package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DB2 LUW P9.3 catalog lifecycle reconciliation for the 310 P8 evidence-valid foreign keys.
 *
 * <p>P8 intentionally drops each successfully validated foreign key, so the disposable catalog is
 * not expected to persist all 310 constraints. P9.3 therefore validates the exact catalog projection
 * of each evidence-valid FK without changing pre-existing state: an already-present FK is reconciled
 * in place, while an absent FK is created, reconciled through SYSCAT, and dropped immediately.</p>
 */
class Db2LuwCatalogForeignKeyReconciliationP93IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String P8_SUCCESS_RESOURCE =
            "evidence/db2luw-p8/20260830_102243_952/db2luw-p8-success.csv";

    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")"
                    + "\\s+ADD\\s+CONSTRAINT\\s+(" + IDENTIFIER + ")"
                    + "\\s+FOREIGN\\s+KEY\\s*\\(([^)]*)\\)"
                    + "\\s+REFERENCES\\s+(" + QUALIFIED_NAME + ")\\s*\\(([^)]*)\\)(.*)$");
    private static final Pattern ON_DELETE = Pattern.compile(
            "(?is)\\bON\\s+DELETE\\s+(NO\\s+ACTION|RESTRICT|CASCADE|SET\\s+NULL)\\b");
    private static final Pattern ON_UPDATE = Pattern.compile(
            "(?is)\\bON\\s+UPDATE\\s+(NO\\s+ACTION|RESTRICT|CASCADE|SET\\s+NULL)\\b");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void retainedP8SuccessEvidenceDefinesExactP93Scope() throws Exception {
        List<EvidenceRow> rows = readEvidence();
        assertEquals(310, rows.size(), "P9.3 retained P8 success row count");
        assertEquals(244, rows.stream().filter(r -> "BASELINE".equals(r.action())).count());
        assertEquals(58, rows.stream().filter(r -> "COLUMN_RENAME_CONFIRMED".equals(r.action())).count());
        assertEquals(8, rows.stream().filter(r -> "TABLE_RENAME_CONFIRMED".equals(r.action())).count());
        assertEquals(310, rows.stream().map(r -> r.sourceTable() + "|" + r.constraintName()).distinct().count(),
                "P9.3 FK identity must be unique");
    }

    @Test
    void reconcilesEvidenceValidForeignKeysThroughDb2Catalog() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.db2luw.p9.sqlRoot=<generated DB2 LUW root> to run P9.3.");
        config.validate();

        List<EvidenceRow> evidence = readEvidence();
        if (config.strictBaseline()) assertEquals(310, evidence.size(), "P9.3 P8-success baseline changed");

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Report report = new Report(config, reportDir, evidence.size());
        Instant started = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName(config.driver());
            try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                verifyExpectedDatabase(connection, config.expectedDatabase());
                report.databaseProduct = connection.getMetaData().getDatabaseProductName();
                report.databaseVersion = connection.getMetaData().getDatabaseProductVersion();
                report.database = scalar(connection, "VALUES CURRENT SERVER");
                report.authorizationId = scalar(connection, "VALUES CURRENT USER");
                report.catalogFkBefore = countForeignKeys(connection, config.expectedSchema());

                int sequence = 0;
                for (EvidenceRow row : evidence) {
                    sequence++;
                    reconcileOne(connection, config, report, row);
                    if (sequence % config.progressEvery() == 0 || sequence == evidence.size()) {
                        System.out.printf(Locale.ROOT,
                                "DB2 LUW P9.3 FK: %,d / %,d, exact=%,d, created=%,d, preexisting=%,d, "
                                        + "mismatch=%,d, errors=%,d, cleanup-errors=%,d%n",
                                sequence, evidence.size(), report.catalogExact, report.createdForValidation,
                                report.preexistingExact, report.catalogMismatch, report.executionErrors,
                                report.cleanupErrors);
                    }
                }
                report.catalogFkAfter = countForeignKeys(connection, config.expectedSchema());
            }
        } catch (Throwable throwable) {
            fatal = throwable;
            report.fatalMessages.add(rootMessage(throwable));
        } finally {
            report.elapsed = Duration.between(started, Instant.now());
            report.write();
            report.printSummary();
        }

        if (fatal != null) {
            if (fatal instanceof Exception exception) throw exception;
            throw new RuntimeException(fatal);
        }
        if (report.catalogFkBefore != report.catalogFkAfter) {
            fail("DB2 LUW P9.3 changed persistent FK catalog state (before=" + report.catalogFkBefore
                    + ", after=" + report.catalogFkAfter + "). Report: " + reportDir);
        }
        if (report.executionErrors > 0 || report.catalogMismatch > 0 || report.cleanupErrors > 0) {
            fail("DB2 LUW P9.3 FK catalog reconciliation found errors/mismatches. Report: " + reportDir);
        }
        assertEquals(evidence.size(), report.catalogExact, "Every P8-success FK must reconcile exactly in catalog");
    }

    private void reconcileOne(Connection connection, Config config, Report report, EvidenceRow row) throws Exception {
        Path file = config.root().resolve(row.file().replace('/', java.io.File.separatorChar)).normalize();
        if (!file.startsWith(config.root()) || !Files.isRegularFile(file)) {
            report.executionErrors++;
            report.rows.add(ResultRow.error(row, "SOURCE_FILE_NOT_FOUND", file.toString()));
            return;
        }

        ParsedFk parsed = findForeignKey(file, row, config.expectedSchema());
        if (parsed == null) {
            report.executionErrors++;
            report.rows.add(ResultRow.error(row, "FK_STATEMENT_NOT_FOUND", file.toString()));
            return;
        }

        ParsedFk resolved = parsed.withReferencedTarget(row.resolvedReferencedTable(),
                parsePipeIdentifiers(row.resolvedReferencedColumns()));
        if (!sameObject(parsed.sourceTable(), row.sourceTable(), config.expectedSchema())) {
            report.executionErrors++;
            report.rows.add(ResultRow.error(row, "SOURCE_TABLE_EVIDENCE_MISMATCH", parsed.sourceTable()));
            return;
        }

        CatalogFk existing = loadCatalogFk(connection, resolved, config.expectedSchema());
        if (existing != null) {
            String mismatch = compareCatalog(resolved, existing, config.expectedSchema());
            if (mismatch.isBlank()) {
                report.catalogExact++;
                report.preexistingExact++;
                report.rows.add(ResultRow.exact(row, resolved, existing, "PREEXISTING_EXACT"));
            } else {
                report.catalogMismatch++;
                report.rows.add(ResultRow.mismatch(row, resolved, existing, "PREEXISTING_MISMATCH", mismatch));
            }
            return;
        }

        report.attemptedCreates++;
        boolean created = false;
        Instant started = Instant.now();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute(resolved.sql());
            created = true;
            report.createdForValidation++;
            CatalogFk actual = loadCatalogFk(connection, resolved, config.expectedSchema());
            if (actual == null) {
                report.catalogMismatch++;
                report.rows.add(ResultRow.error(row, "CATALOG_ROW_NOT_FOUND_AFTER_CREATE", oneLine(resolved.sql())));
                return;
            }
            String mismatch = compareCatalog(resolved, actual, config.expectedSchema());
            if (mismatch.isBlank()) {
                report.catalogExact++;
                report.rows.add(ResultRow.exact(row, resolved, actual, "CREATED_AND_EXACT"));
            } else {
                report.catalogMismatch++;
                report.rows.add(ResultRow.mismatch(row, resolved, actual, "CREATED_BUT_MISMATCHED", mismatch));
            }
        } catch (SQLException exception) {
            report.executionErrors++;
            report.rows.add(ResultRow.sqlError(row, resolved, exception,
                    Duration.between(started, Instant.now()).toMillis()));
            if (connectionFailure(exception)) {
                throw new SQLRecoverableException("DB2 LUW connection failed during P9.3 FK validation",
                        exception.getSQLState(), exception.getErrorCode(), exception);
            }
        } finally {
            if (created) {
                try {
                    dropForeignKey(connection, resolved, config.expectedSchema());
                } catch (SQLException cleanup) {
                    report.cleanupErrors++;
                    report.cleanupRows.add(new CleanupRow(row.file(), row.sourceTable(), row.constraintName(),
                            cleanup.getErrorCode(), cleanup.getSQLState(), oneLine(cleanup.getMessage())));
                    if (connectionFailure(cleanup)) throw cleanup;
                }
            }
        }
    }

    private ParsedFk findForeignKey(Path file, EvidenceRow row, String defaultSchema) throws IOException {
        String script = Files.readString(file, StandardCharsets.UTF_8);
        int statementIndex = 0;
        for (String raw : splitter.parse(script, DatabasePlatform.DB2_LUW)) {
            statementIndex++;
            String sql = stripLeadingComments(raw);
            Matcher matcher = FOREIGN_KEY.matcher(sql);
            if (!matcher.find()) continue;
            String source = normalizeName(matcher.group(1));
            String constraint = normalizeName(matcher.group(2));
            if (!sameObject(source, row.sourceTable(), defaultSchema)
                    || !identifierKey(constraint).equals(identifierKey(row.constraintName()))) continue;
            return new ParsedFk(source, constraint, parseIdentifierList(matcher.group(3)),
                    normalizeName(matcher.group(4)), parseIdentifierList(matcher.group(5)),
                    matcher.group(6), sql, statementIndex);
        }
        return null;
    }

    private static CatalogFk loadCatalogFk(Connection connection, ParsedFk expected, String defaultSchema)
            throws SQLException {
        ObjectName source = ObjectName.parse(expected.sourceTable(), defaultSchema);
        String sql = "SELECT R.REFTABSCHEMA, R.REFTABNAME, R.REFKEYNAME, R.DELETERULE, R.UPDATERULE "
                + "FROM SYSCAT.REFERENCES R WHERE R.TABSCHEMA = ? AND R.TABNAME = ? AND R.CONSTNAME = ? WITH UR";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, catalog(source.owner()));
            ps.setString(2, catalog(source.name()));
            ps.setString(3, catalog(identifierKey(expected.constraintName())));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String refSchema = rs.getString(1);
                String refTable = rs.getString(2);
                String refKeyName = rs.getString(3);
                String deleteRule = rs.getString(4);
                String updateRule = rs.getString(5);
                if (rs.next()) throw new SQLException("Duplicate SYSCAT.REFERENCES row for "
                        + source.owner() + "." + source.name() + "." + expected.constraintName());
                List<String> sourceColumns = loadConstraintColumns(connection,
                        source.owner(), source.name(), identifierKey(expected.constraintName()));
                List<String> referencedColumns = loadConstraintColumns(connection,
                        refSchema, refTable, refKeyName);
                return new CatalogFk(refSchema + "." + refTable, sourceColumns, referencedColumns,
                        normalizeRuleCode(deleteRule), normalizeRuleCode(updateRule), refKeyName);
            }
        }
    }

    private static List<String> loadConstraintColumns(Connection connection, String schema, String table, String constraint)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (var ps = connection.prepareStatement(
                "SELECT COLNAME FROM SYSCAT.KEYCOLUSE WHERE TABSCHEMA = ? AND TABNAME = ? "
                        + "AND CONSTNAME = ? ORDER BY COLSEQ WITH UR")) {
            ps.setString(1, catalog(schema));
            ps.setString(2, catalog(table));
            ps.setString(3, catalog(constraint));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) columns.add(identifierKey(rs.getString(1)));
            }
        }
        return List.copyOf(columns);
    }

    private static String compareCatalog(ParsedFk expected, CatalogFk actual, String defaultSchema) {
        List<String> differences = new ArrayList<>();
        if (!sameObject(expected.referencedTable(), actual.referencedTable(), defaultSchema)) {
            differences.add("REFERENCED_TABLE expected=" + expected.referencedTable()
                    + " actual=" + actual.referencedTable());
        }
        if (!identifierKeys(expected.sourceColumns()).equals(actual.sourceColumns())) {
            differences.add("SOURCE_COLUMNS expected=" + join(identifierKeys(expected.sourceColumns()))
                    + " actual=" + join(actual.sourceColumns()));
        }
        if (!identifierKeys(expected.referencedColumns()).equals(actual.referencedColumns())) {
            differences.add("REFERENCED_COLUMNS expected=" + join(identifierKeys(expected.referencedColumns()))
                    + " actual=" + join(actual.referencedColumns()));
        }
        String expectedDelete = expectedRuleCode(expected.tail(), ON_DELETE);
        String expectedUpdate = expectedRuleCode(expected.tail(), ON_UPDATE);
        if (!expectedDelete.equals(actual.deleteRule())) {
            differences.add("DELETE_RULE expected=" + expectedDelete + " actual=" + actual.deleteRule());
        }
        if (!expectedUpdate.equals(actual.updateRule())) {
            differences.add("UPDATE_RULE expected=" + expectedUpdate + " actual=" + actual.updateRule());
        }
        return String.join("; ", differences);
    }

    private static String expectedRuleCode(String tail, Pattern pattern) {
        Matcher matcher = pattern.matcher(stripSqlComments(tail));
        if (!matcher.find()) return "A";
        String rule = matcher.group(1).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        return switch (rule) {
            case "CASCADE" -> "C";
            case "SET NULL" -> "N";
            case "RESTRICT" -> "R";
            case "NO ACTION" -> "A";
            default -> throw new IllegalArgumentException("Unsupported DB2 referential action: " + rule);
        };
    }

    private static String normalizeRuleCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int countForeignKeys(Connection connection, String schema) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM SYSCAT.TABCONST WHERE TABSCHEMA = ? AND TYPE = 'F' WITH UR")) {
            ps.setString(1, catalog(schema));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void dropForeignKey(Connection connection, ParsedFk fk, String defaultSchema) throws SQLException {
        ObjectName source = ObjectName.parse(fk.sourceTable(), defaultSchema);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + source.sql() + " DROP FOREIGN KEY " + fk.constraintName());
        }
    }

    private static List<EvidenceRow> readEvidence() throws IOException {
        InputStream input = Db2LuwCatalogForeignKeyReconciliationP93IT.class.getClassLoader()
                .getResourceAsStream(P8_SUCCESS_RESOURCE);
        assertNotNull(input, "Missing retained P8 success evidence: " + P8_SUCCESS_RESOURCE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertNotNull(header, "Missing P8 success CSV header");
            List<String> names = parseCsvLine(header);
            List<EvidenceRow> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                assertEquals(names.size(), values.size(), "P8 success CSV width mismatch");
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) row.put(names.get(i), values.get(i));
                rows.add(new EvidenceRow(row.get("file"), row.get("statement"), row.get("source_table"),
                        row.get("constraint_name"), row.get("action"), row.get("original_referenced_table"),
                        row.get("original_referenced_columns"), row.get("resolved_referenced_table"),
                        row.get("resolved_referenced_columns"), row.get("evidence")));
            }
            assertFalse(rows.isEmpty(), "P8 success evidence is empty");
            return List.copyOf(rows);
        }
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
        assertFalse(quoted, "Unclosed CSV quote");
        return values;
    }

    private static List<String> parsePipeIdentifiers(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split("\\|")) result.add(normalizeName(part));
        return List.copyOf(result);
    }

    private static List<String> parseIdentifierList(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String normalized = normalizeName(part.trim());
            if (!normalized.isBlank()) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static List<String> identifierKeys(List<String> values) {
        return values.stream().map(Db2LuwCatalogForeignKeyReconciliationP93IT::identifierKey).toList();
    }

    private static String identifierKey(String value) {
        return unquote(value).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean sameObject(String left, String right, String defaultSchema) {
        ObjectName a = ObjectName.parse(left, defaultSchema);
        ObjectName b = ObjectName.parse(right, defaultSchema);
        return a.owner().equalsIgnoreCase(b.owner()) && a.name().equalsIgnoreCase(b.name());
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String unquote(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\"\"", "\"");
        }
        return v;
    }

    private static String catalog(String value) {
        return unquote(value).toUpperCase(Locale.ROOT);
    }

    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql;
        while (true) {
            String before = value;
            value = value.replaceFirst("(?s)^\\s*/\\*.*?\\*/\\s*", "");
            value = value.replaceFirst("(?s)^\\s*--[^\\r\\n]*(?:\\r?\\n|$)\\s*", "");
            if (value.equals(before)) return value.trim();
        }
    }

    private static String stripSqlComments(String sql) {
        if (sql == null || sql.isEmpty()) return "";
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\\r\\n]*", " ");
    }

    private static String join(List<String> values) { return String.join("|", values); }
    private static String oneLine(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    private static String csv(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static boolean connectionFailure(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("08");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + oneLine(current.getMessage());
    }

    private static void verifyExpectedDatabase(Connection connection, String expected) throws SQLException {
        if (expected == null || expected.isBlank()) return;
        String actual = scalar(connection, "VALUES CURRENT SERVER").trim();
        if (!actual.equalsIgnoreCase(expected.trim())) {
            throw new IllegalStateException("Connected to DB2 database " + actual + " but expected " + expected);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) return "";
            return Objects.toString(rs.getObject(1), "");
        }
    }

    private record EvidenceRow(String file, String statement, String sourceTable, String constraintName,
                               String action, String originalReferencedTable, String originalReferencedColumns,
                               String resolvedReferencedTable, String resolvedReferencedColumns, String evidence) { }

    private record ParsedFk(String sourceTable, String constraintName, List<String> sourceColumns,
                            String referencedTable, List<String> referencedColumns, String tail,
                            String sql, int statementIndex) {
        ParsedFk withReferencedTarget(String table, List<String> columns) {
            Matcher matcher = FOREIGN_KEY.matcher(sql);
            if (!matcher.find()) throw new IllegalArgumentException("Cannot rewrite FK SQL: " + oneLine(sql));
            String rewritten = sql.substring(0, matcher.start(4))
                    + table
                    + sql.substring(matcher.end(4), matcher.start(5))
                    + String.join(", ", columns)
                    + sql.substring(matcher.end(5));
            return new ParsedFk(sourceTable, constraintName, sourceColumns, normalizeName(table),
                    List.copyOf(columns), matcher.group(6), rewritten, statementIndex);
        }
    }

    private record CatalogFk(String referencedTable, List<String> sourceColumns, List<String> referencedColumns,
                             String deleteRule, String updateRule, String referencedKeyName) { }

    private record ObjectName(String owner, String name) {
        static ObjectName parse(String qualified, String defaultSchema) {
            String value = normalizeName(qualified);
            int dot = value.indexOf('.');
            if (dot < 0) return new ObjectName(identifierKey(defaultSchema), identifierKey(value));
            return new ObjectName(identifierKey(value.substring(0, dot)), identifierKey(value.substring(dot + 1)));
        }
        String sql() { return owner + "." + name; }
    }

    private record ResultRow(String file, String sourceTable, String constraintName, String action, String status,
                             String sourceColumns, String expectedReferencedTable, String expectedReferencedColumns,
                             String catalogReferencedTable, String catalogReferencedColumns, String referencedKeyName,
                             String expectedDeleteRule, String catalogDeleteRule, String expectedUpdateRule,
                             String catalogUpdateRule, String detail, int sqlCode, String sqlState, long elapsedMs) {
        static ResultRow exact(EvidenceRow row, ParsedFk expected, CatalogFk actual, String status) {
            return new ResultRow(row.file(), row.sourceTable(), row.constraintName(), row.action(), status,
                    join(identifierKeys(expected.sourceColumns())), expected.referencedTable(),
                    join(identifierKeys(expected.referencedColumns())), actual.referencedTable(),
                    join(actual.referencedColumns()), actual.referencedKeyName(),
                    expectedRuleCode(expected.tail(), ON_DELETE), actual.deleteRule(),
                    expectedRuleCode(expected.tail(), ON_UPDATE), actual.updateRule(), "", 0, "", 0);
        }
        static ResultRow mismatch(EvidenceRow row, ParsedFk expected, CatalogFk actual, String status, String detail) {
            ResultRow exact = exact(row, expected, actual, status);
            return new ResultRow(exact.file(), exact.sourceTable(), exact.constraintName(), exact.action(), status,
                    exact.sourceColumns(), exact.expectedReferencedTable(), exact.expectedReferencedColumns(),
                    exact.catalogReferencedTable(), exact.catalogReferencedColumns(), exact.referencedKeyName(),
                    exact.expectedDeleteRule(), exact.catalogDeleteRule(), exact.expectedUpdateRule(),
                    exact.catalogUpdateRule(), detail, 0, "", 0);
        }
        static ResultRow error(EvidenceRow row, String status, String detail) {
            return new ResultRow(row.file(), row.sourceTable(), row.constraintName(), row.action(), status,
                    "", row.resolvedReferencedTable(), row.resolvedReferencedColumns(), "", "", "",
                    "", "", "", "", detail, 0, "", 0);
        }
        static ResultRow sqlError(EvidenceRow row, ParsedFk expected, SQLException exception, long elapsedMs) {
            return new ResultRow(row.file(), row.sourceTable(), row.constraintName(), row.action(), "SQL_ERROR",
                    join(identifierKeys(expected.sourceColumns())), expected.referencedTable(),
                    join(identifierKeys(expected.referencedColumns())), "", "", "",
                    expectedRuleCode(expected.tail(), ON_DELETE), "",
                    expectedRuleCode(expected.tail(), ON_UPDATE), "",
                    oneLine(exception.getMessage()), exception.getErrorCode(), exception.getSQLState(), elapsedMs);
        }
    }

    private record CleanupRow(String file, String sourceTable, String constraintName,
                              int sqlCode, String sqlState, String message) { }

    private record Config(Path root, String url, String user, String password, String driver,
                          String expectedDatabase, String expectedSchema, int loginTimeoutSeconds,
                          int statementTimeoutSeconds, int progressEvery, boolean strictBaseline, Path reportBase) {
        static Config load() {
            String root = System.getProperty("schemaforge.db2luw.p9.sqlRoot", "").trim();
            return new Config(root.isEmpty() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.url", "jdbc:db2://127.0.0.1:50000/SFORGE"),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.user", "db2inst1"),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.password", "Schemaforge123"),
                    System.getProperty("schemaforge.db2luw.p9.jdbc.driver", "com.ibm.db2.jcc.DB2Driver"),
                    System.getProperty("schemaforge.db2luw.p9.expectedDatabase", "SFORGE"),
                    System.getProperty("schemaforge.db2luw.p9.expectedSchema", "TSTSHMA"),
                    Integer.getInteger("schemaforge.db2luw.p9.loginTimeoutSeconds", 15),
                    Integer.getInteger("schemaforge.db2luw.p9.statementTimeoutSeconds", 30),
                    Integer.getInteger("schemaforge.db2luw.p9.p93.progressEvery", 50),
                    Boolean.parseBoolean(System.getProperty("schemaforge.db2luw.p9.strictBaseline", "true")),
                    Path.of(System.getProperty("schemaforge.db2luw.p9.p93.reportBase",
                            "target/db2luw-p9.3-fk-catalog-reconciliation")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("P9.3 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank())
                throw new IllegalArgumentException("P9.3 expected schema required");
        }
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int expectedFkCount;
        private final List<ResultRow> rows = new ArrayList<>();
        private final List<CleanupRow> cleanupRows = new ArrayList<>();
        private final List<String> fatalMessages = new ArrayList<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String database = "";
        private String authorizationId = "";
        private int catalogFkBefore;
        private int catalogFkAfter;
        private int attemptedCreates;
        private int createdForValidation;
        private int preexistingExact;
        private int catalogExact;
        private int catalogMismatch;
        private int executionErrors;
        private int cleanupErrors;
        private Duration elapsed = Duration.ZERO;

        Report(Config config, Path reportDir, int expectedFkCount) {
            this.config = config;
            this.reportDir = reportDir;
            this.expectedFkCount = expectedFkCount;
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.3-fk-reconciliation.csv"), StandardCharsets.UTF_8)) {
                out.write("file,source_table,constraint_name,action,status,source_columns,expected_referenced_table,"
                        + "expected_referenced_columns,catalog_referenced_table,catalog_referenced_columns,"
                        + "referenced_key_name,expected_delete_rule,catalog_delete_rule,expected_update_rule,"
                        + "catalog_update_rule,detail,sqlcode,sqlstate,elapsed_ms\n");
                for (ResultRow row : rows) {
                    out.write(csv(row.file()) + "," + csv(row.sourceTable()) + "," + csv(row.constraintName()) + ","
                            + csv(row.action()) + "," + csv(row.status()) + "," + csv(row.sourceColumns()) + ","
                            + csv(row.expectedReferencedTable()) + "," + csv(row.expectedReferencedColumns()) + ","
                            + csv(row.catalogReferencedTable()) + "," + csv(row.catalogReferencedColumns()) + ","
                            + csv(row.referencedKeyName()) + "," + csv(row.expectedDeleteRule()) + ","
                            + csv(row.catalogDeleteRule()) + "," + csv(row.expectedUpdateRule()) + ","
                            + csv(row.catalogUpdateRule()) + "," + csv(row.detail()) + "," + row.sqlCode() + ","
                            + csv(row.sqlState()) + "," + row.elapsedMs() + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.3-cleanup-errors.csv"), StandardCharsets.UTF_8)) {
                out.write("file,source_table,constraint_name,sqlcode,sqlstate,message\n");
                for (CleanupRow row : cleanupRows) {
                    out.write(csv(row.file()) + "," + csv(row.sourceTable()) + "," + csv(row.constraintName()) + ","
                            + row.sqlCode() + "," + csv(row.sqlState()) + "," + csv(row.message()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("db2luw-p9.3-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            return "DB2 LUW P9.3 Catalog Reconciliation - Evidence-Valid Foreign Keys\n"
                    + "=================================================================\n"
                    + "Database product          : " + databaseProduct + "\n"
                    + "Database version          : " + databaseVersion + "\n"
                    + "Database                  : " + database.trim() + "\n"
                    + "Authorization ID          : " + authorizationId.trim() + "\n"
                    + "SQL root                  : " + config.root() + "\n"
                    + "Expected P8-success FKs   : " + expectedFkCount + "\n"
                    + "Catalog FK count before   : " + catalogFkBefore + "\n"
                    + "Create attempts           : " + attemptedCreates + "\n"
                    + "Created for validation    : " + createdForValidation + "\n"
                    + "Pre-existing exact        : " + preexistingExact + "\n"
                    + "Catalog exact             : " + catalogExact + "\n"
                    + "Catalog mismatch          : " + catalogMismatch + "\n"
                    + "Execution errors          : " + executionErrors + "\n"
                    + "Cleanup errors            : " + cleanupErrors + "\n"
                    + "Catalog FK count after    : " + catalogFkAfter + "\n"
                    + "Persistent state preserved: " + (catalogFkBefore == catalogFkAfter) + "\n"
                    + "Validation policy         : PREEXISTING=READ ONLY; ABSENT=CREATE/VERIFY/DROP\n"
                    + "Evidence policy           : EXACT P8 SUCCESS SET ONLY (310); 247 BLOCKED FKs EXCLUDED\n"
                    + "Elapsed                   : " + elapsed + "\n"
                    + "Report directory          : " + reportDir.toAbsolutePath() + "\n"
                    + (fatalMessages.isEmpty() ? "" : "Fatal messages            : " + String.join(" | ", fatalMessages) + "\n");
        }

        void printSummary() { System.out.println(summary()); }
    }
}
