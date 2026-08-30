package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DB2 LUW P9.1.1 diagnostic for table/column-shape mismatches reported by P9.1.
 *
 * <p>This gate is read-only and non-mutating. It compares the selected final CREATE TABLE shape
 * with the live catalog, then checks whether catalog-only columns are corroborated by an older
 * generated version of the same table. The goal is to distinguish a stale/historical replay state
 * from an actual selected-final corpus defect before any destructive rebuild or generator change.</p>
 */
class Db2LuwCatalogShapeDiagnosisP911IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Set<String> NON_COLUMN_PREFIXES = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void classifiesP91ColumnShapeMismatches() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.db2luw.p9.sqlRoot=<generated DB2 LUW root> to run P9.1.1.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) fail("No DB2 LUW SQL files found below " + config.root());

        Map<String, List<TableVersion>> history = loadTableHistory(files, config.expectedSchema());
        Map<String, TableVersion> selectedFinal = new LinkedHashMap<>();
        for (Map.Entry<String, List<TableVersion>> entry : history.entrySet()) {
            List<TableVersion> versions = entry.getValue();
            selectedFinal.put(entry.getKey(), versions.get(versions.size() - 1));
        }
        if (config.strictBaseline()) assertEquals(2310, selectedFinal.size(), "P9.1.1 final table baseline changed");

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Instant started = Instant.now();
        Report report = new Report(config, reportDir, files.size(), selectedFinal.size());

        DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
        Class.forName(config.driver());
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setReadOnly(true);
            verifyExpectedDatabase(connection, config.expectedDatabase());
            report.databaseProduct = connection.getMetaData().getDatabaseProductName();
            report.databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            report.database = scalar(connection, "VALUES CURRENT SERVER");
            report.authorizationId = scalar(connection, "VALUES CURRENT USER");

            Map<String, List<String>> catalogColumns = loadCatalogColumns(connection, config.expectedSchema());
            for (Map.Entry<String, TableVersion> entry : selectedFinal.entrySet()) {
                String table = entry.getKey();
                TableVersion finalVersion = entry.getValue();
                List<String> actual = catalogColumns.get(table);
                if (actual == null) {
                    report.missingTables++;
                    report.rows.add(new DiagnosisRow(table, "MISSING_TABLE", finalVersion.file(),
                            finalVersion.columns().size(), 0, "", "", false, false, history.get(table).size()));
                    continue;
                }
                if (finalVersion.columns().equals(actual)) {
                    report.exactTables++;
                    continue;
                }

                report.mismatchTables++;
                Set<String> expectedSet = new LinkedHashSet<>(finalVersion.columns());
                Set<String> actualSet = new LinkedHashSet<>(actual);
                List<String> missing = finalVersion.columns().stream().filter(c -> !actualSet.contains(c)).toList();
                List<String> extra = actual.stream().filter(c -> !expectedSet.contains(c)).toList();
                boolean commonOrderPreserved = commonOrderPreserved(finalVersion.columns(), actual);

                Set<String> historicalColumns = new LinkedHashSet<>();
                for (TableVersion version : history.get(table)) historicalColumns.addAll(version.columns());
                List<String> unexplainedExtra = extra.stream().filter(c -> !historicalColumns.contains(c)).toList();
                boolean allExtraHistorical = !extra.isEmpty() && unexplainedExtra.isEmpty();

                String classification = classify(missing, extra, commonOrderPreserved, allExtraHistorical);
                report.classificationCounts.merge(classification, 1, Integer::sum);
                report.expectedColumns += finalVersion.columns().size();
                report.catalogColumns += actual.size();
                report.extraColumns += extra.size();
                report.missingColumns += missing.size();
                report.unexplainedExtraColumns += unexplainedExtra.size();

                report.rows.add(new DiagnosisRow(table, classification, finalVersion.file(),
                        finalVersion.columns().size(), actual.size(), String.join("|", missing),
                        String.join("|", extra), commonOrderPreserved, allExtraHistorical, history.get(table).size()));

                for (String column : extra) {
                    HistoricalEvidence evidence = findHistoricalEvidence(history.get(table), column, finalVersion.file());
                    report.extraRows.add(new ExtraColumnRow(table, column, historicalColumns.contains(column),
                            evidence.occurrences(), evidence.lastFileContaining(), finalVersion.file()));
                }
            }
        } finally {
            report.elapsed = Duration.between(started, Instant.now());
            report.write();
            report.printSummary();
        }

        if (report.missingTables > 0) {
            fail("DB2 LUW P9.1.1 found missing selected-final tables. Report: " + reportDir);
        }
    }

    private Map<String, List<TableVersion>> loadTableHistory(List<Path> files, String expectedSchema) throws IOException {
        Map<String, List<TableVersion>> result = new LinkedHashMap<>();
        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.DB2_LUW)) {
                String sql = stripLeadingComments(raw);
                Matcher matcher = CREATE_TABLE.matcher(sql);
                if (!matcher.find()) continue;
                ObjectName name = ObjectName.parse(normalizeName(matcher.group(1)), expectedSchema);
                if (!name.owner().equalsIgnoreCase(expectedSchema)) continue;
                List<String> columns = extractColumns(sql, matcher.end());
                result.computeIfAbsent(name.name().toUpperCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(new TableVersion(name.name().toUpperCase(Locale.ROOT), columns, normalize(file)));
            }
        }
        result.replaceAll((key, value) -> List.copyOf(value));
        return result;
    }

    private static String classify(List<String> missing, List<String> extra,
                                   boolean commonOrderPreserved, boolean allExtraHistorical) {
        if (missing.isEmpty() && !extra.isEmpty() && commonOrderPreserved) {
            return allExtraHistorical
                    ? "HISTORICAL_REPLAY_EXTRA_COLUMNS_ONLY"
                    : "EXTRA_CATALOG_COLUMNS_UNEXPLAINED";
        }
        if (!missing.isEmpty() && extra.isEmpty()) return "MISSING_FINAL_COLUMNS";
        if (missing.isEmpty() && extra.isEmpty() && !commonOrderPreserved) return "COLUMN_ORDER_DRIFT";
        if (!missing.isEmpty() && !extra.isEmpty()) return "MIXED_COLUMN_SET_DRIFT";
        if (!commonOrderPreserved) return "COMMON_COLUMN_ORDER_DRIFT_WITH_EXTRAS";
        return "UNCLASSIFIED_COLUMN_SHAPE_DRIFT";
    }

    private static boolean commonOrderPreserved(List<String> expected, List<String> actual) {
        Set<String> expectedSet = new LinkedHashSet<>(expected);
        Set<String> actualSet = new LinkedHashSet<>(actual);
        List<String> expectedCommon = expected.stream().filter(actualSet::contains).toList();
        List<String> actualCommon = actual.stream().filter(expectedSet::contains).toList();
        return expectedCommon.equals(actualCommon);
    }

    private static HistoricalEvidence findHistoricalEvidence(
            List<TableVersion> versions, String column, String selectedFinalFile) {
        int occurrences = 0;
        String last = "";
        for (TableVersion version : versions) {
            if (version.file().equals(selectedFinalFile)) continue;
            if (version.columns().contains(column)) {
                occurrences++;
                last = version.file();
            }
        }
        return new HistoricalEvidence(occurrences, last);
    }

    private static List<String> extractColumns(String createSql, int searchFrom) {
        int open = createSql.indexOf('(', searchFrom);
        if (open < 0) throw new IllegalArgumentException("CREATE TABLE has no body: " + oneLine(createSql));
        int close = matchingParen(createSql, open);
        if (close < 0) throw new IllegalArgumentException("CREATE TABLE has unclosed body: " + oneLine(createSql));
        String body = createSql.substring(open + 1, close);
        List<String> columns = new ArrayList<>();
        for (String element : splitTopLevel(body)) {
            String trimmed = element.trim();
            if (trimmed.isEmpty()) continue;
            String first = firstIdentifier(trimmed);
            if (first == null) continue;
            if (NON_COLUMN_PREFIXES.contains(unquote(first).toUpperCase(Locale.ROOT))) continue;
            columns.add(unquote(first).toUpperCase(Locale.ROOT));
        }
        return List.copyOf(columns);
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean single = false;
        boolean quotedIdentifier = false;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (single) {
                if (ch == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                else if (ch == '"') quotedIdentifier = false;
                continue;
            }
            if (ch == '\'') single = true;
            else if (ch == '"') quotedIdentifier = true;
            else if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean single = false;
        boolean quotedIdentifier = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (single) {
                current.append(ch);
                if (ch == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') current.append(body.charAt(++i));
                else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                current.append(ch);
                if (ch == '"' && i + 1 < body.length() && body.charAt(i + 1) == '"') current.append(body.charAt(++i));
                else if (ch == '"') quotedIdentifier = false;
                continue;
            }
            if (ch == '\'') { single = true; current.append(ch); }
            else if (ch == '"') { quotedIdentifier = true; current.append(ch); }
            else if (ch == '(') { depth++; current.append(ch); }
            else if (ch == ')') { depth--; current.append(ch); }
            else if (ch == ',' && depth == 0) { result.add(current.toString()); current.setLength(0); }
            else current.append(ch);
        }
        result.add(current.toString());
        return result;
    }

    private static String firstIdentifier(String value) {
        String trimmed = value.stripLeading();
        if (trimmed.isEmpty()) return null;
        if (trimmed.charAt(0) == '"') {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 1; i < trimmed.length(); i++) {
                char ch = trimmed.charAt(i);
                out.append(ch);
                if (ch == '"') {
                    if (i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '"') out.append(trimmed.charAt(++i));
                    else return out.toString();
                }
            }
            return null;
        }
        int i = 0;
        while (i < trimmed.length()) {
            char ch = trimmed.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '#' || ch == '@')) break;
            i++;
        }
        return i == 0 ? null : trimmed.substring(0, i);
    }

    private static Map<String, List<String>> loadCatalogColumns(Connection connection, String schema) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (var ps = connection.prepareStatement(
                "SELECT TABNAME, COLNAME FROM SYSCAT.COLUMNS WHERE TABSCHEMA = ? ORDER BY TABNAME, COLNO WITH UR")) {
            ps.setString(1, schema.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.computeIfAbsent(rs.getString(1).toUpperCase(Locale.ROOT), ignored -> new ArrayList<>())
                            .add(rs.getString(2).toUpperCase(Locale.ROOT));
                }
            }
        }
        result.replaceAll((key, value) -> List.copyOf(value));
        return result;
    }

    private static List<Path> findSqlFiles(Path root, String suffix, int maxFiles) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            Stream<Path> selected = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))));
            if (maxFiles > 0) selected = selected.limit(maxFiles);
            return selected.toList();
        }
    }

    private static void verifyExpectedDatabase(Connection connection, String expected) throws SQLException {
        if (expected == null || expected.isBlank()) return;
        String actual = scalar(connection, "VALUES CURRENT SERVER").trim();
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Refusing P9.1.1: expected DB " + expected + " but connected to " + actual);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no rows: " + sql);
            return rs.getString(1);
        }
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

    private static String normalizeName(String value) { return value.replaceAll("\\s*\\.\\s*", ".").trim(); }
    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }
    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
    private static String csv(String value) { return '"' + (value == null ? "" : value).replace("\"", "\"\"") + '"'; }

    private record ObjectName(String owner, String name) {
        static ObjectName parse(String qualified, String defaultSchema) {
            String normalized = normalizeName(qualified).replace("\"", "");
            int dot = normalized.indexOf('.');
            if (dot < 0) return new ObjectName(defaultSchema.toUpperCase(Locale.ROOT), normalized.toUpperCase(Locale.ROOT));
            return new ObjectName(normalized.substring(0, dot).toUpperCase(Locale.ROOT),
                    normalized.substring(dot + 1).toUpperCase(Locale.ROOT));
        }
    }

    private record TableVersion(String table, List<String> columns, String file) { }
    private record HistoricalEvidence(int occurrences, String lastFileContaining) { }
    private record DiagnosisRow(String table, String classification, String selectedFinalFile,
                                int expectedColumns, int catalogColumns, String missingColumns,
                                String extraColumns, boolean commonOrderPreserved,
                                boolean allExtraColumnsHistorical, int generatedVersions) { }
    private record ExtraColumnRow(String table, String column, boolean historicalGeneratedEvidence,
                                  int historicalOccurrences, String lastHistoricalFileContaining,
                                  String selectedFinalFile) { }

    private record Config(Path root, String fileSuffix, int maxFiles, String url, String user, String password,
                          String driver, String expectedDatabase, String expectedSchema, int loginTimeoutSeconds,
                          boolean strictBaseline, Path reportBase) {
        static Config load() {
            String root = System.getProperty("schemaforge.db2luw.p9.sqlRoot", "").trim();
            return new Config(
                    root.isEmpty() ? null : Path.of(root),
                    System.getProperty("schemaforge.db2luw.p9.fileSuffix", ".db2luw.sql"),
                    Integer.getInteger("schemaforge.db2luw.p9.maxFiles", 0),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.url", "jdbc:db2://127.0.0.1:50000/SFORGE"),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.user", "db2inst1"),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.password", "Schemaforge123"),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.driver", "com.ibm.db2.jcc.DB2Driver"),
                    System.getProperty("schemaforge.db2luw.p9.expectedDatabase", "SFORGE"),
                    System.getProperty("schemaforge.db2luw.p9.expectedSchema", "TSTSHMA"),
                    Integer.getInteger("schemaforge.db2luw.p9.loginTimeoutSeconds", 15),
                    Boolean.parseBoolean(System.getProperty("schemaforge.db2luw.p9.strictBaseline", "true")),
                    Path.of(System.getProperty("schemaforge.db2luw.p9.p911.reportBase",
                            "target/db2luw-p9.1.1-catalog-shape-diagnosis")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("P9.1.1 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("P9.1.1 expected schema required");
        }
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int filesDiscovered;
        private final int expectedTables;
        private final List<DiagnosisRow> rows = new ArrayList<>();
        private final List<ExtraColumnRow> extraRows = new ArrayList<>();
        private final Map<String, Integer> classificationCounts = new LinkedHashMap<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String database = "";
        private String authorizationId = "";
        private int exactTables;
        private int mismatchTables;
        private int missingTables;
        private int expectedColumns;
        private int catalogColumns;
        private int extraColumns;
        private int missingColumns;
        private int unexplainedExtraColumns;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, int expectedTables) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.expectedTables = expectedTables;
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.1.1-shape-diagnosis.csv"), StandardCharsets.UTF_8)) {
                out.write("table,classification,selected_final_file,expected_columns,catalog_columns,missing_columns,extra_columns,common_order_preserved,all_extra_columns_historical,generated_versions\n");
                for (DiagnosisRow row : rows) {
                    out.write(csv(row.table()) + "," + csv(row.classification()) + "," + csv(row.selectedFinalFile()) + ","
                            + row.expectedColumns() + "," + row.catalogColumns() + "," + csv(row.missingColumns()) + ","
                            + csv(row.extraColumns()) + "," + row.commonOrderPreserved() + ","
                            + row.allExtraColumnsHistorical() + "," + row.generatedVersions() + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.1.1-extra-column-evidence.csv"), StandardCharsets.UTF_8)) {
                out.write("table,column,historical_generated_evidence,historical_occurrences,last_historical_file_containing,selected_final_file\n");
                for (ExtraColumnRow row : extraRows) {
                    out.write(csv(row.table()) + "," + csv(row.column()) + "," + row.historicalGeneratedEvidence() + ","
                            + row.historicalOccurrences() + "," + csv(row.lastHistoricalFileContaining()) + ","
                            + csv(row.selectedFinalFile()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("db2luw-p9.1.1-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            StringBuilder out = new StringBuilder();
            out.append("DB2 LUW P9.1.1 Catalog Shape Diagnosis\n")
                    .append("======================================\n")
                    .append("Database product             : ").append(databaseProduct).append('\n')
                    .append("Database version             : ").append(databaseVersion).append('\n')
                    .append("Database                     : ").append(database.trim()).append('\n')
                    .append("Authorization ID             : ").append(authorizationId.trim()).append('\n')
                    .append("SQL root                     : ").append(config.root()).append('\n')
                    .append("Files discovered             : ").append(filesDiscovered).append('\n')
                    .append("Selected final tables        : ").append(expectedTables).append('\n')
                    .append("Exact table/column shape     : ").append(exactTables).append('\n')
                    .append("Column mismatch tables       : ").append(mismatchTables).append('\n')
                    .append("Missing selected-final tables: ").append(missingTables).append('\n')
                    .append("Expected columns in mismatch : ").append(expectedColumns).append('\n')
                    .append("Catalog columns in mismatch  : ").append(catalogColumns).append('\n')
                    .append("Extra catalog columns        : ").append(extraColumns).append('\n')
                    .append("Missing final columns        : ").append(missingColumns).append('\n')
                    .append("Unexplained extra columns    : ").append(unexplainedExtraColumns).append("\n\n")
                    .append("Classifications:\n");
            classificationCounts.forEach((key, value) ->
                    out.append("  ").append(String.format(Locale.ROOT, "%-45s", key)).append(value).append('\n'));
            out.append("\nMutation policy               : READ ONLY; DIAGNOSIS ONLY\n")
                    .append("Historical evidence policy    : COLUMN MUST APPEAR IN AN EARLIER GENERATED CREATE TABLE VERSION\n")
                    .append("Elapsed                       : ").append(elapsed).append('\n')
                    .append("Report directory              : ").append(reportDir.toAbsolutePath()).append('\n');
            return out.toString();
        }

        void printSummary() { System.out.println(summary()); }
    }
}
