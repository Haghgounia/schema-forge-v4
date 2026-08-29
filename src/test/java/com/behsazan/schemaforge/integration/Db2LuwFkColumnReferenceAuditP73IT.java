package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.metadata.repository.Db2SysColumnsFileCatalog;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
 * Evidence-only DB2 LUW P7.3 referenced-column audit.
 *
 * <p>The runner consumes only P6 rows classified as COLUMN_REFERENCE_OR_EXTRACTION_GAP and
 * corroborates the requested referenced column against the current generated DB2 LUW corpus,
 * recovered canonical snapshots, and the offline historical SYSIBM.SYSCOLUMNS export.</p>
 *
 * <p>Classifications are diagnostic only: COLUMN_RENAMED, COLUMN_REMOVED,
 * COLUMN_NEVER_EXISTED, or MODEL_INCOMPLETE. No FK, canonical snapshot, parser output, or
 * generated SQL is rewritten.</p>
 */
class Db2LuwFkColumnReferenceAuditP73IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();
    private final CanonicalSnapshotJsonStore snapshotStore = new CanonicalSnapshotJsonStore();

    @Test
    void auditsP73ColumnReferenceEvidence() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set schemaforge.db2luw.p7.sqlRoot, schemaforge.db2luw.p7.snapshotDir and "
                        + "schemaforge.db2luw.p7.db2SysColumnsFile to run P7.3 audit.");

        Path p6Audit = resolveP6AuditFile(config.p6AuditFile(),
                Path.of("target", "db2luw-fk-structural-audit"));
        assertTrue(Files.isRegularFile(p6Audit), "P6 audit CSV not found: " + p6Audit);

        List<P6Row> rows = readP6Csv(p6Audit).stream()
                .filter(row -> "COLUMN_REFERENCE_OR_EXTRACTION_GAP".equals(row.p6Classification()))
                .toList();
        Set<String> targetTables = rows.stream()
                .map(P6Row::referencedTable)
                .map(Db2LuwFkColumnReferenceAuditP73IT::qualifiedKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, TableEvidence> generated = loadGeneratedInventory(config.sqlRoot(), config.fileSuffix(), targetTables);
        Map<String, TableEvidence> canonical = loadCanonicalInventory(config.snapshotDir(), targetTables);
        Db2SysColumnsFileCatalog db2 = new Db2SysColumnsFileCatalog(config.db2SysColumnsFile());

        List<Resolution> resolved = rows.stream()
                .map(row -> resolve(row, generated, canonical, db2))
                .sorted(Comparator.comparing((Resolution row) -> row.input().referencedTable())
                        .thenComparing(row -> row.input().referencedColumns())
                        .thenComparing(row -> row.input().sourceTable())
                        .thenComparing(row -> row.input().constraintName()))
                .toList();

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Path csv = reportDir.resolve("db2luw-fk-p7.3-column-reference-audit.csv");
        writeCsv(csv, resolved);
        String summary = summary(config, p6Audit, generated, canonical, db2, resolved, reportDir);
        Files.writeString(reportDir.resolve("db2luw-fk-p7.3-column-reference-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        if (config.expectedRows() >= 0) {
            assertEquals(config.expectedRows(), resolved.size(), "Unexpected P7.3 input count");
        }
        assertEquals(rows.size(), resolved.size(), "Every P7.3 row must be classified");
    }

    @Test
    void conservativeColumnCandidateRulesPreferUniqueStrongRelations() {
        Set<String> columns = Set.of("USERID", "USERNAME", "ACCNO", "REGCOUNTRYCODE", "PROFITID");
        assertEquals(new ColumnCandidate("USERID", "ROLE_PREFIX_SUFFIX", 1),
                candidate("STATUSUSERID", columns));
        assertEquals(new ColumnCandidate("REGCOUNTRYCODE", "OLD_NEW_NORMALIZED", 0),
                candidate("OLDREGCOUNTRYCODE", columns));
        assertEquals(new ColumnCandidate("PROFITID", "OLD_NEW_NORMALIZED", 0),
                candidate("PROFITIDNEW", columns));
        assertEquals(new ColumnCandidate("ACCNO", "ROLE_PREFIX_SUFFIX", 1),
                candidate("MAINACCNO", columns));
        assertEquals(ColumnCandidate.none(), candidate("TOTALLYUNKNOWN", columns));
    }

    private static Resolution resolve(
            P6Row row,
            Map<String, TableEvidence> generated,
            Map<String, TableEvidence> canonical,
            Db2SysColumnsFileCatalog db2) {
        String table = qualifiedKey(row.referencedTable());
        String requestedColumn = id(firstColumn(row.referencedColumns()));
        TableEvidence generatedTable = generated.get(table);
        TableEvidence canonicalTable = canonical.get(table);
        boolean generatedExact = generatedTable != null && generatedTable.columns().contains(requestedColumn);
        boolean canonicalExact = canonicalTable != null && canonicalTable.columns().contains(requestedColumn);
        Db2SysColumnsFileCatalog.LookupStatus db2Status = db2.lookupStatus(
                schemaName(table), objectName(table), requestedColumn);
        boolean db2Exact = db2Status == Db2SysColumnsFileCatalog.LookupStatus.USABLE
                || db2Status == Db2SysColumnsFileCatalog.LookupStatus.INCOMPLETE
                || db2Status == Db2SysColumnsFileCatalog.LookupStatus.AMBIGUOUS;

        Set<String> currentColumns = new LinkedHashSet<>();
        if (generatedTable != null) currentColumns.addAll(generatedTable.columns());
        if (canonicalTable != null) currentColumns.addAll(canonicalTable.columns());
        currentColumns.remove(requestedColumn);
        ColumnCandidate candidate = candidate(requestedColumn, currentColumns);

        String classification;
        if (generatedExact || canonicalExact) {
            classification = "MODEL_INCOMPLETE";
        } else if (candidate.present()) {
            classification = "COLUMN_RENAMED";
        } else if (db2Exact) {
            classification = "COLUMN_REMOVED";
        } else {
            classification = "COLUMN_NEVER_EXISTED";
        }

        String generatedSource = generatedTable == null ? "" : generatedTable.source();
        String canonicalSource = canonicalTable == null ? "" : canonicalTable.source();
        String evidence = String.join("; ", List.of(
                "generated_parent=" + (generatedTable != null),
                "canonical_parent=" + (canonicalTable != null),
                "generated_exact_column=" + generatedExact,
                "canonical_exact_column=" + canonicalExact,
                "db2_exact_status=" + db2Status.name(),
                "candidate=" + candidate.column(),
                "candidate_relation=" + candidate.relation(),
                "generated_source=" + generatedSource,
                "canonical_source=" + canonicalSource));
        return new Resolution(row, classification, candidate.column(), candidate.relation(), db2Status.name(), evidence);
    }

    private static ColumnCandidate candidate(String requested, Set<String> currentColumns) {
        List<ColumnCandidate> candidates = currentColumns.stream()
                .map(column -> relationCandidate(requested, column))
                .filter(ColumnCandidate::present)
                .sorted(Comparator.comparingInt(ColumnCandidate::rank)
                        .thenComparingInt(candidate -> candidate.column().length())
                        .thenComparing(ColumnCandidate::column))
                .toList();
        if (candidates.isEmpty()) return ColumnCandidate.none();
        ColumnCandidate first = candidates.getFirst();
        long sameRank = candidates.stream().filter(candidate -> candidate.rank() == first.rank()).count();
        return sameRank == 1 ? first : ColumnCandidate.none();
    }

    private static ColumnCandidate relationCandidate(String requestedValue, String candidateValue) {
        String requested = id(requestedValue);
        String candidate = id(candidateValue);
        if (requested.isBlank() || candidate.isBlank() || requested.equals(candidate)) return ColumnCandidate.none();

        if (oldNewNormalized(requested).equals(oldNewNormalized(candidate))
                && !oldNewNormalized(requested).equals(requested)) {
            return new ColumnCandidate(candidate, "OLD_NEW_NORMALIZED", 0);
        }
        if (candidate.length() >= 4 && requested.endsWith(candidate) && requested.length() > candidate.length()) {
            return new ColumnCandidate(candidate, "ROLE_PREFIX_SUFFIX", 1);
        }
        if (requested.length() >= 5 && candidate.endsWith(requested) && candidate.length() > requested.length()) {
            return new ColumnCandidate(candidate, "EXTENDED_NAME_SUFFIX", 2);
        }
        if (requested.length() >= 6 && candidate.length() >= 6 && levenshtein(requested, candidate) <= 2) {
            return new ColumnCandidate(candidate, "NEAR_TYPO", 3);
        }
        return ColumnCandidate.none();
    }

    private static String oldNewNormalized(String value) {
        String result = id(value);
        boolean changed;
        do {
            changed = false;
            for (String marker : List.of("OLD", "NEW")) {
                if (result.startsWith(marker) && result.length() > marker.length() + 3) {
                    result = result.substring(marker.length());
                    changed = true;
                }
                if (result.endsWith(marker) && result.length() > marker.length() + 3) {
                    result = result.substring(0, result.length() - marker.length());
                    changed = true;
                }
            }
            String withoutDigits = result.replaceFirst("[0-9]+$", "");
            if (!withoutDigits.equals(result) && withoutDigits.length() >= 4) {
                result = withoutDigits;
                changed = true;
            }
        } while (changed);
        return result;
    }

    private static int levenshtein(String left, String right) {
        int[] prev = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) prev[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] cur = new int[right.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[right.length()];
    }

    private Map<String, TableEvidence> loadGeneratedInventory(Path root, String suffix, Set<String> targetTables)
            throws IOException {
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
                    if (!targetTables.contains(table)) continue;
                    merge(tables, table, createTableColumns(sql, create.end()), normalize(root.relativize(file)));
                }
            }
        }
        return Map.copyOf(tables);
    }

    private Map<String, TableEvidence> loadCanonicalInventory(Path root, Set<String> targetTables) throws IOException {
        Map<String, TableEvidence> tables = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(Db2LuwFkColumnReferenceAuditP73IT::isSnapshot)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList()) {
                CanonicalSchemaSnapshot snapshot;
                try {
                    snapshot = snapshotStore.readSnapshot(file);
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Cannot load canonical snapshot for P7.3 audit: " + file, exception);
                }
                CanonicalSchemaSnapshot.SchemaSnapshot schema = snapshot.schema();
                if (schema == null || schema.tables() == null) continue;
                for (CanonicalSchemaSnapshot.TableSnapshot tableSnapshot : schema.tables()) {
                    if (tableSnapshot == null || tableSnapshot.name() == null || tableSnapshot.name().isBlank()) continue;
                    String qualified = tableSnapshot.schema() == null || tableSnapshot.schema().isBlank()
                            ? tableSnapshot.name()
                            : tableSnapshot.schema() + "." + tableSnapshot.name();
                    String table = qualifiedKey(qualified);
                    if (!targetTables.contains(table)) continue;
                    Set<String> columns = new LinkedHashSet<>();
                    if (tableSnapshot.columns() != null) {
                        for (CanonicalSchemaSnapshot.ColumnSnapshot column : tableSnapshot.columns()) {
                            if (column != null && column.name() != null && !column.name().isBlank()) {
                                columns.add(id(column.name()));
                            }
                        }
                    }
                    String source = snapshot.source() != null && snapshot.source().relativePath() != null
                            ? snapshot.source().relativePath()
                            : normalize(root.relativize(file));
                    merge(tables, table, columns, source);
                }
            }
        }
        return Map.copyOf(tables);
    }

    private static void merge(Map<String, TableEvidence> tables, String table, Set<String> columns, String source) {
        TableEvidence previous = tables.get(table);
        if (previous == null) {
            tables.put(table, new TableEvidence(Set.copyOf(columns), source));
            return;
        }
        Set<String> merged = new LinkedHashSet<>(previous.columns());
        merged.addAll(columns);
        tables.put(table, new TableEvidence(Set.copyOf(merged), source));
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
                        get(values, index, "source_file"), get(values, index, "source_table"),
                        get(values, index, "constraint_name"), get(values, index, "source_columns"),
                        get(values, index, "referenced_table"), get(values, index, "referenced_columns"),
                        get(values, index, "validation_reason"), get(values, index, "p6_classification")));
            }
        }
        return List.copyOf(rows);
    }

    private static void writeCsv(Path file, List<Resolution> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("source_file,source_table,constraint_name,source_columns,referenced_table,referenced_columns,p7_classification,resolved_column,column_relation,db2_exact_status,evidence\n");
            for (Resolution row : rows) {
                P6Row in = row.input();
                out.write(String.join(",",
                        csv(in.sourceFile()), csv(in.sourceTable()), csv(in.constraintName()), csv(in.sourceColumns()),
                        csv(in.referencedTable()), csv(in.referencedColumns()), csv(row.classification()),
                        csv(row.resolvedColumn()), csv(row.relation()), csv(row.db2Status()), csv(row.evidence())));
                out.newLine();
            }
        }
    }

    private static String summary(
            Config config,
            Path p6Audit,
            Map<String, TableEvidence> generated,
            Map<String, TableEvidence> canonical,
            Db2SysColumnsFileCatalog db2,
            List<Resolution> rows,
            Path reportDir) {
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.stream().map(Resolution::classification).sorted().forEach(value -> counts.merge(value, 1L, Long::sum));
        StringBuilder out = new StringBuilder();
        out.append("DB2 LUW FK Column Reference Audit P7.3\n")
                .append("====================================\n")
                .append("P6 audit file          : ").append(p6Audit).append('\n')
                .append("Generated SQL root     : ").append(config.sqlRoot()).append('\n')
                .append("Canonical snapshot dir : ").append(config.snapshotDir()).append('\n')
                .append("DB2 SYSCOLUMNS         : ").append(config.db2SysColumnsFile()).append('\n')
                .append("Target parent tables   : ").append(rows.stream().map(row -> row.input().referencedTable()).distinct().count()).append('\n')
                .append("Generated parents found: ").append(generated.size()).append('\n')
                .append("Canonical parents found: ").append(canonical.size()).append('\n')
                .append("DB2 SYSCOLUMNS rows    : ").append(db2.sourceRows()).append('\n')
                .append('\n')
                .append("P7.3 rows              : ").append(rows.size()).append('\n');
        counts.forEach((key, value) -> out.append(String.format(Locale.ROOT, "  %-28s %d%n", key, value)));
        out.append('\n')
                .append("Mutation policy         : EVIDENCE ONLY; NO AUTO REWRITE\n")
                .append("Rename candidate policy : UNIQUE STRONG NAME RELATION ONLY\n")
                .append("Report directory        : ").append(reportDir).append('\n');
        return out.toString();
    }

    private static Path resolveP6AuditFile(Path configured, Path root) throws IOException {
        if (configured != null && Files.isRegularFile(configured)) return configured;
        if (!Files.isDirectory(root)) return root.resolve("db2luw-fk-structural-audit.csv");
        try (Stream<Path> paths = Files.walk(root, 2)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("db2luw-fk-structural-audit.csv"))
                    .max(Comparator.comparing(path -> path.getParent().getFileName().toString()))
                    .orElse(root.resolve("db2luw-fk-structural-audit.csv"));
        }
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
            Matcher matcher = Pattern.compile("^\\s*(" + IDENTIFIER + ")").matcher(trimmed);
            if (matcher.find()) columns.add(id(matcher.group(1)));
        }
        return Set.copyOf(columns);
    }

    private static String firstColumn(String value) {
        if (value == null || value.isBlank()) return "";
        int pipe = value.indexOf('|');
        return pipe < 0 ? value : value.substring(0, pipe);
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
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
        boolean singleQuoted = false, doubleQuoted = false, lineComment = false, blockComment = false;
        for (int i = Math.max(0, start); i < value.length(); i++) {
            char ch = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : '\0';
            if (lineComment) { if (ch == '\n' || ch == '\r') lineComment = false; continue; }
            if (blockComment) { if (ch == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (!singleQuoted && !doubleQuoted && ch == '-' && next == '-') { lineComment = true; i++; continue; }
            if (!singleQuoted && !doubleQuoted && ch == '/' && next == '*') { blockComment = true; i++; continue; }
            if (!doubleQuoted && ch == '\'') {
                if (singleQuoted && next == '\'') { i++; continue; }
                singleQuoted = !singleQuoted; continue;
            }
            if (!singleQuoted && ch == '"') {
                if (doubleQuoted && next == '"') { i++; continue; }
                doubleQuoted = !doubleQuoted; continue;
            }
            if (!singleQuoted && !doubleQuoted && ch == '(') return i;
        }
        return -1;
    }

    private static int matchingParen(String value, int open) {
        if (open < 0) return -1;
        int depth = 0; boolean quoted = false;
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
        int depth = 0; boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    result.add(token.toString()); token.setLength(0); continue;
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
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { token.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == ',' && !quoted) { result.add(token.toString()); token.setLength(0); }
            else token.append(ch);
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
                int n = trimmed.indexOf('\n'); value = n < 0 ? "" : trimmed.substring(n + 1); changed = true;
            } else if (trimmed.startsWith("/*")) {
                int e = trimmed.indexOf("*/", 2); value = e < 0 ? "" : trimmed.substring(e + 2); changed = true;
            }
        } while (changed);
        return value.stripLeading();
    }

    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
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
            int expectedRows) {
        static Config load() {
            return new Config(
                    path(System.getProperty("schemaforge.db2luw.p7.sqlRoot")),
                    System.getProperty("schemaforge.db2luw.p7.fileSuffix", ".db2luw.sql"),
                    path(System.getProperty("schemaforge.db2luw.p7.snapshotDir")),
                    path(System.getProperty("schemaforge.db2luw.p7.db2SysColumnsFile")),
                    path(System.getProperty("schemaforge.db2luw.p7.p6AuditFile")),
                    pathOrDefault(System.getProperty("schemaforge.db2luw.p7.p73ReportBase"),
                            Path.of("target", "db2luw-fk-p7.3-column-reference")),
                    integer(System.getProperty("schemaforge.db2luw.p7.expectedColumnReferenceRows"), 75));
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
            return value == null || value.isBlank() ? fallback.toAbsolutePath().normalize()
                    : Path.of(value).toAbsolutePath().normalize();
        }
        private static int integer(String value, int fallback) {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        }
    }

    private record P6Row(
            String sourceFile,
            String sourceTable,
            String constraintName,
            String sourceColumns,
            String referencedTable,
            String referencedColumns,
            String validationReason,
            String p6Classification) {}

    private record TableEvidence(Set<String> columns, String source) {}

    private record ColumnCandidate(String column, String relation, int rank) {
        static ColumnCandidate none() { return new ColumnCandidate("", "NONE", Integer.MAX_VALUE); }
        boolean present() { return !column.isBlank(); }
    }

    private record Resolution(
            P6Row input,
            String classification,
            String resolvedColumn,
            String relation,
            String db2Status,
            String evidence) {}
}
