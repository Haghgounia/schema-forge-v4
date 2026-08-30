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
 * DB2 LUW P9.1 catalog reconciliation for final table and column shape.
 *
 * <p>This phase is intentionally read-only. It derives the selected final CREATE TABLE definition
 * from the generated DB2 LUW corpus and reconciles table existence plus ordered column names against
 * SYSCAT.TABLES/SYSCAT.COLUMNS. Keys, indexes and supported FKs are reconciled in subsequent P9 gates.</p>
 */
class Db2LuwCatalogReconciliationP91IT {
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
    void reconcilesSelectedFinalTablesAndColumnsWithDb2Catalog() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.db2luw.p9.sqlRoot=<generated DB2 LUW root> to run P9.1.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) fail("No DB2 LUW SQL files found below " + config.root());

        Map<String, TableShape> finalTables = loadFinalTableShapes(files);
        Map<String, TableShape> expected = new LinkedHashMap<>();
        for (Map.Entry<String, TableShape> entry : finalTables.entrySet()) {
            ObjectName name = ObjectName.parse(entry.getKey(), config.expectedSchema());
            if (name.owner().equalsIgnoreCase(config.expectedSchema())) {
                expected.put(name.name().toUpperCase(Locale.ROOT), entry.getValue());
            }
        }
        if (config.strictBaseline()) assertEquals(2310, expected.size(), "P9.1 final table baseline changed");

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Instant started = Instant.now();
        Report report = new Report(config, reportDir, files.size(), expected.size());

        DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
        Class.forName(config.driver());
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setReadOnly(true);
            verifyExpectedDatabase(connection, config.expectedDatabase());
            report.databaseProduct = connection.getMetaData().getDatabaseProductName();
            report.databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            report.database = scalar(connection, "VALUES CURRENT SERVER");
            report.authorizationId = scalar(connection, "VALUES CURRENT USER");

            Set<String> catalogTables = loadCatalogTables(connection, config.expectedSchema());
            Map<String, List<String>> catalogColumns = loadCatalogColumns(connection, config.expectedSchema());
            report.catalogTables = catalogTables.size();

            for (Map.Entry<String, TableShape> entry : expected.entrySet()) {
                String table = entry.getKey();
                TableShape desired = entry.getValue();
                if (!catalogTables.contains(table)) {
                    report.missingTables++;
                    report.tableRows.add(new TableRow(table, "MISSING_FROM_CATALOG", desired.file(),
                            desired.columns().size(), 0));
                    continue;
                }
                List<String> actual = catalogColumns.getOrDefault(table, List.of());
                report.expectedColumns += desired.columns().size();
                report.catalogColumnsForExpected += actual.size();
                if (desired.columns().equals(actual)) {
                    report.exactTables++;
                } else {
                    report.columnMismatchTables++;
                    report.tableRows.add(new TableRow(table, "COLUMN_SHAPE_MISMATCH", desired.file(),
                            desired.columns().size(), actual.size()));
                    addColumnDiffs(report, table, desired, actual);
                }
            }

            for (String table : catalogTables) {
                if (!expected.containsKey(table)) {
                    report.extraCatalogTables++;
                    report.tableRows.add(new TableRow(table, "EXTRA_CATALOG_TABLE", "",
                            0, catalogColumns.getOrDefault(table, List.of()).size()));
                }
            }
        } finally {
            report.elapsed = Duration.between(started, Instant.now());
            report.write();
            report.printSummary();
        }

        if (report.missingTables > 0 || report.columnMismatchTables > 0) {
            fail("DB2 LUW P9.1 catalog reconciliation found missing/column-mismatched generated tables. Report: "
                    + reportDir);
        }
        if (config.failOnExtraTables() && report.extraCatalogTables > 0) {
            fail("DB2 LUW P9.1 found " + report.extraCatalogTables
                    + " extra catalog tables in schema " + config.expectedSchema() + ". Report: " + reportDir);
        }
    }

    private Map<String, TableShape> loadFinalTableShapes(List<Path> files) throws IOException {
        Map<String, TableShape> result = new LinkedHashMap<>();
        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.DB2_LUW)) {
                String sql = stripLeadingComments(raw);
                Matcher matcher = CREATE_TABLE.matcher(sql);
                if (!matcher.find()) continue;
                String qualified = normalizeName(matcher.group(1));
                List<String> columns = extractColumns(sql, matcher.end());
                result.put(canonicalObjectKey(qualified), new TableShape(qualified, columns, normalize(file)));
            }
        }
        return result;
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
                if (ch == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    current.append(body.charAt(++i));
                } else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                current.append(ch);
                if (ch == '"' && i + 1 < body.length() && body.charAt(i + 1) == '"') {
                    current.append(body.charAt(++i));
                } else if (ch == '"') quotedIdentifier = false;
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
                    if (i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '"') {
                        out.append(trimmed.charAt(++i));
                    } else return out.toString();
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

    private static Set<String> loadCatalogTables(Connection connection, String schema) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (var ps = connection.prepareStatement(
                "SELECT TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA = ? AND TYPE IN ('T','U') ORDER BY TABNAME WITH UR")) {
            ps.setString(1, schema.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1).toUpperCase(Locale.ROOT));
            }
        }
        return result;
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

    private static void addColumnDiffs(Report report, String table, TableShape desired, List<String> actual) {
        int max = Math.max(desired.columns().size(), actual.size());
        for (int i = 0; i < max; i++) {
            String expected = i < desired.columns().size() ? desired.columns().get(i) : "";
            String observed = i < actual.size() ? actual.get(i) : "";
            if (!expected.equals(observed)) {
                report.columnRows.add(new ColumnRow(table, i, expected, observed, desired.file()));
            }
        }
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
            throw new IllegalStateException("Refusing P9.1: expected DB " + expected + " but connected to " + actual);
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

    private static String canonicalObjectKey(String value) {
        return normalizeName(value).replace("\"", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeName(String value) {
        return value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

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

    private record TableShape(String qualifiedName, List<String> columns, String file) { }
    private record TableRow(String table, String status, String desiredFile, int expectedColumns, int catalogColumns) { }
    private record ColumnRow(String table, int ordinal, String expectedColumn, String catalogColumn, String desiredFile) { }

    private record Config(
            Path root, String fileSuffix, int maxFiles, String url, String user, String password,
            String driver, String expectedDatabase, String expectedSchema, int loginTimeoutSeconds,
            boolean strictBaseline, boolean failOnExtraTables, Path reportBase) {
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
                    Boolean.parseBoolean(System.getProperty("schemaforge.db2luw.p9.failOnExtraTables", "false")),
                    Path.of(System.getProperty("schemaforge.db2luw.p9.reportBase", "target/db2luw-p9.1-catalog-reconciliation")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("P9.1 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("P9.1 expected schema required");
        }
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int filesDiscovered;
        private final int expectedTables;
        private final List<TableRow> tableRows = new ArrayList<>();
        private final List<ColumnRow> columnRows = new ArrayList<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String database = "";
        private String authorizationId = "";
        private int catalogTables;
        private int exactTables;
        private int missingTables;
        private int columnMismatchTables;
        private int extraCatalogTables;
        private int expectedColumns;
        private int catalogColumnsForExpected;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, int expectedTables) {
            this.config = config; this.reportDir = reportDir; this.filesDiscovered = filesDiscovered; this.expectedTables = expectedTables;
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(reportDir.resolve("db2luw-p9.1-table-reconciliation.csv"), StandardCharsets.UTF_8)) {
                out.write("table,status,desired_file,expected_columns,catalog_columns\n");
                for (TableRow row : tableRows) {
                    out.write(csv(row.table()) + "," + csv(row.status()) + "," + csv(row.desiredFile()) + ","
                            + row.expectedColumns() + "," + row.catalogColumns() + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(reportDir.resolve("db2luw-p9.1-column-differences.csv"), StandardCharsets.UTF_8)) {
                out.write("table,ordinal,expected_column,catalog_column,desired_file\n");
                for (ColumnRow row : columnRows) {
                    out.write(csv(row.table()) + "," + row.ordinal() + "," + csv(row.expectedColumn()) + ","
                            + csv(row.catalogColumn()) + "," + csv(row.desiredFile()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("db2luw-p9.1-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            return "DB2 LUW P9.1 Catalog Reconciliation - Tables/Columns\n"
                    + "=================================================\n"
                    + "Database product          : " + databaseProduct + "\n"
                    + "Database version          : " + databaseVersion + "\n"
                    + "Database                  : " + database.trim() + "\n"
                    + "Authorization ID          : " + authorizationId.trim() + "\n"
                    + "SQL root                  : " + config.root() + "\n"
                    + "Files discovered          : " + filesDiscovered + "\n"
                    + "Expected final tables     : " + expectedTables + "\n"
                    + "Catalog tables in schema  : " + catalogTables + "\n"
                    + "Exact table/column shape  : " + exactTables + "\n"
                    + "Missing generated tables  : " + missingTables + "\n"
                    + "Column mismatch tables    : " + columnMismatchTables + "\n"
                    + "Extra catalog tables      : " + extraCatalogTables + "\n"
                    + "Expected columns checked  : " + expectedColumns + "\n"
                    + "Catalog columns checked   : " + catalogColumnsForExpected + "\n"
                    + "Extra-table fail policy   : " + config.failOnExtraTables() + "\n"
                    + "Mutation policy           : READ ONLY\n"
                    + "Elapsed                   : " + elapsed + "\n"
                    + "Report directory          : " + reportDir.toAbsolutePath() + "\n";
        }

        void printSummary() { System.out.println(summary()); }
    }
}
