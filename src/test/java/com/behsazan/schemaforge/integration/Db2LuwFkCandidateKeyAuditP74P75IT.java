package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence-only DB2 LUW P7.4/P7.5 candidate-key and key-version audit.
 *
 * <p>P7.4 never invents UNIQUE merely because a foreign key needs it. A row is considered
 * independently supported only when the referenced column set is present as a canonical PK,
 * canonical UK, canonical unique index, or an independently recovered Legacy Word UK.</p>
 *
 * <p>P7.5 separates historical generated-key evidence from canonical current/recovered evidence.
 * No canonical snapshot, generated SQL, parser output, or live database object is mutated.</p>
 */
class Db2LuwFkCandidateKeyAuditP74P75IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final Path RETAINED_P6_BASELINE = Path.of(
            "src", "test", "resources", "evidence", "db2luw-p6", "20260829_182000_267",
            "db2luw-fk-structural-audit.csv");

    private final CanonicalSnapshotJsonStore snapshotStore = new CanonicalSnapshotJsonStore();

    @Test
    void auditsP74CandidateKeysAndP75VersionDrift() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set schemaforge.db2luw.p7.snapshotDir to run P7.4/P7.5 audit. "
                        + "Optionally set schemaforge.db2luw.p7.legacyUkProbeFile.");

        Path p6Audit = resolveP6AuditFile(config.p6AuditFile(),
                Path.of("target", "db2luw-fk-structural-audit"), RETAINED_P6_BASELINE);
        assertTrue(Files.isRegularFile(p6Audit), "P6 audit CSV not found: " + p6Audit);

        List<P6Row> all = readP6Csv(p6Audit);
        List<P6Row> p74Rows = all.stream()
                .filter(row -> "KEY_MODEL_OR_EXTRACTION_GAP".equals(row.p6Classification()))
                .toList();
        List<P6Row> p75Rows = all.stream()
                .filter(row -> "KEY_VERSION_DRIFT".equals(row.p6Classification()))
                .toList();

        Set<String> targetTables = new LinkedHashSet<>();
        p74Rows.forEach(row -> targetTables.add(qualifiedKey(row.referencedTable())));
        p75Rows.forEach(row -> targetTables.add(qualifiedKey(row.referencedTable())));

        Map<String, KeyEvidence> canonical = loadCanonicalKeyInventory(config.snapshotDir(), targetTables);
        Map<KeyRef, List<String>> legacyUk = loadLegacyUkEvidence(config.legacyUkProbeFile());

        List<Resolution> p74 = p74Rows.stream()
                .map(row -> resolveP74(row, canonical, legacyUk))
                .sorted(rowComparator())
                .toList();
        List<Resolution> p75 = p75Rows.stream()
                .map(row -> resolveP75(row, canonical))
                .sorted(rowComparator())
                .toList();

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        writeCsv(reportDir.resolve("db2luw-fk-p7.4-candidate-key-audit.csv"), p74);
        writeCsv(reportDir.resolve("db2luw-fk-p7.5-key-version-audit.csv"), p75);
        String summary = summary(config, p6Audit, canonical, legacyUk, p74, p75, reportDir);
        Files.writeString(reportDir.resolve("db2luw-fk-p7.4-p7.5-summary.txt"), summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        if (config.expectedP74Rows() >= 0) assertEquals(config.expectedP74Rows(), p74.size(), "Unexpected P7.4 row count");
        if (config.expectedP75Rows() >= 0) assertEquals(config.expectedP75Rows(), p75.size(), "Unexpected P7.5 row count");
        assertEquals(p74Rows.size(), p74.size(), "Every P7.4 row must be classified");
        assertEquals(p75Rows.size(), p75.size(), "Every P7.5 row must be classified");
    }

    @Test
    void retainedP6BaselineIsAvailableAndComplete() throws Exception {
        assertTrue(Files.isRegularFile(RETAINED_P6_BASELINE),
                "Retained P6 baseline missing: " + RETAINED_P6_BASELINE);
        List<P6Row> rows = readP6Csv(RETAINED_P6_BASELINE);
        assertEquals(313, rows.size(), "Retained P6 baseline row count drifted");
        assertEquals(50, rows.stream().filter(row -> "KEY_MODEL_OR_EXTRACTION_GAP".equals(row.p6Classification())).count());
        assertEquals(16, rows.stream().filter(row -> "KEY_VERSION_DRIFT".equals(row.p6Classification())).count());
    }

    @Test
    void exactOrderedColumnSetsAreRequiredForIndependentKeyEvidence() {
        KeyEvidence evidence = new KeyEvidence(
                Set.of("A|B"), Set.of("C"), Set.of("D|E"),
                Map.of("PK:A|B", List.of("one"), "UK:C", List.of("two"), "UI:D|E", List.of("three")));
        assertEquals("CANONICAL_PRIMARY_KEY_EVIDENCE", classifyCanonical(evidence, "A|B"));
        assertEquals("CANONICAL_UNIQUE_KEY_EVIDENCE", classifyCanonical(evidence, "C"));
        assertEquals("CANONICAL_UNIQUE_INDEX_EVIDENCE", classifyCanonical(evidence, "D|E"));
        assertEquals("", classifyCanonical(evidence, "B|A"));
    }

    private static Resolution resolveP74(
            P6Row row,
            Map<String, KeyEvidence> canonical,
            Map<KeyRef, List<String>> legacyUk) {
        String table = qualifiedKey(row.referencedTable());
        String columns = columnKey(row.referencedColumns());
        KeyEvidence evidence = canonical.getOrDefault(table, KeyEvidence.empty());
        String canonicalClass = classifyCanonical(evidence, columns);
        List<String> legacySources = legacyUk.getOrDefault(new KeyRef(table, columns), List.of());

        String classification;
        if (!canonicalClass.isBlank()) classification = canonicalClass;
        else if (!legacySources.isEmpty()) classification = "LEGACY_WORD_UNIQUE_KEY_EVIDENCE";
        else classification = "NO_INDEPENDENT_UNIQUE_EVIDENCE";

        String detail = String.join("; ", List.of(
                "canonical_pk_match=" + evidence.primaryKeys().contains(columns),
                "canonical_uk_match=" + evidence.uniqueKeys().contains(columns),
                "canonical_unique_index_match=" + evidence.uniqueIndexes().contains(columns),
                "legacy_word_uk_match=" + !legacySources.isEmpty(),
                "canonical_sources=" + String.join("|", evidence.sourcesFor(columns)),
                "legacy_sources=" + String.join("|", legacySources)));
        return new Resolution(row, classification, columns, detail);
    }

    private static Resolution resolveP75(P6Row row, Map<String, KeyEvidence> canonical) {
        String table = qualifiedKey(row.referencedTable());
        String columns = columnKey(row.referencedColumns());
        KeyEvidence evidence = canonical.getOrDefault(table, KeyEvidence.empty());
        String current = classifyCanonical(evidence, columns);
        String classification = current.isBlank()
                ? "HISTORICAL_GENERATED_KEY_ONLY"
                : "CANONICAL_KEY_EVIDENCE_PRESENT";
        String detail = String.join("; ", List.of(
                "historical_generated_evidence=" + row.p6Evidence(),
                "canonical_key_class=" + current,
                "canonical_sources=" + String.join("|", evidence.sourcesFor(columns))));
        return new Resolution(row, classification, columns, detail);
    }

    private static String classifyCanonical(KeyEvidence evidence, String columns) {
        if (evidence.primaryKeys().contains(columns)) return "CANONICAL_PRIMARY_KEY_EVIDENCE";
        if (evidence.uniqueKeys().contains(columns)) return "CANONICAL_UNIQUE_KEY_EVIDENCE";
        if (evidence.uniqueIndexes().contains(columns)) return "CANONICAL_UNIQUE_INDEX_EVIDENCE";
        return "";
    }

    private Map<String, KeyEvidence> loadCanonicalKeyInventory(Path root, Set<String> targetTables) throws IOException {
        Map<String, MutableKeyEvidence> mutable = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(Db2LuwFkCandidateKeyAuditP74P75IT::isSnapshot)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList()) {
                CanonicalSchemaSnapshot snapshot;
                try {
                    snapshot = snapshotStore.readSnapshot(file);
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Cannot load canonical snapshot for P7.4/P7.5 audit: " + file, exception);
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

    private static Map<KeyRef, List<String>> loadLegacyUkEvidence(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return Map.of();
        Map<KeyRef, List<String>> result = new LinkedHashMap<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = in.readLine();
            if (first == null) return Map.of();
            List<String> headers = parseCsvLine(first);
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) index.put(headers.get(i), i);
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

    private static Comparator<Resolution> rowComparator() {
        return Comparator.comparing((Resolution row) -> row.input().referencedTable())
                .thenComparing(row -> row.input().referencedColumns())
                .thenComparing(row -> row.input().sourceTable())
                .thenComparing(row -> row.input().constraintName());
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
                        get(values, index, "validation_reason"), get(values, index, "p6_classification"),
                        get(values, index, "evidence")));
            }
        }
        return List.copyOf(rows);
    }

    private static void writeCsv(Path file, List<Resolution> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("source_file,source_table,constraint_name,source_columns,referenced_table,referenced_columns,p7_classification,key_columns,evidence\n");
            for (Resolution row : rows) {
                P6Row in = row.input();
                out.write(String.join(",",
                        csv(in.sourceFile()), csv(in.sourceTable()), csv(in.constraintName()), csv(in.sourceColumns()),
                        csv(in.referencedTable()), csv(in.referencedColumns()), csv(row.classification()),
                        csv(row.keyColumns()), csv(row.evidence())));
                out.newLine();
            }
        }
    }

    private static String summary(
            Config config,
            Path p6Audit,
            Map<String, KeyEvidence> canonical,
            Map<KeyRef, List<String>> legacyUk,
            List<Resolution> p74,
            List<Resolution> p75,
            Path reportDir) {
        Map<String, Long> c74 = counts(p74);
        Map<String, Long> c75 = counts(p75);
        StringBuilder out = new StringBuilder();
        out.append("DB2 LUW FK Candidate Key Audit P7.4/P7.5\n")
                .append("========================================\n")
                .append("P6 audit file          : ").append(p6Audit).append('\n')
                .append("Canonical snapshot dir : ").append(config.snapshotDir()).append('\n')
                .append("Legacy UK probe        : ").append(config.legacyUkProbeFile() == null ? "NOT PROVIDED" : config.legacyUkProbeFile()).append('\n')
                .append("Canonical target tables: ").append(canonical.size()).append('\n')
                .append("Legacy UK definitions  : ").append(legacyUk.size()).append('\n')
                .append('\n')
                .append("P7.4 candidate-key rows: ").append(p74.size()).append('\n');
        c74.forEach((key, value) -> out.append(String.format(Locale.ROOT, "  %-38s %d%n", key, value)));
        out.append('\n').append("P7.5 key-version rows  : ").append(p75.size()).append('\n');
        c75.forEach((key, value) -> out.append(String.format(Locale.ROOT, "  %-38s %d%n", key, value)));
        out.append('\n')
                .append("Mutation policy         : EVIDENCE ONLY; NEVER INVENT UNIQUE FOR FK\n")
                .append("Key matching policy     : EXACT ORDERED COLUMN SET ONLY\n")
                .append("Report directory        : ").append(reportDir).append('\n');
        return out.toString();
    }

    private static Map<String, Long> counts(List<Resolution> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        rows.stream().map(Resolution::classification).sorted().forEach(value -> result.merge(value, 1L, Long::sum));
        return result;
    }

    private static Path resolveP6AuditFile(Path configured, Path root, Path retainedBaseline) throws IOException {
        if (configured != null && Files.isRegularFile(configured)) return configured;
        if (Files.isDirectory(root)) {
            try (Stream<Path> paths = Files.walk(root, 2)) {
                Path latest = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals("db2luw-fk-structural-audit.csv"))
                        .max(Comparator.comparing(path -> path.getParent().getFileName().toString()))
                        .orElse(null);
                if (latest != null) return latest;
            }
        }
        if (retainedBaseline != null && Files.isRegularFile(retainedBaseline)) {
            return retainedBaseline.toAbsolutePath().normalize();
        }
        return root.resolve("db2luw-fk-structural-audit.csv");
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static String qualifiedKey(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s*\\.\\s*", ".").replace("\"", "").trim().toUpperCase(Locale.ROOT);
    }

    private static String columnKey(String value) {
        if (value == null || value.isBlank()) return "";
        String[] parts = value.split("\\|");
        List<String> columns = new ArrayList<>();
        for (String part : parts) if (!part.isBlank()) columns.add(id(part));
        return String.join("|", columns);
    }

    private static String columnKey(List<String> values) {
        if (values == null) return "";
        return String.join("|", values.stream().filter(java.util.Objects::nonNull).map(Db2LuwFkCandidateKeyAuditP74P75IT::id).toList());
    }

    private static String id(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1).replace("\"\"", "\"");
        }
        return normalized.toUpperCase(Locale.ROOT);
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

    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String csv(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private record Config(
            Path snapshotDir,
            Path p6AuditFile,
            Path legacyUkProbeFile,
            Path reportBase,
            int expectedP74Rows,
            int expectedP75Rows) {
        static Config load() {
            return new Config(
                    path(System.getProperty("schemaforge.db2luw.p7.snapshotDir")),
                    path(System.getProperty("schemaforge.db2luw.p7.p6AuditFile")),
                    path(System.getProperty("schemaforge.db2luw.p7.legacyUkProbeFile")),
                    pathOrDefault(System.getProperty("schemaforge.db2luw.p7.p74p75ReportBase"),
                            Path.of("target", "db2luw-fk-p7.4-p7.5-key-audit")),
                    integer(System.getProperty("schemaforge.db2luw.p7.expectedCandidateKeyRows"), 50),
                    integer(System.getProperty("schemaforge.db2luw.p7.expectedKeyVersionRows"), 16));
        }
        boolean enabled() { return snapshotDir != null && Files.isDirectory(snapshotDir); }
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
            String p6Classification,
            String p6Evidence) {}

    private record Resolution(P6Row input, String classification, String keyColumns, String evidence) {}
    private record KeyRef(String table, String columns) {}

    private record KeyEvidence(
            Set<String> primaryKeys,
            Set<String> uniqueKeys,
            Set<String> uniqueIndexes,
            Map<String, List<String>> sources) {
        static KeyEvidence empty() { return new KeyEvidence(Set.of(), Set.of(), Set.of(), Map.of()); }
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

        void add(String type, String columns, String source) {
            if (columns == null || columns.isBlank()) return;
            switch (type) {
                case "PK" -> primaryKeys.add(columns);
                case "UK" -> uniqueKeys.add(columns);
                case "UI" -> uniqueIndexes.add(columns);
                default -> throw new IllegalArgumentException("Unsupported key type: " + type);
            }
            String key = type + ":" + columns;
            List<String> current = new ArrayList<>(sources.getOrDefault(key, List.of()));
            if (source != null && !source.isBlank() && !current.contains(source)) current.add(source);
            sources.put(key, List.copyOf(current));
        }

        KeyEvidence freeze() {
            return new KeyEvidence(Set.copyOf(primaryKeys), Set.copyOf(uniqueKeys), Set.copyOf(uniqueIndexes), Map.copyOf(sources));
        }
    }
}
