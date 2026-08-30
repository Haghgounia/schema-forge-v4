package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB2 LUW P8.2 post-resolution key audit for the 12 P8.1 rows that remained blocked after an
 * evidence-confirmed table/column rename. The audit is read-only and never changes generated SQL,
 * canonical snapshots, parser output, or the live catalog.
 */
class Db2LuwPostResolutionKeyAuditP82IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String P81_BLOCKERS_RESOURCE =
            "evidence/db2luw-p8/20260830_102243_952/db2luw-p8-live-blockers.csv";
    private static final String P75_RESOURCE =
            "evidence/db2luw-p7/20260830_095240_805/db2luw-fk-p7.5-key-version-audit.csv";

    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern PRIMARY_KEY = Pattern.compile(
            "(?is)\\bCONSTRAINT\\s+" + IDENTIFIER + "\\s+PRIMARY\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern UNIQUE_CONSTRAINT = Pattern.compile(
            "(?is)\\bCONSTRAINT\\s+" + IDENTIFIER + "\\s+UNIQUE\\s*\\(([^)]*)\\)");
    private static final Pattern UNIQUE_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+UNIQUE\\s+INDEX\\s+" + QUALIFIED_NAME
                    + "\\s+ON\\s+(" + QUALIFIED_NAME + ")\\s*\\(([^)]*)\\)");

    private final CanonicalSnapshotJsonStore snapshotStore = new CanonicalSnapshotJsonStore();
    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void retainedP81EvidenceIsComplete() throws Exception {
        List<BlockerRow> blockers = readBlockersResource(P81_BLOCKERS_RESOURCE);
        List<P75Row> p75 = readP75Resource(P75_RESOURCE);
        assertEquals(12, blockers.size(), "retained P8.1 post-resolution blocker count");
        assertEquals(16, p75.size(), "retained P7.5 key-version row count");
        assertEquals(12, blockers.stream()
                .filter(row -> "REFERENCED_COLUMNS_NOT_PK_OR_UNIQUE".equals(row.reason()))
                .count(), "all retained P8.1 blockers must be key blockers");
        Set<KeyRef> targets = new LinkedHashSet<>();
        blockers.forEach(row -> targets.add(new KeyRef(
                qualifiedKey(row.resolvedReferencedTable()), columnKey(row.resolvedReferencedColumns()))));
        p75.forEach(row -> targets.add(new KeyRef(
                qualifiedKey(row.referencedTable()), columnKey(row.referencedColumns()))));
        assertEquals(6, targets.size(), "retained P8.2 distinct parent-key targets");
        assertEquals(18, blockers.stream()
                        .filter(row -> "TSTSHMA.CTACCOUNTTYPE".equals(qualifiedKey(row.resolvedReferencedTable())))
                        .count()
                        + p75.stream().filter(row -> "TSTSHMA.CTACCOUNTTYPE".equals(qualifiedKey(row.referencedTable())))
                        .count(),
                "CTACCOUNTTYPE.ACCTYPE affected FK references");
    }

    @Test
    void auditsPostResolutionParentKeyEvidenceAcrossAllGeneratedVersions() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set schemaforge.db2luw.p8.sqlRoot and schemaforge.db2luw.p7.snapshotDir to run P8.2 audit.");
        config.validate();

        List<BlockerRow> blockers = readBlockersResource(P81_BLOCKERS_RESOURCE);
        List<P75Row> p75Rows = readP75Resource(P75_RESOURCE);
        Set<KeyRef> targets = new LinkedHashSet<>();
        blockers.forEach(row -> targets.add(new KeyRef(
                qualifiedKey(row.resolvedReferencedTable()), columnKey(row.resolvedReferencedColumns()))));
        p75Rows.forEach(row -> targets.add(new KeyRef(
                qualifiedKey(row.referencedTable()), columnKey(row.referencedColumns()))));

        Map<String, List<GeneratedVersion>> generated = loadGeneratedVersions(config.sqlRoot(), targets);
        Map<String, KeyEvidence> canonical = loadCanonicalKeyInventory(config.snapshotDir(),
                targets.stream().map(KeyRef::table).collect(java.util.stream.Collectors.toSet()));
        Map<KeyRef, List<String>> legacyUk = loadLegacyUkEvidence(config.legacyUkProbeFile());

        List<AuditRow> rows = targets.stream()
                .map(target -> classify(target, blockers, p75Rows, generated, canonical, legacyUk))
                .sorted(Comparator.comparing((AuditRow row) -> row.target().table())
                        .thenComparing(row -> row.target().columns()))
                .toList();

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        writeCsv(reportDir.resolve("db2luw-p8.2-post-resolution-key-audit.csv"), rows);
        String summary = summary(config, blockers, p75Rows, rows, reportDir);
        Files.writeString(reportDir.resolve("db2luw-p8.2-post-resolution-key-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        assertEquals(12, blockers.size());
        assertEquals(16, p75Rows.size());
        assertTrue(rows.size() >= 1, "P8.2 target key set must not be empty");
    }

    private AuditRow classify(
            KeyRef target,
            List<BlockerRow> blockers,
            List<P75Row> p75Rows,
            Map<String, List<GeneratedVersion>> generated,
            Map<String, KeyEvidence> canonical,
            Map<KeyRef, List<String>> legacyUk) {
        List<GeneratedVersion> versions = generated.getOrDefault(target.table(), List.of());
        List<GeneratedVersion> keyVersions = versions.stream()
                .filter(version -> version.keys().contains(target.columns()))
                .toList();
        GeneratedVersion selected = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        boolean selectedHasKey = selected != null && selected.keys().contains(target.columns());

        KeyEvidence canonicalEvidence = canonical.getOrDefault(target.table(), KeyEvidence.empty());
        String canonicalClass = classifyCanonical(canonicalEvidence, target.columns());
        List<String> legacySources = legacyUk.getOrDefault(target, List.of());
        int blockerCount = (int) blockers.stream()
                .filter(row -> target.equals(new KeyRef(
                        qualifiedKey(row.resolvedReferencedTable()), columnKey(row.resolvedReferencedColumns()))))
                .count();
        int p75Count = (int) p75Rows.stream()
                .filter(row -> target.equals(new KeyRef(
                        qualifiedKey(row.referencedTable()), columnKey(row.referencedColumns()))))
                .count();

        String classification;
        if (selectedHasKey) {
            classification = "SELECTED_FINAL_DECLARES_KEY_LIVE_CATALOG_DRIFT";
        } else if (!keyVersions.isEmpty() && !canonicalClass.isBlank()) {
            classification = "HISTORICAL_GENERATED_AND_CANONICAL_KEY_EVIDENCE";
        } else if (!keyVersions.isEmpty()) {
            classification = "HISTORICAL_GENERATED_KEY_EVIDENCE_ONLY";
        } else if (!canonicalClass.isBlank()) {
            classification = "CANONICAL_KEY_EVIDENCE_ONLY";
        } else if (!legacySources.isEmpty()) {
            classification = "LEGACY_WORD_UNIQUE_KEY_EVIDENCE_ONLY";
        } else {
            classification = "NO_INDEPENDENT_KEY_EVIDENCE";
        }

        return new AuditRow(target, blockerCount, p75Count, versions.size(), keyVersions.size(),
                selected == null ? "" : selected.file(), selectedHasKey,
                keyVersions.stream().map(GeneratedVersion::file).toList(), canonicalClass,
                canonicalEvidence.sourcesFor(target.columns()), legacySources, classification);
    }

    private Map<String, List<GeneratedVersion>> loadGeneratedVersions(Path root, Set<KeyRef> targets)
            throws IOException {
        Set<String> targetTables = targets.stream().map(KeyRef::table).collect(java.util.stream.Collectors.toSet());
        Map<String, List<GeneratedVersion>> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".db2luw.sql"))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList();
            for (Path file : files) {
                String script = Files.readString(file, StandardCharsets.UTF_8);
                List<String> statements = splitter.parse(script, DatabasePlatform.DB2_LUW);
                String createdTable = "";
                Set<String> keys = new LinkedHashSet<>();
                for (String raw : statements) {
                    String sql = stripLeadingComments(raw);
                    Matcher create = CREATE_TABLE.matcher(sql);
                    if (create.find()) {
                        createdTable = qualifiedKey(create.group(1));
                        if (targetTables.contains(createdTable)) {
                            collectMatches(PRIMARY_KEY, sql, keys);
                            collectMatches(UNIQUE_CONSTRAINT, sql, keys);
                        }
                    }
                    Matcher index = UNIQUE_INDEX.matcher(sql);
                    if (index.find()) {
                        String table = qualifiedKey(index.group(1));
                        if (targetTables.contains(table)) {
                            String columns = columnKeyFromSql(index.group(2));
                            if (!columns.isBlank()) {
                                if (createdTable.isBlank()) createdTable = table;
                                if (table.equals(createdTable)) keys.add(columns);
                            }
                        }
                    }
                }
                if (!createdTable.isBlank() && targetTables.contains(createdTable)) {
                    result.computeIfAbsent(createdTable, ignored -> new ArrayList<>())
                            .add(new GeneratedVersion(normalize(root.relativize(file)), Set.copyOf(keys)));
                }
            }
        }
        Map<String, List<GeneratedVersion>> frozen = new LinkedHashMap<>();
        result.forEach((table, versions) -> frozen.put(table, List.copyOf(versions)));
        return Map.copyOf(frozen);
    }

    private static void collectMatches(Pattern pattern, String sql, Set<String> keys) {
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            String columns = columnKeyFromSql(matcher.group(1));
            if (!columns.isBlank()) keys.add(columns);
        }
    }

    private Map<String, KeyEvidence> loadCanonicalKeyInventory(Path root, Set<String> targetTables)
            throws IOException {
        Map<String, MutableKeyEvidence> mutable = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(Db2LuwPostResolutionKeyAuditP82IT::isSnapshot)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList()) {
                CanonicalSchemaSnapshot snapshot;
                try {
                    snapshot = snapshotStore.readSnapshot(file);
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Cannot load canonical snapshot for P8.2 audit: " + file, exception);
                }
                CanonicalSchemaSnapshot.SchemaSnapshot schema = snapshot.schema();
                if (schema == null || schema.tables() == null) continue;
                for (CanonicalSchemaSnapshot.TableSnapshot table : schema.tables()) {
                    if (table == null || table.name() == null || table.name().isBlank()) continue;
                    String qualified = table.schema() == null || table.schema().isBlank()
                            ? table.name() : table.schema() + "." + table.name();
                    String tableKey = qualifiedKey(qualified);
                    if (!targetTables.contains(tableKey)) continue;
                    String source = snapshot.source() != null && snapshot.source().relativePath() != null
                            ? snapshot.source().relativePath() : normalize(root.relativize(file));
                    MutableKeyEvidence out = mutable.computeIfAbsent(tableKey, ignored -> new MutableKeyEvidence());
                    if (table.primaryKey() != null && table.primaryKey().columns() != null) {
                        out.add("PK", columnKey(table.primaryKey().columns()), source);
                    }
                    if (table.uniqueKeys() != null) {
                        for (CanonicalSchemaSnapshot.UniqueKeySnapshot uk : table.uniqueKeys()) {
                            if (uk != null && uk.columns() != null) out.add("UK", columnKey(uk.columns()), source);
                        }
                    }
                    if (table.indexes() != null) {
                        for (CanonicalSchemaSnapshot.IndexSnapshot index : table.indexes()) {
                            if (index == null || !isUniqueIndex(index) || index.columns() == null) continue;
                            List<String> columns = index.columns().stream()
                                    .filter(java.util.Objects::nonNull)
                                    .map(CanonicalSchemaSnapshot.IndexColumnSnapshot::column)
                                    .filter(value -> value != null && !value.isBlank())
                                    .toList();
                            if (!columns.isEmpty()) out.add("UI", columnKey(columns), source);
                        }
                    }
                }
            }
        }
        Map<String, KeyEvidence> result = new LinkedHashMap<>();
        mutable.forEach((key, value) -> result.put(key, value.freeze()));
        return Map.copyOf(result);
    }

    private static boolean isUniqueIndex(CanonicalSchemaSnapshot.IndexSnapshot index) {
        String type = index.type() == null ? "" : index.type().trim().toUpperCase(Locale.ROOT);
        if (type.contains("UNIQUE")) return true;
        Map<String, String> options = index.physicalOptions();
        if (options == null) return false;
        return options.entrySet().stream().anyMatch(entry -> {
            String key = entry.getKey() == null ? "" : entry.getKey().toUpperCase(Locale.ROOT);
            String value = entry.getValue() == null ? "" : entry.getValue().toUpperCase(Locale.ROOT);
            return (key.contains("UNIQUE") && Set.of("TRUE", "YES", "Y", "1", "UNIQUE").contains(value))
                    || value.equals("UNIQUE");
        });
    }

    private static String classifyCanonical(KeyEvidence evidence, String columns) {
        if (evidence.primaryKeys().contains(columns)) return "CANONICAL_PRIMARY_KEY_EVIDENCE";
        if (evidence.uniqueKeys().contains(columns)) return "CANONICAL_UNIQUE_KEY_EVIDENCE";
        if (evidence.uniqueIndexes().contains(columns)) return "CANONICAL_UNIQUE_INDEX_EVIDENCE";
        return "";
    }

    private static Map<KeyRef, List<String>> loadLegacyUkEvidence(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return Map.of();
        Map<KeyRef, List<String>> result = new LinkedHashMap<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = in.readLine();
            if (first == null) return Map.of();
            List<String> headers = parseCsvLine(first);
            Map<String, Integer> index = index(headers);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                String table = qualifiedKey(get(values, index, "table"));
                String columns = columnKey(get(values, index, "columns"));
                String source = get(values, index, "source");
                if (table.isBlank() || columns.isBlank()) continue;
                KeyRef ref = new KeyRef(table, columns);
                List<String> sources = new ArrayList<>(result.getOrDefault(ref, List.of()));
                if (!source.isBlank() && !sources.contains(source)) sources.add(source);
                result.put(ref, List.copyOf(sources));
            }
        }
        return Map.copyOf(result);
    }

    private static List<BlockerRow> readBlockersResource(String resource) throws IOException {
        List<Map<String, String>> rows = readResourceCsv(resource);
        return rows.stream().map(row -> new BlockerRow(
                row.get("source_table"), row.get("constraint_name"), row.get("action"),
                row.get("resolved_referenced_table"), row.get("resolved_referenced_columns"),
                row.get("reason"), row.get("evidence"))).toList();
    }

    private static List<P75Row> readP75Resource(String resource) throws IOException {
        List<Map<String, String>> rows = readResourceCsv(resource);
        return rows.stream().map(row -> new P75Row(
                row.get("source_table"), row.get("constraint_name"), row.get("referenced_table"),
                row.get("referenced_columns"), row.get("p7_classification"), row.get("evidence"))).toList();
    }

    private static List<Map<String, String>> readResourceCsv(String resource) throws IOException {
        InputStream input = Db2LuwPostResolutionKeyAuditP82IT.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(input, "retained evidence resource not found: " + resource);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String first = in.readLine();
            assertNotNull(first, "retained evidence resource is empty: " + resource);
            List<String> headers = parseCsvLine(first);
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < values.size() ? values.get(i) : "");
                }
                rows.add(Map.copyOf(row));
            }
            return List.copyOf(rows);
        }
    }

    private static void writeCsv(Path file, List<AuditRow> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("referenced_table,key_columns,p8_1_blocker_rows,p7_5_deferred_rows,generated_versions,generated_versions_with_exact_key,selected_final_file,selected_final_has_key,generated_key_version_files,canonical_key_class,canonical_sources,legacy_uk_sources,p8_2_classification\n");
            for (AuditRow row : rows) {
                out.write(String.join(",",
                        csv(row.target().table()), csv(row.target().columns()),
                        Integer.toString(row.blockerRows()), Integer.toString(row.p75Rows()),
                        Integer.toString(row.generatedVersions()), Integer.toString(row.generatedKeyVersions()),
                        csv(row.selectedFinalFile()), Boolean.toString(row.selectedFinalHasKey()),
                        csv(String.join("|", row.generatedKeyVersionFiles())), csv(row.canonicalClass()),
                        csv(String.join("|", row.canonicalSources())), csv(String.join("|", row.legacySources())),
                        csv(row.classification())));
                out.newLine();
            }
        }
    }

    private static String summary(
            Config config, List<BlockerRow> blockers, List<P75Row> p75, List<AuditRow> rows, Path reportDir) {
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.stream().map(AuditRow::classification).sorted()
                .forEach(value -> counts.merge(value, 1L, Long::sum));
        int affected = rows.stream().mapToInt(row -> row.blockerRows() + row.p75Rows()).sum();
        StringBuilder out = new StringBuilder();
        out.append("DB2 LUW P8.2 Post-Resolution Parent-Key Audit\n")
                .append("=============================================\n")
                .append("Generated SQL root       : ").append(config.sqlRoot()).append('\n')
                .append("Canonical snapshot dir   : ").append(config.snapshotDir()).append('\n')
                .append("Legacy UK probe          : ")
                .append(config.legacyUkProbeFile() == null ? "NOT PROVIDED" : config.legacyUkProbeFile()).append('\n')
                .append("P8.1 live blocker rows   : ").append(blockers.size()).append('\n')
                .append("P7.5 deferred key rows   : ").append(p75.size()).append('\n')
                .append("Distinct parent key refs : ").append(rows.size()).append('\n')
                .append("Affected FK references   : ").append(affected).append("\n\n");
        counts.forEach((key, value) -> out.append(String.format(Locale.ROOT, "  %-52s %d%n", key, value)));
        out.append("\nMutation policy          : EVIDENCE ONLY; NO TABLE/KEY/FK MUTATION\n")
                .append("Exact-key policy         : ORDERED COLUMN SET MUST MATCH PK/UK/UNIQUE INDEX\n")
                .append("Report directory         : ").append(reportDir).append('\n');
        return out.toString();
    }

    private static boolean isSnapshot(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schema.json");
    }

    private static String qualifiedKey(String value) {
        if (value == null) return "";
        return value.replace("\"", "").replaceAll("\\s*\\.\\s*", ".")
                .trim().toUpperCase(Locale.ROOT);
    }

    private static String columnKey(String value) {
        if (value == null || value.isBlank()) return "";
        return columnKey(List.of(value.split("\\|")));
    }

    private static String columnKey(List<String> columns) {
        return columns.stream().map(String::trim).filter(value -> !value.isBlank())
                .map(value -> value.replace("\"", "").toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static List<String> parseIdentifierList(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    token.append("\"\"");
                    i++;
                } else {
                    quoted = !quoted;
                    token.append(ch);
                }
            } else if (ch == ',' && !quoted) {
                addIdentifierToken(result, token);
            } else {
                token.append(ch);
            }
        }
        addIdentifierToken(result, token);
        return List.copyOf(result);
    }

    private static void addIdentifierToken(List<String> result, StringBuilder token) {
        String value = token.toString().trim();
        token.setLength(0);
        if (!value.isEmpty()) result.add(value);
    }

    private static String columnKeyFromSql(String csv) {
        return columnKey(parseIdentifierList(csv));
    }

    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql;
        boolean changed;
        do {
            changed = false;
            String trimmed = value.stripLeading();
            if (trimmed.startsWith("--")) {
                int newline = trimmed.indexOf('\n');
                value = newline < 0 ? "" : trimmed.substring(newline + 1);
                changed = true;
            } else if (trimmed.startsWith("/*")) {
                int end = trimmed.indexOf("*/", 2);
                value = end < 0 ? "" : trimmed.substring(end + 2);
                changed = true;
            }
        } while (changed);
        return value.stripLeading();
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static Map<String, Integer> index(List<String> headers) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) result.put(headers.get(i), i);
        return result;
    }

    private static String get(List<String> values, Map<String, Integer> index, String field) {
        Integer offset = index.get(field);
        return offset == null || offset >= values.size() ? "" : values.get(offset);
    }

    private record KeyRef(String table, String columns) { }
    private record GeneratedVersion(String file, Set<String> keys) { }
    private record BlockerRow(
            String sourceTable, String constraintName, String action,
            String resolvedReferencedTable, String resolvedReferencedColumns,
            String reason, String evidence) { }
    private record P75Row(
            String sourceTable, String constraintName, String referencedTable, String referencedColumns,
            String classification, String evidence) { }
    private record AuditRow(
            KeyRef target, int blockerRows, int p75Rows, int generatedVersions, int generatedKeyVersions,
            String selectedFinalFile, boolean selectedFinalHasKey, List<String> generatedKeyVersionFiles,
            String canonicalClass, List<String> canonicalSources, List<String> legacySources,
            String classification) { }

    private record KeyEvidence(
            Set<String> primaryKeys, Set<String> uniqueKeys, Set<String> uniqueIndexes,
            Map<String, List<String>> sources) {
        static KeyEvidence empty() {
            return new KeyEvidence(Set.of(), Set.of(), Set.of(), Map.of());
        }
        List<String> sourcesFor(String columns) {
            List<String> result = new ArrayList<>();
            for (String prefix : List.of("PK:", "UK:", "UI:")) {
                for (String source : sources.getOrDefault(prefix + columns, List.of())) {
                    if (!result.contains(source)) result.add(source);
                }
            }
            return List.copyOf(result);
        }
    }

    private static final class MutableKeyEvidence {
        private final Set<String> primaryKeys = new LinkedHashSet<>();
        private final Set<String> uniqueKeys = new LinkedHashSet<>();
        private final Set<String> uniqueIndexes = new LinkedHashSet<>();
        private final Map<String, List<String>> sources = new LinkedHashMap<>();

        void add(String kind, String columns, String source) {
            if (columns == null || columns.isBlank()) return;
            switch (kind) {
                case "PK" -> primaryKeys.add(columns);
                case "UK" -> uniqueKeys.add(columns);
                case "UI" -> uniqueIndexes.add(columns);
                default -> throw new IllegalArgumentException("Unknown key kind: " + kind);
            }
            String key = kind + ":" + columns;
            List<String> values = new ArrayList<>(sources.getOrDefault(key, List.of()));
            if (source != null && !source.isBlank() && !values.contains(source)) values.add(source);
            sources.put(key, List.copyOf(values));
        }

        KeyEvidence freeze() {
            return new KeyEvidence(Set.copyOf(primaryKeys), Set.copyOf(uniqueKeys), Set.copyOf(uniqueIndexes),
                    Map.copyOf(sources));
        }
    }

    private record Config(Path sqlRoot, Path snapshotDir, Path legacyUkProbeFile, Path reportBase) {
        static Config load() {
            return new Config(
                    path(System.getProperty("schemaforge.db2luw.p8.sqlRoot")),
                    path(System.getProperty("schemaforge.db2luw.p7.snapshotDir")),
                    path(System.getProperty("schemaforge.db2luw.p7.legacyUkProbeFile")),
                    path(System.getProperty("schemaforge.db2luw.p8.2.reportDir",
                            "target/db2luw-p8.2-post-resolution-key-audit")));
        }
        boolean enabled() { return sqlRoot != null && snapshotDir != null; }
        void validate() {
            if (!Files.isDirectory(sqlRoot)) throw new IllegalArgumentException("P8.2 SQL root not found: " + sqlRoot);
            if (!Files.isDirectory(snapshotDir)) throw new IllegalArgumentException("P8.2 snapshot dir not found: " + snapshotDir);
        }
        private static Path path(String value) {
            return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
        }
    }
}
