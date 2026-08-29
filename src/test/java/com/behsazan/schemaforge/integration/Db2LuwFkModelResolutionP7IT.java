package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.metadata.repository.Db2SysColumnsFileCatalog;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence-only DB2 LUW P7.1/P7.2 FK model-resolution audit.
 *
 * <p>This runner never rewrites canonical snapshots, parser output, or generated SQL. It consumes
 * the P6 structural-audit CSV and corroborates unresolved parent references against three
 * independent evidence sources: generated DB2 LUW table history, recovered canonical snapshots,
 * and the offline DB2 SYSIBM.SYSCOLUMNS export.</p>
 *
 * <p>P7.1 output is deliberately conservative: CONFIRMED_RENAME requires a strong normalized-name
 * relation plus compatible referenced columns and absence of the originally requested parent.
 * POSSIBLE_ALIAS is evidence for review only. No automatic FK target rewrite is performed.</p>
 */
class Db2LuwFkModelResolutionP7IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();
    private final CanonicalSnapshotJsonStore snapshotStore = new CanonicalSnapshotJsonStore();

    @Test
    void loadsHistoricalCanonicalSnapshotForEvidenceWithoutRuntimeCompatibilityCheck(@TempDir Path tempDir)
            throws Exception {
        Path snapshotFile = tempDir.resolve("legacy.schema.json");
        CanonicalSchemaSnapshot snapshot = new CanonicalSchemaSnapshot(
                "1.0",
                "4",
                "word-pipeline-v4-2026-08-17-legacy-metadata-recovery10-final",
                "2026-08-17T00:00:00Z",
                new CanonicalSchemaSnapshot.SourceSnapshot(
                        "legacy/sample.doc", "sample.doc", "sha", 1L, "2026-08-17T00:00:00Z", "legacy"),
                new CanonicalSchemaSnapshot.SchemaSnapshot(
                        "TSTSHMA",
                        "",
                        Map.of(),
                        List.of(new CanonicalSchemaSnapshot.TableSnapshot(
                                "TSTSHMA",
                                "LEGACY_PARENT",
                                "",
                                "",
                                List.of(new CanonicalSchemaSnapshot.ColumnSnapshot(
                                        "ID", null, false, null, "", false, 1, null, Map.of())),
                                null,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                Map.of())),
                        List.of()));
        snapshotStore.writeSnapshot(snapshotFile, snapshot);

        CanonicalInventory inventory = loadCanonicalInventory(tempDir);

        assertTrue(inventory.tables().containsKey("TSTSHMA.LEGACY_PARENT"));
        assertEquals(Set.of("ID"), inventory.tables().get("TSTSHMA.LEGACY_PARENT").columns());
    }

    @Test
    void auditsP7NameAliasAndMissingParentEvidence() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set schemaforge.db2luw.p7.snapshotDir, schemaforge.db2luw.p7.sqlRoot and "
                        + "schemaforge.db2luw.p7.db2SysColumnsFile to run P7.1/P7.2 audit.");

        Path p6AuditFile = resolveP6AuditFile(config.p6AuditFile(),
                Path.of("target", "db2luw-fk-structural-audit"));
        assertTrue(Files.isRegularFile(p6AuditFile), missingP6Message(config.p6AuditFile(), p6AuditFile));

        List<P6Row> p6Rows = readP6Csv(p6AuditFile);
        List<P6Row> nameAliasRows = p6Rows.stream()
                .filter(row -> "NAME_OR_ALIAS_DRIFT".equals(row.p6Classification()))
                .toList();
        List<P6Row> missingRows = p6Rows.stream()
                .filter(row -> "MISSING_FROM_GENERATED_CORPUS".equals(row.p6Classification()))
                .toList();

        GeneratedInventory generated = loadGeneratedInventory(config.sqlRoot(), config.fileSuffix());
        CanonicalInventory canonical = loadCanonicalInventory(config.snapshotDir());
        Db2SysColumnsFileCatalog db2 = new Db2SysColumnsFileCatalog(config.db2SysColumnsFile());

        List<NameAliasResolution> aliases = nameAliasRows.stream()
                .map(row -> resolveNameAlias(row, generated, canonical, db2))
                .sorted(Comparator.comparing((NameAliasResolution row) -> row.input().referencedTable())
                        .thenComparing(row -> row.input().sourceTable())
                        .thenComparing(row -> row.input().constraintName()))
                .toList();
        List<MissingParentResolution> missing = missingRows.stream()
                .map(row -> resolveMissingParent(row, generated, canonical, db2))
                .sorted(Comparator.comparing((MissingParentResolution row) -> row.input().referencedTable())
                        .thenComparing(row -> row.input().sourceTable())
                        .thenComparing(row -> row.input().constraintName()))
                .toList();

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        writeNameAliasCsv(reportDir.resolve("db2luw-fk-p7.1-name-alias-resolution.csv"), aliases);
        writeMissingParentCsv(reportDir.resolve("db2luw-fk-p7.2-missing-parent-resolution.csv"), missing);
        String summary = summary(config, p6AuditFile, generated, canonical, db2, aliases, missing, reportDir);
        Files.writeString(reportDir.resolve("db2luw-fk-p7-resolution-summary.txt"), summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        if (config.expectedNameAliasRows() >= 0) {
            assertEquals(config.expectedNameAliasRows(), aliases.size(), "Unexpected P7.1 input count");
        }
        if (config.expectedMissingRows() >= 0) {
            assertEquals(config.expectedMissingRows(), missing.size(), "Unexpected P7.2 input count");
        }
        assertEquals(nameAliasRows.size(), aliases.size(), "Every P7.1 row must be classified");
        assertEquals(missingRows.size(), missing.size(), "Every P7.2 row must be classified");
    }

    private NameAliasResolution resolveNameAlias(
            P6Row row,
            GeneratedInventory generated,
            CanonicalInventory canonical,
            Db2SysColumnsFileCatalog db2) {
        String requested = qualifiedKey(row.referencedTable());
        String requestedSchema = schemaName(requested);
        String requestedObject = objectName(requested);
        String candidateObject = objectName(row.evidence());
        Set<String> requestedColumns = columnSet(row.referencedColumns());

        boolean requestedGenerated = generated.tables().containsKey(requested);
        boolean requestedCanonical = canonical.tables().containsKey(requested);
        boolean requestedDb2 = db2TableExists(db2, requestedSchema, requestedObject);

        CandidateEvidence candidate = candidateEvidence(
                requestedSchema, candidateObject, requestedColumns, generated, canonical, db2);
        Relation relation = relation(requestedObject, candidateObject);

        String classification;
        if (!requestedGenerated && !requestedCanonical && !requestedDb2
                && relation == Relation.LEGACY_PREFIX_NORMALIZED
                && candidate.exists()
                && candidate.columnsCompatible()) {
            classification = "CONFIRMED_RENAME";
        } else if (!candidateObject.isBlank()
                && candidate.exists()
                && relation != Relation.NONE) {
            classification = "POSSIBLE_ALIAS";
        } else {
            classification = "UNRESOLVED";
        }

        String evidence = String.join("; ", List.of(
                "requested_generated=" + requestedGenerated,
                "requested_canonical=" + requestedCanonical,
                "requested_db2=" + requestedDb2,
                "candidate=" + candidate.qualifiedName(),
                "relation=" + relation.name(),
                "candidate_generated=" + candidate.generated(),
                "candidate_canonical=" + candidate.canonical(),
                "candidate_db2=" + candidate.db2(),
                "columns_compatible=" + candidate.columnsCompatible()));
        return new NameAliasResolution(row, classification, candidate.qualifiedName(), relation.name(), evidence);
    }

    private MissingParentResolution resolveMissingParent(
            P6Row row,
            GeneratedInventory generated,
            CanonicalInventory canonical,
            Db2SysColumnsFileCatalog db2) {
        String requested = qualifiedKey(row.referencedTable());
        String schema = schemaName(requested);
        String table = objectName(requested);
        Set<String> requestedColumns = columnSet(row.referencedColumns());

        TableEvidence canonicalExact = canonical.tables().get(requested);
        boolean db2Exact = db2TableExists(db2, schema, table);
        String near = nearestCandidate(requested, requestedColumns, generated, canonical);

        String classification;
        String evidence;
        if (canonicalExact != null) {
            classification = "CANONICAL_PRESENT_GENERATION_BLOCKED";
            evidence = "canonical=" + canonicalExact.source()
                    + "; canonical_columns_compatible=" + canonicalExact.columns().containsAll(requestedColumns);
        } else if (db2Exact) {
            classification = "EXTERNAL_OR_SHARED_DEPENDENCY";
            evidence = "exact table exists in offline DB2 SYSCOLUMNS but is absent from recovered canonical/generated corpus";
        } else if (!near.isBlank()) {
            classification = "STALE_LEGACY_REFERENCE";
            evidence = "near_candidate=" + near + "; relation=" + relation(table, objectName(near)).name();
        } else {
            classification = "CANONICAL_ABSENT";
            evidence = "no exact canonical/generated/DB2 parent and no strong historical name candidate";
        }
        return new MissingParentResolution(row, classification, near, evidence);
    }

    private CandidateEvidence candidateEvidence(
            String requestedSchema,
            String candidateObject,
            Set<String> requestedColumns,
            GeneratedInventory generated,
            CanonicalInventory canonical,
            Db2SysColumnsFileCatalog db2) {
        if (candidateObject.isBlank()) return CandidateEvidence.empty();
        String exact = requestedSchema.isBlank() ? candidateObject : requestedSchema + "." + candidateObject;
        String candidateQualified = generated.tables().containsKey(exact) || canonical.tables().containsKey(exact)
                ? exact
                : findByObjectName(candidateObject, generated, canonical);
        if (candidateQualified.isBlank()) candidateQualified = exact;

        TableEvidence generatedEvidence = generated.tables().get(candidateQualified);
        TableEvidence canonicalEvidence = canonical.tables().get(candidateQualified);
        boolean generatedExists = generatedEvidence != null;
        boolean canonicalExists = canonicalEvidence != null;
        boolean db2Exists = db2TableExists(db2, schemaName(candidateQualified), objectName(candidateQualified));
        boolean columnsCompatible = requestedColumns.isEmpty()
                || (generatedEvidence != null && generatedEvidence.columns().containsAll(requestedColumns))
                || (canonicalEvidence != null && canonicalEvidence.columns().containsAll(requestedColumns));
        return new CandidateEvidence(candidateQualified, generatedExists, canonicalExists, db2Exists, columnsCompatible);
    }

    private GeneratedInventory loadGeneratedInventory(Path root, String suffix) throws IOException {
        Map<String, TableEvidence> tables = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList()) {
                String script = Files.readString(file, StandardCharsets.UTF_8);
                for (String raw : splitter.parse(script, DatabasePlatform.DB2_LUW)) {
                    String sql = stripLeadingComments(raw);
                    Matcher create = CREATE_TABLE.matcher(sql);
                    if (!create.find()) continue;
                    String table = qualifiedKey(create.group(1));
                    Set<String> columns = createTableColumns(sql, create.end());
                    String source = normalize(root.relativize(file));
                    TableEvidence previous = tables.get(table);
                    if (previous == null) {
                        tables.put(table, new TableEvidence(table, columns, source));
                    } else {
                        Set<String> merged = new LinkedHashSet<>(previous.columns());
                        merged.addAll(columns);
                        tables.put(table, new TableEvidence(table, Set.copyOf(merged), source));
                    }
                }
            }
        }
        return new GeneratedInventory(Map.copyOf(tables));
    }

    private CanonicalInventory loadCanonicalInventory(Path root) throws IOException {
        Map<String, TableEvidence> tables = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(Db2LuwFkModelResolutionP7IT::isSnapshot)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList()) {
                try {
                    CanonicalSchemaSnapshot snapshot = snapshotStore.readSnapshot(file);
                    CanonicalSchemaSnapshot.SchemaSnapshot schema = snapshot.schema();
                    if (schema == null || schema.tables() == null) {
                        continue;
                    }
                    for (CanonicalSchemaSnapshot.TableSnapshot table : schema.tables()) {
                        if (table == null || table.name() == null || table.name().isBlank()) {
                            continue;
                        }
                        String qualified = table.schema() == null || table.schema().isBlank()
                                ? table.name()
                                : table.schema() + "." + table.name();
                        String name = qualifiedKey(qualified);
                        Set<String> columns = new LinkedHashSet<>();
                        if (table.columns() != null) {
                            for (CanonicalSchemaSnapshot.ColumnSnapshot column : table.columns()) {
                                if (column != null && column.name() != null && !column.name().isBlank()) {
                                    columns.add(id(column.name()));
                                }
                            }
                        }
                        String source = snapshot.source() != null && snapshot.source().relativePath() != null
                                ? snapshot.source().relativePath()
                                : normalize(root.relativize(file));
                        TableEvidence previous = tables.get(name);
                        if (previous == null) {
                            tables.put(name, new TableEvidence(name, Set.copyOf(columns), source));
                        } else {
                            Set<String> merged = new LinkedHashSet<>(previous.columns());
                            merged.addAll(columns);
                            tables.put(name, new TableEvidence(name, Set.copyOf(merged), source));
                        }
                    }
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Cannot load canonical snapshot for P7 audit: " + file, exception);
                }
            }
        }
        return new CanonicalInventory(Map.copyOf(tables));
    }

    private static String nearestCandidate(
            String requested,
            Set<String> requestedColumns,
            GeneratedInventory generated,
            CanonicalInventory canonical) {
        String target = objectName(requested);
        List<String> candidates = new ArrayList<>();
        candidates.addAll(generated.tables().keySet());
        candidates.addAll(canonical.tables().keySet());
        return candidates.stream()
                .distinct()
                .filter(candidate -> !candidate.equals(requested))
                .filter(candidate -> relation(target, objectName(candidate)) != Relation.NONE)
                .filter(candidate -> {
                    TableEvidence generatedEvidence = generated.tables().get(candidate);
                    TableEvidence canonicalEvidence = canonical.tables().get(candidate);
                    return requestedColumns.isEmpty()
                            || (generatedEvidence != null && generatedEvidence.columns().containsAll(requestedColumns))
                            || (canonicalEvidence != null && canonicalEvidence.columns().containsAll(requestedColumns));
                })
                .sorted(Comparator
                        .comparing((String candidate) -> relation(target, objectName(candidate)).rank())
                        .thenComparingInt(String::length)
                        .thenComparing(String::compareTo))
                .findFirst().orElse("");
    }

    private static String findByObjectName(
            String objectNameValue,
            GeneratedInventory generated,
            CanonicalInventory canonical) {
        return Stream.concat(generated.tables().keySet().stream(), canonical.tables().keySet().stream())
                .distinct()
                .filter(candidate -> objectName(candidate).equals(id(objectNameValue)))
                .sorted()
                .findFirst().orElse("");
    }

    private static boolean db2TableExists(Db2SysColumnsFileCatalog catalog, String schema, String table) {
        if (schema.isBlank() || table.isBlank()) return false;
        return catalog.lookupStatus(schema, table, "__SCHEMAFORGE_P7_TABLE_PROBE__")
                != Db2SysColumnsFileCatalog.LookupStatus.TABLE_NOT_FOUND;
    }

    private static Set<String> createTableColumns(String sql, int createEnd) {
        int open = firstCodeParen(sql, createEnd);
        int close = matchingParen(sql, open);
        if (open < 0 || close <= open) return Set.of();
        String body = sql.substring(open + 1, close);
        Set<String> columns = new LinkedHashSet<>();
        for (String item : splitTopLevel(body)) {
            String trimmed = item.trim();
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CONSTRAINT ") || upper.startsWith("PRIMARY KEY")
                    || upper.startsWith("UNIQUE ") || upper.startsWith("CHECK ")
                    || upper.startsWith("FOREIGN KEY")) continue;
            Matcher idMatcher = Pattern.compile("^\\s*(" + IDENTIFIER + ")").matcher(trimmed);
            if (idMatcher.find()) columns.add(id(idMatcher.group(1)));
        }
        return Set.copyOf(columns);
    }

    private static List<P6Row> readP6Csv(Path file) throws IOException {
        List<P6Row> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = in.readLine();
            if (first == null) return List.of();
            List<String> headers = parseCsvLine(first);
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) index.put(headers.get(i), i);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                rows.add(new P6Row(
                        get(values, index, "source_file"),
                        get(values, index, "statement"),
                        get(values, index, "source_table"),
                        get(values, index, "constraint_name"),
                        get(values, index, "source_columns"),
                        get(values, index, "referenced_table"),
                        get(values, index, "referenced_columns"),
                        get(values, index, "validation_reason"),
                        get(values, index, "p6_classification"),
                        get(values, index, "evidence")));
            }
        }
        return List.copyOf(rows);
    }

    private static void writeNameAliasCsv(Path file, List<NameAliasResolution> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("source_file,source_table,constraint_name,source_columns,referenced_table,referenced_columns,p6_candidate,p7_classification,resolved_candidate,name_relation,evidence\n");
            for (NameAliasResolution row : rows) {
                P6Row in = row.input();
                out.write(String.join(",",
                        csv(in.sourceFile()), csv(in.sourceTable()), csv(in.constraintName()), csv(in.sourceColumns()),
                        csv(in.referencedTable()), csv(in.referencedColumns()), csv(in.evidence()),
                        csv(row.classification()), csv(row.candidate()), csv(row.relation()), csv(row.evidence())));
                out.newLine();
            }
        }
    }

    private static void writeMissingParentCsv(Path file, List<MissingParentResolution> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("source_file,source_table,constraint_name,source_columns,referenced_table,referenced_columns,p7_classification,near_candidate,evidence\n");
            for (MissingParentResolution row : rows) {
                P6Row in = row.input();
                out.write(String.join(",",
                        csv(in.sourceFile()), csv(in.sourceTable()), csv(in.constraintName()), csv(in.sourceColumns()),
                        csv(in.referencedTable()), csv(in.referencedColumns()), csv(row.classification()),
                        csv(row.nearCandidate()), csv(row.evidence())));
                out.newLine();
            }
        }
    }

    private static String summary(
            Config config,
            Path p6AuditFile,
            GeneratedInventory generated,
            CanonicalInventory canonical,
            Db2SysColumnsFileCatalog db2,
            List<NameAliasResolution> aliases,
            List<MissingParentResolution> missing,
            Path reportDir) {
        Map<String, Long> aliasCounts = counts(aliases.stream().map(NameAliasResolution::classification).toList());
        Map<String, Long> missingCounts = counts(missing.stream().map(MissingParentResolution::classification).toList());
        StringBuilder out = new StringBuilder();
        out.append("DB2 LUW FK Model Resolution P7.1/P7.2\n")
                .append("====================================\n")
                .append("P6 audit file          : ").append(p6AuditFile).append('\n')
                .append("Generated SQL root     : ").append(config.sqlRoot()).append('\n')
                .append("Canonical snapshot dir : ").append(config.snapshotDir()).append('\n')
                .append("DB2 SYSCOLUMNS         : ").append(config.db2SysColumnsFile()).append('\n')
                .append("Generated table keys   : ").append(generated.tables().size()).append('\n')
                .append("Canonical table keys   : ").append(canonical.tables().size()).append('\n')
                .append("DB2 SYSCOLUMNS rows    : ").append(db2.sourceRows()).append('\n')
                .append('\n')
                .append("P7.1 Name/Alias Drift rows : ").append(aliases.size()).append('\n');
        aliasCounts.forEach((key, value) -> out.append(String.format(Locale.ROOT, "  %-32s %d%n", key, value)));
        out.append('\n').append("P7.2 Missing Parent rows   : ").append(missing.size()).append('\n');
        missingCounts.forEach((key, value) -> out.append(String.format(Locale.ROOT, "  %-32s %d%n", key, value)));
        out.append('\n').append("Mutation policy         : EVIDENCE ONLY; NO AUTO REWRITE\n")
                .append("Report directory        : ").append(reportDir).append('\n');
        return out.toString();
    }

    private static Map<String, Long> counts(List<String> values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        values.stream().sorted().forEach(value -> counts.merge(value, 1L, Long::sum));
        return counts;
    }

    private static Path resolveP6AuditFile(Path configured, Path reportRoot) throws IOException {
        if (configured != null && Files.isRegularFile(configured)) {
            return configured;
        }
        Path latest = latestP6Audit(reportRoot);
        if (Files.isRegularFile(latest)) {
            if (configured != null) {
                System.out.println("Configured P6 audit CSV is missing; using latest available report instead: " + latest);
            }
            return latest;
        }
        return configured != null ? configured : latest;
    }

    private static String missingP6Message(Path configured, Path resolved) {
        StringBuilder message = new StringBuilder("P6 audit CSV not found: ").append(resolved);
        if (configured != null) {
            message.append(" (configured path was ").append(configured).append(')');
        }
        return message.append(". Rebuild the DB2 LUW FK validation report with Db2LuwForeignKeyDirectoryExecutionIT, ")
                .append("then run Db2LuwForeignKeyStructuralAuditTest. P7 can then be rerun without ")
                .append("schemaforge.db2luw.p7.p6AuditFile; it will select the latest P6 report automatically.")
                .toString();
    }

    private static Path latestP6Audit(Path root) throws IOException {
        if (!Files.isDirectory(root)) return root.resolve("db2luw-fk-structural-audit.csv");
        try (Stream<Path> paths = Files.walk(root, 2)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("db2luw-fk-structural-audit.csv"))
                    .max(Comparator.comparing(path -> path.getParent().getFileName().toString()))
                    .orElse(root.resolve("db2luw-fk-structural-audit.csv"));
        }
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static Relation relation(String left, String right) {
        String a = id(left);
        String b = id(right);
        if (a.isBlank() || b.isBlank() || a.equals(b)) return Relation.NONE;
        if (stripLegacyPrefixes(a).equals(stripLegacyPrefixes(b))) return Relation.LEGACY_PREFIX_NORMALIZED;
        if (a.endsWith(b) || b.endsWith(a)) return Relation.SUFFIX_OVERLAP;
        return Relation.NONE;
    }

    private static String stripLegacyPrefixes(String value) {
        String upper = id(value);
        for (String prefix : List.of("JT", "J")) {
            if (upper.startsWith(prefix) && upper.length() > prefix.length() + 2) {
                return upper.substring(prefix.length());
            }
        }
        return upper;
    }

    private static Set<String> columnSet(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.split("\\|")) {
            if (!token.isBlank()) result.add(id(token));
        }
        return Set.copyOf(result);
    }

    private static String qualifiedKey(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s*\\.\\s*", ".").replace("\"", "").trim().toUpperCase(Locale.ROOT);
    }

    private static String schemaName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private static String objectName(String qualified) {
        String normalized = qualifiedKey(qualified);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }

    private static String id(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1).replace("\"\"", "\"");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static int firstCodeParen(String value, int start) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = Math.max(0, start); i < value.length(); i++) {
            char ch = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && ch == '-' && next == '-') { lineComment = true; i++; continue; }
            if (!singleQuoted && !doubleQuoted && ch == '/' && next == '*') { blockComment = true; i++; continue; }
            if (!doubleQuoted && ch == '\'') {
                if (singleQuoted && next == '\'') { i++; continue; }
                singleQuoted = !singleQuoted;
                continue;
            }
            if (!singleQuoted && ch == '"') {
                if (doubleQuoted && next == '"') { i++; continue; }
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && ch == '(') return i;
        }
        return -1;
    }

    private static int matchingParen(String value, int open) {
        if (open < 0) return -1;
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (quoted) continue;
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    result.add(token.toString());
                    token.setLength(0);
                    continue;
                }
            }
            token.append(ch);
        }
        if (!token.toString().isBlank()) result.add(token.toString());
        return result;
    }

    private static List<String> parseCsvLine(String line) {
        if (line == null) return List.of();
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    token.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                result.add(token.toString());
                token.setLength(0);
            } else {
                token.append(ch);
            }
        }
        result.add(token.toString());
        return result;
    }

    private static String get(List<String> values, Map<String, Integer> index, String key) {
        Integer i = index.get(key);
        return i == null || i >= values.size() ? "" : values.get(i);
    }

    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql;
        boolean changed;
        do {
            changed = false;
            String trimmed = value.stripLeading();
            if (trimmed.startsWith("--")) {
                int n = trimmed.indexOf('\n');
                value = n < 0 ? "" : trimmed.substring(n + 1);
                changed = true;
            } else if (trimmed.startsWith("/*")) {
                int e = trimmed.indexOf("*/", 2);
                value = e < 0 ? "" : trimmed.substring(e + 2);
                changed = true;
            }
        } while (changed);
        return value.stripLeading();
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private record Config(
            Path sqlRoot,
            String fileSuffix,
            Path snapshotDir,
            Path db2SysColumnsFile,
            Path p6AuditFile,
            Path reportBase,
            int expectedNameAliasRows,
            int expectedMissingRows) {
        static Config load() {
            return new Config(
                    path(System.getProperty("schemaforge.db2luw.p7.sqlRoot")),
                    System.getProperty("schemaforge.db2luw.p7.fileSuffix", ".db2luw.sql"),
                    path(System.getProperty("schemaforge.db2luw.p7.snapshotDir")),
                    path(System.getProperty("schemaforge.db2luw.p7.db2SysColumnsFile")),
                    path(System.getProperty("schemaforge.db2luw.p7.p6AuditFile")),
                    pathOrDefault(System.getProperty("schemaforge.db2luw.p7.reportBase"),
                            Path.of("target", "db2luw-fk-p7-resolution")),
                    integer(System.getProperty("schemaforge.db2luw.p7.expectedNameAliasRows"), 104),
                    integer(System.getProperty("schemaforge.db2luw.p7.expectedMissingRows"), 68));
        }

        boolean enabled() {
            return sqlRoot != null && Files.isDirectory(sqlRoot)
                    && snapshotDir != null && Files.isDirectory(snapshotDir)
                    && db2SysColumnsFile != null && Files.isRegularFile(db2SysColumnsFile);
        }

        private static Path path(String value) {
            return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
        }

        private static Path pathOrDefault(String value, Path fallback) {
            return value == null || value.isBlank()
                    ? fallback.toAbsolutePath().normalize()
                    : Path.of(value).toAbsolutePath().normalize();
        }

        private static int integer(String value, int fallback) {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        }
    }

    private enum Relation {
        LEGACY_PREFIX_NORMALIZED(0),
        SUFFIX_OVERLAP(1),
        NONE(2);

        private final int rank;

        Relation(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }
    }

    private record P6Row(
            String sourceFile,
            String statement,
            String sourceTable,
            String constraintName,
            String sourceColumns,
            String referencedTable,
            String referencedColumns,
            String validationReason,
            String p6Classification,
            String evidence) {}

    private record TableEvidence(String qualifiedName, Set<String> columns, String source) {}
    private record GeneratedInventory(Map<String, TableEvidence> tables) {}
    private record CanonicalInventory(Map<String, TableEvidence> tables) {}
    private record CandidateEvidence(
            String qualifiedName,
            boolean generated,
            boolean canonical,
            boolean db2,
            boolean columnsCompatible) {
        static CandidateEvidence empty() {
            return new CandidateEvidence("", false, false, false, false);
        }

        boolean exists() {
            return generated || canonical || db2;
        }
    }

    private record NameAliasResolution(
            P6Row input,
            String classification,
            String candidate,
            String relation,
            String evidence) {}

    private record MissingParentResolution(
            P6Row input,
            String classification,
            String nearCandidate,
            String evidence) {}
}
