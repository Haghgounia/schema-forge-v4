package com.behsazan.schemaforge.integration;

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
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live Oracle validation of the foreign-key statements intentionally skipped by
 * {@link OracleSqlDirectoryExecutionTest} in HISTORICAL mode.
 *
 * <p>The historical replay leaves the final encountered definition of each table
 * in the disposable schema. This test mirrors that final-state rule: for duplicate
 * historical documents it validates only the FK statements belonging to the final
 * CREATE TABLE definition. It never guesses a historical winner beyond the exact
 * deterministic file order already used by the directory replay.</p>
 *
 * <p>Each successfully created FK is dropped immediately after validation so this
 * test does not permanently change the replay schema.</p>
 */
class OracleForeignKeyDirectoryExecutionIT {

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String IDENT = "(?:\\\"[^\\\"]+\\\"|[A-Z0-9_$#]+)";
    private static final String QNAME = IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+(" + QNAME + ")");

    private static final Pattern FK = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QNAME + ")\\s+ADD\\s+CONSTRAINT\\s+(" + IDENT + ")"
                    + "\\s+FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s+REFERENCES\\s+(" + QNAME + ")\\s*\\(([^)]*)\\)");

    @Test
    void validateFinalStateForeignKeysAgainstLiveOracle() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set oracle.fk.sql.root (or oracle.sql.root), oracle.jdbc.url and oracle.jdbc.user to run this test.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) {
            fail("No Oracle SQL files found below " + config.root() + " with suffix " + config.fileSuffix());
        }

        Map<String, TablePlan> finalPlans = loadFinalPlans(files);
        List<TablePlan> plans = finalPlans.values().stream()
                .filter(plan -> !plan.foreignKeys().isEmpty())
                .toList();

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Report report = new Report(config, reportDir, files.size(), finalPlans.size());
        Instant started = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName("oracle.jdbc.OracleDriver");
            try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                report.readDatabaseInfo(connection);
                verifySchema(connection, config.expectedSchema());

                int tableSequence = 0;
                for (TablePlan plan : plans) {
                    tableSequence++;
                    validatePlan(connection, config, report, plan);
                    if (tableSequence % config.progressEveryTables() == 0 || tableSequence == plans.size()) {
                        System.out.printf(Locale.ROOT,
                                "Oracle FK tables: %,d / %,d, attempted=%,d, succeeded=%,d, errors=%,d, structural-blocked=%,d, dependency-skipped=%,d%n",
                                tableSequence, plans.size(), report.attempted, report.succeeded,
                                report.failed, report.structuralBlocked, report.dependencySkipped);
                    }
                }
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
        if (config.failOnErrors() && report.failed > 0) {
            fail("Oracle FK validation completed with " + report.failed
                    + " live execution errors. Report: " + reportDir);
        }
        if (config.failOnBlockers() && report.structuralBlocked > 0) {
            fail("Oracle FK validation completed with " + report.structuralBlocked
                    + " structural FK blockers. Report: " + reportDir);
        }
    }

    private static Map<String, TablePlan> loadFinalPlans(List<Path> files) throws IOException {
        Map<String, TablePlan> finalPlans = new LinkedHashMap<>();
        OracleSqlDirectoryExecutionTest.OracleStatementSplitter splitter =
                new OracleSqlDirectoryExecutionTest.OracleStatementSplitter();

        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            List<OracleSqlDirectoryExecutionTest.SqlUnit> units = splitter.split(script);
            String createdTable = null;
            List<ForeignKeyUnit> foreignKeys = new ArrayList<>();

            for (OracleSqlDirectoryExecutionTest.SqlUnit unit : units) {
                String sql = stripLeadingComments(unit.sql());
                Matcher create = CREATE_TABLE.matcher(sql);
                if (create.find()) {
                    createdTable = normalizeName(create.group(1));
                }
                Matcher fk = FK.matcher(sql);
                if (fk.find()) {
                    foreignKeys.add(new ForeignKeyUnit(
                            normalizeName(fk.group(1)),
                            normalizeName(fk.group(2)),
                            parseIdentifierList(fk.group(3)),
                            normalizeName(fk.group(4)),
                            parseIdentifierList(fk.group(5)),
                            unit.sql(),
                            unit.startLine(),
                            file));
                }
            }

            if (createdTable != null) {
                // Replaces an older historical version, including the case where the
                // final definition has no foreign keys at all.
                String finalCreatedTable = createdTable;
                List<ForeignKeyUnit> owned = foreignKeys.stream()
                        .filter(fk -> sameObject(fk.sourceTable(), finalCreatedTable))
                        .toList();
                finalPlans.put(canonicalObjectKey(finalCreatedTable),
                        new TablePlan(finalCreatedTable, file, List.copyOf(owned)));
            }
        }
        return finalPlans;
    }

    private static void validatePlan(
            Connection connection, Config config, Report report, TablePlan plan) throws SQLException {
        for (ForeignKeyUnit fk : plan.foreignKeys()) {
            if (!ownedByExpectedSchema(fk.sourceTable(), config.expectedSchema())
                    || !ownedByExpectedSchema(fk.referencedTable(), config.expectedSchema())) {
                report.dependencySkipped++;
                report.skips.add(new SkipRow(relative(config.root(), fk.file()), fk.startLine(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "OUTSIDE_EXPECTED_SCHEMA"));
                continue;
            }
            if (!tableExists(connection, fk.sourceTable(), config.expectedSchema())) {
                report.dependencySkipped++;
                report.skips.add(new SkipRow(relative(config.root(), fk.file()), fk.startLine(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "SOURCE_TABLE_NOT_FOUND"));
                continue;
            }
            if (!tableExists(connection, fk.referencedTable(), config.expectedSchema())) {
                report.dependencySkipped++;
                report.skips.add(new SkipRow(relative(config.root(), fk.file()), fk.startLine(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "REFERENCED_TABLE_NOT_FOUND"));
                continue;
            }

            List<String> missingSource = missingColumns(
                    connection, fk.sourceTable(), fk.sourceColumns(), config.expectedSchema());
            if (!missingSource.isEmpty()) {
                report.structuralBlocked++;
                report.blockers.add(new BlockerRow(relative(config.root(), fk.file()), fk.startLine(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "SOURCE_COLUMN_NOT_FOUND", String.join("|", missingSource), oneLine(fk.sql())));
                continue;
            }

            List<String> missingReferenced = missingColumns(
                    connection, fk.referencedTable(), fk.referencedColumns(), config.expectedSchema());
            if (!missingReferenced.isEmpty()) {
                report.structuralBlocked++;
                report.blockers.add(new BlockerRow(relative(config.root(), fk.file()), fk.startLine(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "REFERENCED_COLUMN_NOT_FOUND", String.join("|", missingReferenced), oneLine(fk.sql())));
                continue;
            }

            if (!referencedColumnsAreUniqueKey(
                    connection, fk.referencedTable(), fk.referencedColumns(), config.expectedSchema())) {
                report.structuralBlocked++;
                report.blockers.add(new BlockerRow(relative(config.root(), fk.file()), fk.startLine(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "REFERENCED_COLUMNS_NOT_PK_OR_UNIQUE", "", oneLine(fk.sql())));
                continue;
            }

            dropConstraintIfPresent(connection, fk.sourceTable(), fk.constraintName());
            report.attempted++;
            Instant started = Instant.now();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(config.statementTimeoutSeconds());
                statement.execute(fk.sql());
                report.succeeded++;
            } catch (SQLException exception) {
                report.failed++;
                report.errors.add(new ErrorRow(
                        relative(config.root(), fk.file()), fk.startLine(), fk.sourceTable(),
                        fk.constraintName(), joinIdentifiers(fk.sourceColumns()), fk.referencedTable(),
                        joinIdentifiers(fk.referencedColumns()), exception.getErrorCode(),
                        exception.getSQLState(), oneLine(exception.getMessage()),
                        Duration.between(started, Instant.now()).toMillis(), oneLine(fk.sql())));
                if (connectionFailure(exception)) {
                    throw new SQLRecoverableException(
                            "Oracle connection failed while validating FK from " + fk.file(),
                            exception.getSQLState(), exception.getErrorCode(), exception);
                }
            } finally {
                try {
                    dropConstraintIfPresent(connection, fk.sourceTable(), fk.constraintName());
                } catch (SQLException cleanup) {
                    report.cleanupFailed++;
                    if (connectionFailure(cleanup)) throw cleanup;
                }
            }
        }
    }

    private static List<String> missingColumns(
            Connection connection, String qualifiedTable, List<String> columns, String expectedSchema)
            throws SQLException {
        ObjectName table = ObjectName.parse(qualifiedTable, expectedSchema);
        Set<String> existing = new java.util.LinkedHashSet<>();
        try (var statement = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ?")) {
            statement.setString(1, table.owner().toUpperCase(Locale.ROOT));
            statement.setString(2, table.name().toUpperCase(Locale.ROOT));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) existing.add(rs.getString(1).toUpperCase(Locale.ROOT));
            }
        }
        return columns.stream()
                .map(OracleForeignKeyDirectoryExecutionIT::unquote)
                .filter(column -> !existing.contains(column.toUpperCase(Locale.ROOT)))
                .toList();
    }

    private static boolean referencedColumnsAreUniqueKey(
            Connection connection, String qualifiedTable, List<String> columns, String expectedSchema)
            throws SQLException {
        ObjectName table = ObjectName.parse(qualifiedTable, expectedSchema);
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(
                "SELECT c.CONSTRAINT_NAME, cc.COLUMN_NAME "
                        + "FROM ALL_CONSTRAINTS c "
                        + "JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER "
                        + "AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME "
                        + "AND cc.TABLE_NAME = c.TABLE_NAME "
                        + "WHERE c.OWNER = ? AND c.TABLE_NAME = ? "
                        + "AND c.CONSTRAINT_TYPE IN ('P','U') AND c.STATUS = 'ENABLED' "
                        + "ORDER BY c.CONSTRAINT_NAME, cc.POSITION")) {
            statement.setString(1, table.owner().toUpperCase(Locale.ROOT));
            statement.setString(2, table.name().toUpperCase(Locale.ROOT));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    constraints.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                            .add(rs.getString(2).toUpperCase(Locale.ROOT));
                }
            }
        }
        List<String> expected = columns.stream()
                .map(OracleForeignKeyDirectoryExecutionIT::unquote)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList();
        return constraints.values().stream().anyMatch(expected::equals);
    }

    private static List<String> parseIdentifierList(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                token.append(ch);
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

    private static String joinIdentifiers(List<String> values) {
        return String.join("|", values);
    }

    private static boolean tableExists(Connection connection, String qualified, String expectedSchema)
            throws SQLException {
        ObjectName name = ObjectName.parse(qualified, expectedSchema);
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?")) {
            statement.setString(1, name.owner().toUpperCase(Locale.ROOT));
            statement.setString(2, name.name().toUpperCase(Locale.ROOT));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void dropConstraintIfPresent(Connection connection, String table, String constraint)
            throws SQLException {
        String sql = "ALTER TABLE " + table + " DROP CONSTRAINT " + constraint;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            // ORA-02443: Cannot drop constraint - nonexistent constraint.
            if (Math.abs(exception.getErrorCode()) != 2443) throw exception;
        }
    }

    private static void verifySchema(Connection connection, String expected) throws SQLException {
        if (expected.isBlank()) return;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual")) {
            String actual = result.next() ? result.getString(1) : "";
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("Expected schema " + expected + " but connected schema is " + actual);
            }
        }
    }

    private static List<Path> findSqlFiles(Path root, String suffix, int maxFiles) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            Stream<Path> selected = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString(),
                            String.CASE_INSENSITIVE_ORDER));
            if (maxFiles > 0) selected = selected.limit(maxFiles);
            return selected.toList();
        }
    }

    private static boolean connectionFailure(SQLException exception) {
        int code = Math.abs(exception.getErrorCode());
        return exception instanceof SQLRecoverableException
                || Set.of(28, 1012, 3113, 3114, 3135, 12514, 12541, 17002, 17410).contains(code);
    }

    private static boolean ownedByExpectedSchema(String object, String expectedSchema) {
        if (expectedSchema == null || expectedSchema.isBlank()) return true;
        int separator = object.indexOf('.');
        if (separator < 0) return true;
        String owner = unquote(object.substring(0, separator));
        return owner.equalsIgnoreCase(expectedSchema);
    }

    private static boolean sameObject(String first, String second) {
        return canonicalObjectKey(first).equals(canonicalObjectKey(second));
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

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName()
                + (current.getMessage() == null ? "" : ": " + current.getMessage());
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private record ObjectName(String owner, String name) {
        static ObjectName parse(String qualified, String defaultOwner) {
            String normalized = normalizeName(qualified);
            int separator = normalized.indexOf('.');
            if (separator < 0) return new ObjectName(defaultOwner, unquote(normalized));
            return new ObjectName(unquote(normalized.substring(0, separator)),
                    unquote(normalized.substring(separator + 1)));
        }
    }

    private record ForeignKeyUnit(
            String sourceTable, String constraintName, List<String> sourceColumns,
            String referencedTable, List<String> referencedColumns,
            String sql, int startLine, Path file) {
    }

    private record TablePlan(String table, Path file, List<ForeignKeyUnit> foreignKeys) {
    }

    private record ErrorRow(
            String file, int line, String sourceTable, String constraintName, String sourceColumns,
            String referencedTable, String referencedColumns, int oracleCode, String sqlState, String message,
            long elapsedMs, String sql) {
    }

    private record BlockerRow(
            String file, int line, String sourceTable, String constraintName, String sourceColumns,
            String referencedTable, String referencedColumns, String reason, String detail, String sql) {
    }

    private record SkipRow(
            String file, int line, String sourceTable, String constraintName, String sourceColumns,
            String referencedTable, String referencedColumns, String reason) {
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int filesDiscovered;
        private final int finalTables;
        private final List<ErrorRow> errors = new ArrayList<>();
        private final List<BlockerRow> blockers = new ArrayList<>();
        private final List<SkipRow> skips = new ArrayList<>();
        private final List<String> fatalMessages = new ArrayList<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private int attempted;
        private int succeeded;
        private int failed;
        private int structuralBlocked;
        private int dependencySkipped;
        private int cleanupFailed;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, int finalTables) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.finalTables = finalTables;
        }

        void readDatabaseInfo(Connection connection) throws SQLException {
            databaseProduct = connection.getMetaData().getDatabaseProductName();
            databaseVersion = connection.getMetaData().getDatabaseProductVersion();
        }

        void write() throws IOException {
            writeErrors();
            writeBlockers();
            writeSkips();
            Files.writeString(reportDir.resolve("oracle-fk-validation-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        private void writeErrors() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("oracle-fk-validation-errors.csv"), StandardCharsets.UTF_8)) {
                out.write("file,line,source_table,constraint_name,source_columns,referenced_table,referenced_columns,oracle_code,sql_state,message,elapsed_ms,sql\n");
                for (ErrorRow row : errors) {
                    out.write(String.join(",",
                            csv(row.file()), Integer.toString(row.line()), csv(row.sourceTable()),
                            csv(row.constraintName()), csv(row.sourceColumns()), csv(row.referencedTable()),
                            csv(row.referencedColumns()), Integer.toString(row.oracleCode()),
                            csv(row.sqlState()), csv(row.message()), Long.toString(row.elapsedMs()), csv(row.sql())));
                    out.newLine();
                }
            }
        }

        private void writeBlockers() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("oracle-fk-validation-blockers.csv"), StandardCharsets.UTF_8)) {
                out.write("file,line,source_table,constraint_name,source_columns,referenced_table,referenced_columns,reason,detail,sql\n");
                for (BlockerRow row : blockers) {
                    out.write(String.join(",",
                            csv(row.file()), Integer.toString(row.line()), csv(row.sourceTable()),
                            csv(row.constraintName()), csv(row.sourceColumns()), csv(row.referencedTable()),
                            csv(row.referencedColumns()), csv(row.reason()), csv(row.detail()), csv(row.sql())));
                    out.newLine();
                }
            }
        }

        private void writeSkips() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("oracle-fk-validation-skipped.csv"), StandardCharsets.UTF_8)) {
                out.write("file,line,source_table,constraint_name,source_columns,referenced_table,referenced_columns,reason\n");
                for (SkipRow row : skips) {
                    out.write(String.join(",",
                            csv(row.file()), Integer.toString(row.line()), csv(row.sourceTable()),
                            csv(row.constraintName()), csv(row.sourceColumns()), csv(row.referencedTable()),
                            csv(row.referencedColumns()), csv(row.reason())));
                    out.newLine();
                }
            }
        }

        private String summary() {
            return "Oracle FK live validation summary\n"
                    + "=================================\n"
                    + "Database             : " + databaseProduct + "\n"
                    + "Database version     : " + databaseVersion + "\n"
                    + "Root directory       : " + config.root() + "\n"
                    + "File suffix          : " + config.fileSuffix() + "\n"
                    + "Files discovered     : " + filesDiscovered + "\n"
                    + "Final table defs     : " + finalTables + "\n"
                    + "FK attempted         : " + attempted + "\n"
                    + "FK succeeded         : " + succeeded + "\n"
                    + "FK failed            : " + failed + "\n"
                    + "Structural blocked   : " + structuralBlocked + "\n"
                    + "Dependency skipped   : " + dependencySkipped + "\n"
                    + "Cleanup failed       : " + cleanupFailed + "\n"
                    + "Elapsed              : " + elapsed + "\n"
                    + (fatalMessages.isEmpty() ? "" : "Fatal                 : " + String.join(" | ", fatalMessages) + "\n");
        }

        void printSummary() {
            System.out.println("============================================================");
            System.out.printf(Locale.ROOT, "Files discovered   : %d%n", filesDiscovered);
            System.out.printf(Locale.ROOT, "Final table defs   : %d%n", finalTables);
            System.out.printf(Locale.ROOT, "FK attempted       : %d%n", attempted);
            System.out.printf(Locale.ROOT, "FK succeeded       : %d%n", succeeded);
            System.out.printf(Locale.ROOT, "FK failed          : %d%n", failed);
            System.out.printf(Locale.ROOT, "Structural blocked : %d%n", structuralBlocked);
            System.out.printf(Locale.ROOT, "Dependency skipped : %d%n", dependencySkipped);
            System.out.printf(Locale.ROOT, "Cleanup failed     : %d%n", cleanupFailed);
            System.out.printf(Locale.ROOT, "Elapsed            : %s%n", elapsed);
            System.out.printf(Locale.ROOT, "Reports            : %s%n", reportDir);
            System.out.println("============================================================");
        }
    }

    private record Config(
            Path root, String fileSuffix, String url, String user, String password,
            String expectedSchema, Path reportBase, int maxFiles, int progressEveryTables,
            int loginTimeoutSeconds, int statementTimeoutSeconds, boolean failOnErrors,
            boolean failOnBlockers) {

        static Config load() {
            Path root = path(first("oracle.fk.sql.root", "oracle.sql.root"));
            String fileSuffix = value("oracle.fk.sql.fileSuffix",
                    value("oracle.sql.fileSuffix", ".oracle.sql"));
            String url = first("oracle.fk.jdbc.url", "oracle.jdbc.url");
            String user = first("oracle.fk.jdbc.user", "oracle.jdbc.user");
            String password = first("oracle.fk.jdbc.password", "oracle.jdbc.password");
            String expectedSchema = value("oracle.fk.expectedSchema",
                    value("oracle.sql.expectedSchema", user == null ? "" : user));
            Path reportBase = path(value("oracle.fk.reportDir",
                    "target/oracle-fk-validation-report"));
            return new Config(root, fileSuffix, url, user, password, expectedSchema, reportBase,
                    integer("oracle.fk.maxFiles", 0), integer("oracle.fk.progressEveryTables", 100),
                    integer("oracle.fk.loginTimeoutSeconds", 20),
                    integer("oracle.fk.statementTimeoutSeconds", 60),
                    bool("oracle.fk.failOnErrors", true),
                    bool("oracle.fk.failOnBlockers", false));
        }

        boolean enabled() {
            return root != null && url != null && !url.isBlank() && user != null && !user.isBlank();
        }

        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Oracle FK SQL root not found: " + root);
            if (fileSuffix == null || fileSuffix.isBlank()) throw new IllegalArgumentException("Oracle FK file suffix is blank");
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("oracle.fk.expectedSchema must not be blank");
        }

        private static String first(String... names) {
            for (String name : names) {
                String value = System.getProperty(name);
                if (value != null && !value.isBlank()) return value.trim();
            }
            return null;
        }

        private static String value(String name, String fallback) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static int integer(String name, int fallback) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        }

        private static boolean bool(String name, boolean fallback) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
        }

        private static Path path(String value) {
            return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
        }
    }
}
