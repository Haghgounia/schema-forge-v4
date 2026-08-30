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
 * P9.1.3 read-only diagnosis for the P9 CREATE TABLE column extractor itself.
 *
 * <p>P9.1/P9.1.2 treated each top-level CREATE body element as a column declaration only when
 * the first character sequence was an identifier. A generated warning/comment immediately before
 * a column therefore made the legacy extractor skip that real column even though DB2 executed it.
 * This gate compares the legacy extraction with a comment-aware extraction and the live catalog.
 * No generated SQL or catalog object is changed.</p>
 */
class Db2LuwCatalogCreateBodyParserDiagnosisP913IT {
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
    void diagnosesCreateBodyExtractorAgainstDb2Catalog() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.db2luw.p9.sqlRoot=<generated DB2 LUW root> to run P9.1.3.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) fail("No DB2 LUW SQL files found below " + config.root());

        Map<String, TableShape> finalTables = loadFinalTableShapes(files, config.root());
        Map<String, TableShape> expected = new LinkedHashMap<>();
        for (Map.Entry<String, TableShape> entry : finalTables.entrySet()) {
            ObjectName name = ObjectName.parse(entry.getKey(), config.expectedSchema());
            if (name.owner().equalsIgnoreCase(config.expectedSchema())) {
                expected.put(name.name().toUpperCase(Locale.ROOT), entry.getValue());
            }
        }
        if (config.strictBaseline()) assertEquals(2310, expected.size(), "P9.1.3 final table baseline changed");

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

            Map<String, List<String>> catalogColumns = loadCatalogColumns(connection, config.expectedSchema());
            for (Map.Entry<String, TableShape> entry : expected.entrySet()) {
                String table = entry.getKey();
                TableShape shape = entry.getValue();
                List<String> actual = catalogColumns.get(table);
                if (actual == null) {
                    report.missingTables++;
                    report.rows.add(new TableRow(table, "MISSING_TABLE", shape.legacyColumns().size(),
                            shape.robustColumns().size(), 0, String.join("|", shape.recoveredColumns()), "", "", shape.file()));
                    continue;
                }

                boolean legacyExact = shape.legacyColumns().equals(actual);
                boolean robustExact = shape.robustColumns().equals(actual);
                if (legacyExact) report.legacyExactTables++; else report.legacyMismatchTables++;
                if (robustExact) report.robustExactTables++; else report.robustMismatchTables++;

                if (!shape.recoveredColumns().isEmpty()) {
                    report.tablesWithCommentRecoveredColumns++;
                    report.commentRecoveredColumns += shape.recoveredColumns().size();
                    for (RecoveredColumn recovered : shape.recoveredEvidence()) {
                        report.recoveredRows.add(new RecoveredRow(table, recovered.column(), recovered.rawElement(), shape.file()));
                    }
                }

                Set<String> robustSet = new LinkedHashSet<>(shape.robustColumns());
                Set<String> actualSet = new LinkedHashSet<>(actual);
                List<String> residualMissing = shape.robustColumns().stream().filter(c -> !actualSet.contains(c)).toList();
                List<String> residualExtra = actual.stream().filter(c -> !robustSet.contains(c)).toList();
                report.residualMissingColumns += residualMissing.size();
                report.residualExtraColumns += residualExtra.size();

                String classification;
                if (legacyExact) classification = "LEGACY_EXTRACTOR_ALREADY_EXACT";
                else if (robustExact) classification = "COMMENT_AWARE_EXTRACTOR_MATCHES_CATALOG";
                else if (residualMissing.isEmpty() && !residualExtra.isEmpty()) classification = "RESIDUAL_CATALOG_EXTRA_AFTER_COMMENT_FIX";
                else if (!residualMissing.isEmpty() && residualExtra.isEmpty()) classification = "RESIDUAL_CATALOG_MISSING_AFTER_COMMENT_FIX";
                else if (!residualMissing.isEmpty()) classification = "RESIDUAL_MIXED_DRIFT_AFTER_COMMENT_FIX";
                else classification = "RESIDUAL_ORDER_DRIFT_AFTER_COMMENT_FIX";
                report.classifications.merge(classification, 1, Integer::sum);

                if (!legacyExact || !robustExact || !shape.recoveredColumns().isEmpty()) {
                    report.rows.add(new TableRow(table, classification, shape.legacyColumns().size(),
                            shape.robustColumns().size(), actual.size(), String.join("|", shape.recoveredColumns()),
                            String.join("|", residualMissing), String.join("|", residualExtra), shape.file()));
                }
            }
        } finally {
            report.elapsed = Duration.between(started, Instant.now());
            report.write();
            report.printSummary();
        }

        if (report.missingTables > 0) {
            fail("DB2 LUW P9.1.3 found missing selected-final tables. Report: " + reportDir);
        }
    }

    private Map<String, TableShape> loadFinalTableShapes(List<Path> files, Path root) throws IOException {
        Map<String, TableShape> result = new LinkedHashMap<>();
        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.DB2_LUW)) {
                String sql = stripLeadingComments(raw);
                Matcher matcher = CREATE_TABLE.matcher(sql);
                if (!matcher.find()) continue;
                String qualified = normalizeName(matcher.group(1));
                BodyExtraction extraction = extractColumns(sql, matcher.end());
                result.put(canonicalObjectKey(qualified), new TableShape(
                        qualified, extraction.legacyColumns(), extraction.robustColumns(),
                        extraction.recoveredColumns(), extraction.recoveredEvidence(),
                        normalize(root.relativize(file))));
            }
        }
        return result;
    }

    private static BodyExtraction extractColumns(String createSql, int searchFrom) {
        int open = createSql.indexOf('(', searchFrom);
        if (open < 0) throw new IllegalArgumentException("CREATE TABLE has no body: " + oneLine(createSql));
        int close = matchingParen(createSql, open);
        if (close < 0) throw new IllegalArgumentException("CREATE TABLE has unclosed body: " + oneLine(createSql));
        String body = createSql.substring(open + 1, close);
        List<String> legacy = new ArrayList<>();
        List<String> robust = new ArrayList<>();
        List<String> recovered = new ArrayList<>();
        List<RecoveredColumn> recoveredEvidence = new ArrayList<>();

        for (String element : splitTopLevel(body)) {
            String rawElement = element.trim();
            if (rawElement.isEmpty()) continue;

            String legacyFirst = firstIdentifier(rawElement);
            String legacyColumn = normalizeColumnCandidate(legacyFirst);
            if (legacyColumn != null) legacy.add(legacyColumn);

            String commentAware = stripLeadingComments(rawElement);
            String robustFirst = firstIdentifier(commentAware);
            String robustColumn = normalizeColumnCandidate(robustFirst);
            if (robustColumn != null) {
                robust.add(robustColumn);
                if (legacyColumn == null) {
                    recovered.add(robustColumn);
                    recoveredEvidence.add(new RecoveredColumn(robustColumn, oneLine(rawElement)));
                }
            }
        }
        return new BodyExtraction(List.copyOf(legacy), List.copyOf(robust), List.copyOf(recovered), List.copyOf(recoveredEvidence));
    }

    private static String normalizeColumnCandidate(String first) {
        if (first == null) return null;
        String normalized = unquote(first).toUpperCase(Locale.ROOT);
        if (NON_COLUMN_PREFIXES.contains(normalized)) return null;
        return normalized;
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
            throw new IllegalStateException("Refusing P9.1.3: expected DB " + expected + " but connected to " + actual);
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

    private record RecoveredColumn(String column, String rawElement) { }
    private record BodyExtraction(List<String> legacyColumns, List<String> robustColumns,
                                  List<String> recoveredColumns, List<RecoveredColumn> recoveredEvidence) { }
    private record TableShape(String qualifiedName, List<String> legacyColumns, List<String> robustColumns,
                              List<String> recoveredColumns, List<RecoveredColumn> recoveredEvidence, String file) { }
    private record TableRow(String table, String classification, int legacyColumns, int robustColumns,
                            int catalogColumns, String commentRecoveredColumns, String residualMissingColumns,
                            String residualExtraColumns, String file) { }
    private record RecoveredRow(String table, String column, String rawElement, String file) { }

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
                    Path.of(System.getProperty("schemaforge.db2luw.p9.p913.reportBase",
                            "target/db2luw-p9.1.3-create-body-parser-diagnosis")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("P9.1.3 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("P9.1.3 expected schema required");
        }
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int filesDiscovered;
        private final int expectedTables;
        private final List<TableRow> rows = new ArrayList<>();
        private final List<RecoveredRow> recoveredRows = new ArrayList<>();
        private final Map<String, Integer> classifications = new LinkedHashMap<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String database = "";
        private String authorizationId = "";
        private int legacyExactTables;
        private int legacyMismatchTables;
        private int robustExactTables;
        private int robustMismatchTables;
        private int missingTables;
        private int tablesWithCommentRecoveredColumns;
        private int commentRecoveredColumns;
        private int residualExtraColumns;
        private int residualMissingColumns;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, int expectedTables) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.expectedTables = expectedTables;
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.1.3-parser-reconciliation.csv"), StandardCharsets.UTF_8)) {
                out.write("table,classification,legacy_columns,comment_aware_columns,catalog_columns,comment_recovered_columns,residual_missing_columns,residual_extra_columns,selected_final_file\n");
                for (TableRow row : rows) {
                    out.write(csv(row.table()) + "," + csv(row.classification()) + "," + row.legacyColumns() + ","
                            + row.robustColumns() + "," + row.catalogColumns() + "," + csv(row.commentRecoveredColumns()) + ","
                            + csv(row.residualMissingColumns()) + "," + csv(row.residualExtraColumns()) + "," + csv(row.file()) + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.1.3-comment-recovered-columns.csv"), StandardCharsets.UTF_8)) {
                out.write("table,column,raw_create_body_element,selected_final_file\n");
                for (RecoveredRow row : recoveredRows) {
                    out.write(csv(row.table()) + "," + csv(row.column()) + "," + csv(row.rawElement()) + "," + csv(row.file()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("db2luw-p9.1.3-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            StringBuilder out = new StringBuilder();
            out.append("DB2 LUW P9.1.3 CREATE Body Parser Diagnosis\n")
                    .append("==========================================\n")
                    .append("Database product              : ").append(databaseProduct).append('\n')
                    .append("Database version              : ").append(databaseVersion).append('\n')
                    .append("Database                      : ").append(database.trim()).append('\n')
                    .append("Authorization ID              : ").append(authorizationId.trim()).append('\n')
                    .append("SQL root                      : ").append(config.root()).append('\n')
                    .append("Files discovered              : ").append(filesDiscovered).append('\n')
                    .append("Selected final tables         : ").append(expectedTables).append('\n')
                    .append("Legacy extractor exact tables: ").append(legacyExactTables).append('\n')
                    .append("Legacy extractor mismatches  : ").append(legacyMismatchTables).append('\n')
                    .append("Comment-aware exact tables    : ").append(robustExactTables).append('\n')
                    .append("Comment-aware mismatches      : ").append(robustMismatchTables).append('\n')
                    .append("Missing catalog tables        : ").append(missingTables).append('\n')
                    .append("Tables with recovered columns : ").append(tablesWithCommentRecoveredColumns).append('\n')
                    .append("Columns recovered from comments: ").append(commentRecoveredColumns).append('\n')
                    .append("Residual extra catalog columns: ").append(residualExtraColumns).append('\n')
                    .append("Residual missing final columns: ").append(residualMissingColumns).append("\n\n")
                    .append("Classifications:\n");
            classifications.forEach((key, value) ->
                    out.append("  ").append(String.format(Locale.ROOT, "%-50s", key)).append(value).append('\n'));
            out.append("\nMutation policy                : READ ONLY; DIAGNOSIS ONLY\n")
                    .append("Parser comparison policy       : LEGACY VS COMMENT-AWARE TOP-LEVEL CREATE BODY EXTRACTION\n")
                    .append("Elapsed                        : ").append(elapsed).append('\n')
                    .append("Report directory               : ").append(reportDir.toAbsolutePath()).append('\n');
            return out.toString();
        }

        void printSummary() { System.out.println(summary()); }
    }
}
