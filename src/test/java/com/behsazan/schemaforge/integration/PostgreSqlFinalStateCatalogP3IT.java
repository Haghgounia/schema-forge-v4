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
 * PostgreSQL PG-P3 final-state catalog reconciliation for tables and ordered columns.
 *
 * <p>The historical PG-P2 runner drops each logical table before replaying every revision, so after
 * all 5,321 scripts complete the database should contain the last generated revision for each of the
 * 2,670 logical tables. This gate independently derives that same selected-final set from file order
 * and compares it with PostgreSQL catalog state. It is read-only.</p>
 */
class PostgreSqlFinalStateCatalogP3IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:(?:UNLOGGED|TEMP|TEMPORARY)\\s+)?TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + QUALIFIED_NAME + ")");
    private static final Set<String> NON_COLUMN_PREFIXES = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE", "LIKE");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void reconcilesSelectedFinalTablesAndColumnsWithPostgreSqlCatalog() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.postgresql.p3.sqlRoot=<generated PostgreSQL root> to run PG-P3.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix());
        if (files.isEmpty()) fail("No PostgreSQL SQL files found below " + config.root());
        if (config.strictBaseline()) assertEquals(5321, files.size(), "PG-P3 accepted corpus file baseline changed");

        Map<String, TableShape> allFinal = loadFinalTableShapes(files);
        String expectedSchema = pgIdentifier(config.expectedSchema());
        Map<String, TableShape> expected = new LinkedHashMap<>();
        for (Map.Entry<String, TableShape> entry : allFinal.entrySet()) {
            ObjectName object = ObjectName.parse(entry.getValue().qualifiedName(), expectedSchema);
            if (object.schema().equals(expectedSchema)) {
                expected.put(object.name(), entry.getValue());
            }
        }
        if (config.strictBaseline()) assertEquals(2670, expected.size(), "PG-P3 selected-final table baseline changed");

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Instant started = Instant.now();
        Report report = new Report(config, reportDir, files.size(), expected.size());

        DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
        Class.forName(config.driver());
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setReadOnly(true);
            report.databaseProduct = connection.getMetaData().getDatabaseProductName();
            report.databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            report.database = scalar(connection, "SELECT current_database()");
            report.authorizationId = scalar(connection, "SELECT current_user");
            report.schema = expectedSchema;
            if (!config.expectedDatabase().isBlank()
                    && !config.expectedDatabase().equalsIgnoreCase(report.database)) {
                throw new IllegalStateException("Refusing PG-P3: expected database "
                        + config.expectedDatabase() + " but connected to " + report.database);
            }

            Set<String> catalogTables = loadCatalogTables(connection, expectedSchema);
            Map<String, List<String>> catalogColumns = loadCatalogColumns(connection, expectedSchema);
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
            fail("PostgreSQL PG-P3 found missing/column-mismatched selected-final tables. Report: " + reportDir);
        }
        if (config.failOnExtraTables() && report.extraCatalogTables > 0) {
            fail("PostgreSQL PG-P3 found extra catalog tables in schema " + expectedSchema + ". Report: " + reportDir);
        }
    }

    private Map<String, TableShape> loadFinalTableShapes(List<Path> files) throws IOException {
        Map<String, TableShape> result = new LinkedHashMap<>();
        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.POSTGRESQL)) {
                String sql = stripLeadingComments(raw);
                Matcher matcher = CREATE_TABLE.matcher(sql);
                if (!matcher.find()) continue;
                String qualified = normalizeName(matcher.group(1));
                ObjectName object = ObjectName.parse(qualified, "public");
                List<String> columns = extractColumns(sql, matcher.end());
                result.put(object.canonicalKey(), new TableShape(qualified, columns, relative(file)));
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
            String trimmed = stripLeadingComments(element.trim());
            if (trimmed.isEmpty()) continue;
            String first = firstIdentifier(trimmed);
            if (first == null) continue;
            String keyword = identifierText(first).toUpperCase(Locale.ROOT);
            if (NON_COLUMN_PREFIXES.contains(keyword)) continue;
            columns.add(pgIdentifier(first));
        }
        return List.copyOf(columns);
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { i++; blockComment = false; }
                continue;
            }
            if (dollarTag != null) {
                if (text.startsWith(dollarTag, i)) {
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (single) {
                if (ch == '\'' && next == '\'') i++;
                else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                if (ch == '"' && next == '"') i++;
                else if (ch == '"') quotedIdentifier = false;
                continue;
            }
            if (ch == '-' && next == '-') { i++; lineComment = true; }
            else if (ch == '/' && next == '*') { i++; blockComment = true; }
            else if (ch == '\'') single = true;
            else if (ch == '"') quotedIdentifier = true;
            else if (ch == '$') {
                String tag = dollarTagAt(text, i);
                if (tag != null) { dollarTag = tag; i += tag.length() - 1; }
            } else if (ch == '(') depth++;
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
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            char next = i + 1 < body.length() ? body.charAt(i + 1) : '\0';
            current.append(ch);
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { current.append(next); i++; blockComment = false; }
                continue;
            }
            if (dollarTag != null) {
                if (body.startsWith(dollarTag, i)) {
                    for (int j = 1; j < dollarTag.length(); j++) current.append(body.charAt(i + j));
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (single) {
                if (ch == '\'' && next == '\'') { current.append(next); i++; }
                else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                if (ch == '"' && next == '"') { current.append(next); i++; }
                else if (ch == '"') quotedIdentifier = false;
                continue;
            }
            if (ch == '-' && next == '-') { current.append(next); i++; lineComment = true; }
            else if (ch == '/' && next == '*') { current.append(next); i++; blockComment = true; }
            else if (ch == '\'') single = true;
            else if (ch == '"') quotedIdentifier = true;
            else if (ch == '$') {
                String tag = dollarTagAt(body, i);
                if (tag != null) {
                    for (int j = 1; j < tag.length(); j++) current.append(body.charAt(i + j));
                    i += tag.length() - 1;
                    dollarTag = tag;
                }
            } else if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) {
                current.setLength(current.length() - 1);
                result.add(current.toString());
                current.setLength(0);
            }
        }
        result.add(current.toString());
        return result;
    }

    private static String dollarTagAt(String text, int offset) {
        if (text.charAt(offset) != '$') return null;
        int end = text.indexOf('$', offset + 1);
        if (end < 0) return null;
        String middle = text.substring(offset + 1, end);
        if (!middle.matches("[A-Za-z_][A-Za-z0-9_]*|")) return null;
        return text.substring(offset, end + 1);
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
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) break;
            i++;
        }
        return i == 0 ? null : trimmed.substring(0, i);
    }

    private static Set<String> loadCatalogTables(Connection connection, String schema) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        String sql = """
                SELECT c.relname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind IN ('r','p')
                ORDER BY c.relname
                """;
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        return result;
    }

    private static Map<String, List<String>> loadCatalogColumns(Connection connection, String schema) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String sql = """
                SELECT c.relname, a.attname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
                WHERE n.nspname = ?
                  AND c.relkind IN ('r','p')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY c.relname, a.attnum
                """;
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(rs.getString(2));
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
                report.columnRows.add(new ColumnRow(table, i + 1, expected, observed, desired.file()));
            }
        }
    }

    private static List<Path> findSqlFiles(Path root, String suffix) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
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

    private static String normalizeName(String value) {
        return value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String identifierText(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    /** PostgreSQL folds unquoted identifiers to lower-case; quoted identifiers preserve case. */
    private static String pgIdentifier(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return identifierText(trimmed);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String relative(Path path) { return path.toString().replace('\\', '/'); }
    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
    private static String csv(String value) { return '"' + (value == null ? "" : value).replace("\"", "\"\"") + '"'; }

    private record ObjectName(String schema, String name) {
        static ObjectName parse(String qualified, String defaultSchema) {
            String normalized = normalizeName(qualified);
            int dot = normalized.indexOf('.');
            if (dot < 0) return new ObjectName(pgIdentifier(defaultSchema), pgIdentifier(normalized));
            return new ObjectName(pgIdentifier(normalized.substring(0, dot)), pgIdentifier(normalized.substring(dot + 1)));
        }
        String canonicalKey() { return schema + "." + name; }
    }

    private record TableShape(String qualifiedName, List<String> columns, String file) { }
    private record TableRow(String table, String status, String desiredFile, int expectedColumns, int catalogColumns) { }
    private record ColumnRow(String table, int ordinal, String expectedColumn, String catalogColumn, String desiredFile) { }

    private record Config(
            Path root, String fileSuffix, String url, String user, String password, String driver,
            String expectedDatabase, String expectedSchema, int loginTimeoutSeconds, boolean strictBaseline,
            boolean failOnExtraTables, Path reportBase) {
        static Config load() {
            String root = firstNonBlank(
                    System.getProperty("schemaforge.postgresql.p3.sqlRoot"),
                    System.getProperty("postgresql.sql.root"),
                    System.getenv("POSTGRESQL_SQL_ROOT"));
            return new Config(
                    root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("schemaforge.postgresql.p3.fileSuffix", ".postgresql.sql"),
                    System.getProperty("schemaforge.postgresql.p3.jdbc.url", "jdbc:postgresql://localhost:5433/mydb"),
                    System.getProperty("schemaforge.postgresql.p3.jdbc.user", "postgres"),
                    System.getProperty("schemaforge.postgresql.p3.jdbc.password", "123456"),
                    System.getProperty("schemaforge.postgresql.p3.jdbc.driver", "org.postgresql.Driver"),
                    System.getProperty("schemaforge.postgresql.p3.expectedDatabase", "mydb"),
                    System.getProperty("schemaforge.postgresql.p3.expectedSchema", "TSTSHMA"),
                    Integer.getInteger("schemaforge.postgresql.p3.loginTimeoutSeconds", 20),
                    Boolean.parseBoolean(System.getProperty("schemaforge.postgresql.p3.strictBaseline", "true")),
                    Boolean.parseBoolean(System.getProperty("schemaforge.postgresql.p3.failOnExtraTables", "false")),
                    Path.of(System.getProperty("schemaforge.postgresql.p3.reportBase",
                            "target/postgresql-p3-final-state-catalog"))); 
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("PG-P3 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("PG-P3 expected schema required");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
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
        private String schema = "";
        private int catalogTables;
        private int exactTables;
        private int missingTables;
        private int columnMismatchTables;
        private int extraCatalogTables;
        private int expectedColumns;
        private int catalogColumnsForExpected;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, int expectedTables) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.expectedTables = expectedTables;
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("postgresql-p3-table-reconciliation.csv"), StandardCharsets.UTF_8)) {
                out.write("table,status,desired_file,expected_columns,catalog_columns\n");
                for (TableRow row : tableRows) {
                    out.write(csv(row.table()) + "," + csv(row.status()) + "," + csv(row.desiredFile()) + ","
                            + row.expectedColumns() + "," + row.catalogColumns() + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("postgresql-p3-column-differences.csv"), StandardCharsets.UTF_8)) {
                out.write("table,ordinal,expected_column,catalog_column,desired_file\n");
                for (ColumnRow row : columnRows) {
                    out.write(csv(row.table()) + "," + row.ordinal() + "," + csv(row.expectedColumn()) + ","
                            + csv(row.catalogColumn()) + "," + csv(row.desiredFile()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("postgresql-p3-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            return "PostgreSQL PG-P3 Final-State Catalog Reconciliation - Tables/Columns\n"
                    + "=================================================================\n"
                    + "Database product          : " + databaseProduct + "\n"
                    + "Database version          : " + databaseVersion + "\n"
                    + "Database                  : " + database + "\n"
                    + "Authorization ID          : " + authorizationId + "\n"
                    + "Schema                    : " + schema + "\n"
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
