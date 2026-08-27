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
import java.sql.SQLRecoverableException;
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

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live Db2 LUW validation of the foreign-key statements intentionally skipped by
 * {@link Db2LuwDirectoryExecutionTest} in HISTORICAL mode.
 *
 * <p>The historical replay leaves the final encountered definition of each table in the
 * disposable database. This test mirrors that exact deterministic file-order rule: when several
 * historical documents define the same table, only the foreign keys belonging to the final table
 * definition are considered. It does not pretend that every historical revision forms one
 * coherent schema.</p>
 *
 * <p>Before executing a foreign key, Db2 catalog preflight classifies missing tables, missing
 * columns, and missing parent PK/UNIQUE evidence as corpus/model blockers rather than dialect SQL
 * failures. Every successfully created FK is dropped immediately after validation so repeated runs
 * are isolated.</p>
 */
class Db2LuwForeignKeyDirectoryExecutionIT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")"
                    + "\\s+ADD\\s+CONSTRAINT\\s+(" + IDENTIFIER + ")"
                    + "\\s+FOREIGN\\s+KEY\\s*\\(([^)]*)\\)"
                    + "\\s+REFERENCES\\s+(" + QUALIFIED_NAME + ")\\s*\\(([^)]*)\\)");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void validateFinalStateForeignKeysAgainstLiveDb2Luw() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set db2luw.fk.sql.root (or db2luw.sql.root), db2luw.fk.jdbc.url (or db2luw.jdbc.url), "
                        + "and db2luw.fk.jdbc.user (or db2luw.jdbc.user) to run this test.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) {
            fail("No Db2 LUW SQL files found below " + config.root() + " with suffix " + config.fileSuffix());
        }

        Map<String, TablePlan> finalPlans = loadFinalPlans(files);
        List<TablePlan> plans = finalPlans.values().stream()
                .filter(plan -> !plan.foreignKeys().isEmpty())
                .toList();
        int finalForeignKeys = plans.stream().mapToInt(plan -> plan.foreignKeys().size()).sum();

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Report report = new Report(config, reportDir, files.size(), finalPlans.size(), finalForeignKeys);
        Instant started = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName(config.driver());
            try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                verifyExpectedDatabase(connection, config.expectedDatabase());
                report.readDatabaseInfo(connection);

                int tableSequence = 0;
                for (TablePlan plan : plans) {
                    tableSequence++;
                    validatePlan(connection, config, report, plan);
                    if (tableSequence % config.progressEveryTables() == 0 || tableSequence == plans.size()) {
                        System.out.printf(Locale.ROOT,
                                "Db2 LUW FK tables: %,d / %,d, candidates=%,d, attempted=%,d, succeeded=%,d, "
                                        + "errors=%,d, structural-blocked=%,d, dependency-skipped=%,d%n",
                                tableSequence, plans.size(), finalForeignKeys, report.attempted, report.succeeded,
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
            fail("Db2 LUW FK validation completed with " + report.failed
                    + " live execution errors. Report: " + reportDir);
        }
        if (config.failOnBlockers() && report.structuralBlocked > 0) {
            fail("Db2 LUW FK validation completed with " + report.structuralBlocked
                    + " structural FK blockers. Report: " + reportDir);
        }
    }

    private Map<String, TablePlan> loadFinalPlans(List<Path> files) throws IOException {
        Map<String, TablePlan> finalPlans = new LinkedHashMap<>();
        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            List<String> statements = splitter.parse(script, DatabasePlatform.DB2_LUW);
            String createdTable = null;
            List<ForeignKeyUnit> foreignKeys = new ArrayList<>();

            int statementIndex = 0;
            for (String raw : statements) {
                statementIndex++;
                String sql = stripLeadingComments(raw);
                Matcher create = CREATE_TABLE.matcher(sql);
                if (create.find()) {
                    createdTable = normalizeName(create.group(1));
                }
                Matcher fk = FOREIGN_KEY.matcher(sql);
                if (fk.find()) {
                    foreignKeys.add(new ForeignKeyUnit(
                            normalizeName(fk.group(1)),
                            normalizeName(fk.group(2)),
                            parseIdentifierList(fk.group(3)),
                            normalizeName(fk.group(4)),
                            parseIdentifierList(fk.group(5)),
                            raw,
                            statementIndex,
                            file));
                }
            }

            if (createdTable != null) {
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
                report.skips.add(new SkipRow(relative(config.root(), fk.file()), fk.statement(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "OUTSIDE_EXPECTED_SCHEMA"));
                continue;
            }
            if (!tableExists(connection, fk.sourceTable(), config.expectedSchema())) {
                report.dependencySkipped++;
                report.skips.add(new SkipRow(relative(config.root(), fk.file()), fk.statement(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "SOURCE_TABLE_NOT_FOUND"));
                continue;
            }
            if (!tableExists(connection, fk.referencedTable(), config.expectedSchema())) {
                report.dependencySkipped++;
                report.skips.add(new SkipRow(relative(config.root(), fk.file()), fk.statement(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "REFERENCED_TABLE_NOT_FOUND"));
                continue;
            }

            List<String> missingSource = missingColumns(
                    connection, fk.sourceTable(), fk.sourceColumns(), config.expectedSchema());
            if (!missingSource.isEmpty()) {
                report.structuralBlocked++;
                report.blockers.add(new BlockerRow(relative(config.root(), fk.file()), fk.statement(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "SOURCE_COLUMN_NOT_FOUND", String.join("|", missingSource), oneLine(fk.sql())));
                continue;
            }

            List<String> missingReferenced = missingColumns(
                    connection, fk.referencedTable(), fk.referencedColumns(), config.expectedSchema());
            if (!missingReferenced.isEmpty()) {
                report.structuralBlocked++;
                report.blockers.add(new BlockerRow(relative(config.root(), fk.file()), fk.statement(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "REFERENCED_COLUMN_NOT_FOUND", String.join("|", missingReferenced), oneLine(fk.sql())));
                continue;
            }

            if (!referencedColumnsAreUniqueKey(
                    connection, fk.referencedTable(), fk.referencedColumns(), config.expectedSchema())) {
                report.structuralBlocked++;
                report.blockers.add(new BlockerRow(relative(config.root(), fk.file()), fk.statement(),
                        fk.sourceTable(), fk.constraintName(), joinIdentifiers(fk.sourceColumns()),
                        fk.referencedTable(), joinIdentifiers(fk.referencedColumns()),
                        "REFERENCED_COLUMNS_NOT_PK_OR_UNIQUE", "", oneLine(fk.sql())));
                continue;
            }

            dropForeignKeyIfPresent(connection, fk.sourceTable(), fk.constraintName(), config.expectedSchema());
            report.attempted++;
            Instant started = Instant.now();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(config.statementTimeoutSeconds());
                statement.execute(fk.sql());
                report.succeeded++;
            } catch (SQLException exception) {
                report.failed++;
                report.errors.add(new ErrorRow(
                        relative(config.root(), fk.file()), fk.statement(), fk.sourceTable(),
                        fk.constraintName(), joinIdentifiers(fk.sourceColumns()), fk.referencedTable(),
                        joinIdentifiers(fk.referencedColumns()), exception.getErrorCode(),
                        exception.getSQLState(), oneLine(exception.getMessage()),
                        Duration.between(started, Instant.now()).toMillis(), oneLine(fk.sql())));
                if (connectionFailure(exception)) {
                    throw new SQLRecoverableException(
                            "Db2 LUW connection failed while validating FK from " + fk.file(),
                            exception.getSQLState(), exception.getErrorCode(), exception);
                }
            } finally {
                try {
                    dropForeignKeyIfPresent(connection, fk.sourceTable(), fk.constraintName(), config.expectedSchema());
                } catch (SQLException cleanup) {
                    report.cleanupFailed++;
                    report.cleanupErrors.add(new CleanupRow(
                            relative(config.root(), fk.file()), fk.statement(), fk.sourceTable(),
                            fk.constraintName(), cleanup.getErrorCode(), cleanup.getSQLState(),
                            oneLine(cleanup.getMessage())));
                    if (connectionFailure(cleanup)) throw cleanup;
                }
            }
        }
    }

    private static boolean tableExists(Connection connection, String qualified, String expectedSchema)
            throws SQLException {
        ObjectName name = ObjectName.parse(qualified, expectedSchema);
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM SYSCAT.TABLES WHERE TABSCHEMA = ? AND TABNAME = ? AND TYPE IN ('T','U') WITH UR")) {
            statement.setString(1, catalog(name.owner()));
            statement.setString(2, catalog(name.name()));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static List<String> missingColumns(
            Connection connection, String qualifiedTable, List<String> columns, String expectedSchema)
            throws SQLException {
        ObjectName table = ObjectName.parse(qualifiedTable, expectedSchema);
        Set<String> existing = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement(
                "SELECT COLNAME FROM SYSCAT.COLUMNS WHERE TABSCHEMA = ? AND TABNAME = ? WITH UR")) {
            statement.setString(1, catalog(table.owner()));
            statement.setString(2, catalog(table.name()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) existing.add(rs.getString(1).toUpperCase(Locale.ROOT));
            }
        }
        return columns.stream()
                .map(Db2LuwForeignKeyDirectoryExecutionIT::unquote)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(column -> !existing.contains(column))
                .toList();
    }

    private static boolean referencedColumnsAreUniqueKey(
            Connection connection, String qualifiedTable, List<String> columns, String expectedSchema)
            throws SQLException {
        ObjectName table = ObjectName.parse(qualifiedTable, expectedSchema);
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(
                "SELECT C.CONSTNAME, K.COLNAME "
                        + "FROM SYSCAT.TABCONST C "
                        + "JOIN SYSCAT.KEYCOLUSE K ON K.TABSCHEMA = C.TABSCHEMA "
                        + "AND K.TABNAME = C.TABNAME AND K.CONSTNAME = C.CONSTNAME "
                        + "WHERE C.TABSCHEMA = ? AND C.TABNAME = ? AND C.TYPE IN ('P','U') "
                        + "ORDER BY C.CONSTNAME, K.COLSEQ WITH UR")) {
            statement.setString(1, catalog(table.owner()));
            statement.setString(2, catalog(table.name()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    constraints.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                            .add(rs.getString(2).toUpperCase(Locale.ROOT));
                }
            }
        }
        List<String> expected = columns.stream()
                .map(Db2LuwForeignKeyDirectoryExecutionIT::unquote)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList();
        return constraints.values().stream().anyMatch(expected::equals);
    }

    private static void dropForeignKeyIfPresent(
            Connection connection, String qualifiedTable, String constraint, String expectedSchema)
            throws SQLException {
        ObjectName table = ObjectName.parse(qualifiedTable, expectedSchema);
        String constraintName = catalog(unquote(constraint));
        boolean exists;
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM SYSCAT.REFERENCES WHERE TABSCHEMA = ? AND TABNAME = ? AND CONSTNAME = ? WITH UR")) {
            statement.setString(1, catalog(table.owner()));
            statement.setString(2, catalog(table.name()));
            statement.setString(3, constraintName);
            try (ResultSet rs = statement.executeQuery()) {
                exists = rs.next();
            }
        }
        if (!exists) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + qualifiedTable + " DROP FOREIGN KEY " + constraint);
        }
    }


    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Query returned no rows: " + sql);
            return result.getString(1);
        }
    }

    private static void verifyExpectedDatabase(Connection connection, String expected) throws SQLException {
        if (expected == null || expected.isBlank()) return;
        String actual;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("VALUES CURRENT SERVER")) {
            actual = result.next() ? result.getString(1) : "";
        }
        if (!expected.equalsIgnoreCase(actual.trim())) {
            throw new IllegalStateException("Refusing Db2 LUW FK validation: expected database "
                    + expected + " but connected to " + actual);
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

    private static boolean ownedByExpectedSchema(String object, String expectedSchema) {
        if (expectedSchema == null || expectedSchema.isBlank()) return true;
        ObjectName name = ObjectName.parse(object, expectedSchema);
        return name.owner().equalsIgnoreCase(expectedSchema);
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

    private static String catalog(String value) {
        return unquote(value).toUpperCase(Locale.ROOT);
    }

    private static String joinIdentifiers(List<String> values) {
        return String.join("|", values);
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

    private static boolean connectionFailure(SQLException exception) {
        if (exception instanceof SQLRecoverableException) return true;
        String state = exception.getSQLState();
        return state != null && state.startsWith("08");
    }

    private static String relative(Path root, Path file) {
        return normalize(root.relativize(file));
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName()
                + (current.getMessage() == null ? "" : ": " + oneLine(current.getMessage()));
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
            if (separator < 0) {
                if (defaultOwner == null || defaultOwner.isBlank()) {
                    throw new IllegalArgumentException("Unqualified Db2 object requires db2luw.fk.expectedSchema: "
                            + qualified);
                }
                return new ObjectName(unquote(defaultOwner), unquote(normalized));
            }
            return new ObjectName(unquote(normalized.substring(0, separator)),
                    unquote(normalized.substring(separator + 1)));
        }
    }

    private record ForeignKeyUnit(
            String sourceTable, String constraintName, List<String> sourceColumns,
            String referencedTable, List<String> referencedColumns,
            String sql, int statement, Path file) {
    }

    private record TablePlan(String table, Path file, List<ForeignKeyUnit> foreignKeys) {
    }

    private record ErrorRow(
            String file, int statement, String sourceTable, String constraintName, String sourceColumns,
            String referencedTable, String referencedColumns, int sqlCode, String sqlState, String message,
            long elapsedMs, String sql) {
    }

    private record BlockerRow(
            String file, int statement, String sourceTable, String constraintName, String sourceColumns,
            String referencedTable, String referencedColumns, String reason, String detail, String sql) {
    }

    private record SkipRow(
            String file, int statement, String sourceTable, String constraintName, String sourceColumns,
            String referencedTable, String referencedColumns, String reason) {
    }

    private record CleanupRow(
            String file, int statement, String sourceTable, String constraintName,
            int sqlCode, String sqlState, String message) {
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int filesDiscovered;
        private final int finalTables;
        private final int finalForeignKeys;
        private final List<ErrorRow> errors = new ArrayList<>();
        private final List<BlockerRow> blockers = new ArrayList<>();
        private final List<SkipRow> skips = new ArrayList<>();
        private final List<CleanupRow> cleanupErrors = new ArrayList<>();
        private final List<String> fatalMessages = new ArrayList<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String databaseName = "";
        private String authorizationId = "";
        private int attempted;
        private int succeeded;
        private int failed;
        private int structuralBlocked;
        private int dependencySkipped;
        private int cleanupFailed;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, int finalTables, int finalForeignKeys) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.finalTables = finalTables;
            this.finalForeignKeys = finalForeignKeys;
        }

        void readDatabaseInfo(Connection connection) throws SQLException {
            databaseProduct = connection.getMetaData().getDatabaseProductName();
            databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            databaseName = scalar(connection, "VALUES CURRENT SERVER");
            authorizationId = scalar(connection, "VALUES CURRENT USER");
        }

        void write() throws IOException {
            writeErrors();
            writeBlockers();
            writeSkips();
            writeCleanupErrors();
            Files.writeString(reportDir.resolve("db2luw-fk-validation-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        private void writeErrors() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-fk-validation-errors.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,source_columns,referenced_table,referenced_columns,sqlcode,sqlstate,message,elapsed_ms,sql\n");
                for (ErrorRow row : errors) {
                    out.write(String.join(",",
                            csv(row.file()), Integer.toString(row.statement()), csv(row.sourceTable()),
                            csv(row.constraintName()), csv(row.sourceColumns()), csv(row.referencedTable()),
                            csv(row.referencedColumns()), Integer.toString(row.sqlCode()), csv(row.sqlState()),
                            csv(row.message()), Long.toString(row.elapsedMs()), csv(row.sql())));
                    out.newLine();
                }
            }
        }

        private void writeBlockers() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-fk-validation-blockers.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,source_columns,referenced_table,referenced_columns,reason,detail,sql\n");
                for (BlockerRow row : blockers) {
                    out.write(String.join(",",
                            csv(row.file()), Integer.toString(row.statement()), csv(row.sourceTable()),
                            csv(row.constraintName()), csv(row.sourceColumns()), csv(row.referencedTable()),
                            csv(row.referencedColumns()), csv(row.reason()), csv(row.detail()), csv(row.sql())));
                    out.newLine();
                }
            }
        }

        private void writeSkips() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-fk-validation-skipped.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,source_columns,referenced_table,referenced_columns,reason\n");
                for (SkipRow row : skips) {
                    out.write(String.join(",",
                            csv(row.file()), Integer.toString(row.statement()), csv(row.sourceTable()),
                            csv(row.constraintName()), csv(row.sourceColumns()), csv(row.referencedTable()),
                            csv(row.referencedColumns()), csv(row.reason())));
                    out.newLine();
                }
            }
        }

        private void writeCleanupErrors() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-fk-validation-cleanup-errors.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,sqlcode,sqlstate,message\n");
                for (CleanupRow row : cleanupErrors) {
                    out.write(String.join(",",
                            csv(row.file()), Integer.toString(row.statement()), csv(row.sourceTable()),
                            csv(row.constraintName()), Integer.toString(row.sqlCode()), csv(row.sqlState()),
                            csv(row.message())));
                    out.newLine();
                }
            }
        }

        private String summary() {
            return "Db2 LUW FK live validation summary\n"
                    + "==================================\n"
                    + "Database product      : " + databaseProduct + "\n"
                    + "Database version      : " + databaseVersion + "\n"
                    + "Database              : " + databaseName + "\n"
                    + "Authorization ID      : " + authorizationId + "\n"
                    + "Root directory        : " + config.root() + "\n"
                    + "File suffix           : " + config.fileSuffix() + "\n"
                    + "Files discovered      : " + filesDiscovered + "\n"
                    + "Final table defs      : " + finalTables + "\n"
                    + "Final FK candidates   : " + finalForeignKeys + "\n"
                    + "FK attempted          : " + attempted + "\n"
                    + "FK succeeded          : " + succeeded + "\n"
                    + "FK failed             : " + failed + "\n"
                    + "Structural blocked    : " + structuralBlocked + "\n"
                    + "Dependency skipped    : " + dependencySkipped + "\n"
                    + "Cleanup failed        : " + cleanupFailed + "\n"
                    + "Elapsed               : " + elapsed + "\n"
                    + "Report directory      : " + reportDir + "\n"
                    + (fatalMessages.isEmpty() ? "" : "Fatal                 : " + String.join(" | ", fatalMessages) + "\n");
        }

        void printSummary() {
            System.out.println(summary());
        }
    }

    private record Config(
            Path root, String fileSuffix, String url, String user, String password, String driver,
            String expectedDatabase, String expectedSchema, Path reportBase, int maxFiles,
            int progressEveryTables, int loginTimeoutSeconds, int statementTimeoutSeconds,
            boolean failOnErrors, boolean failOnBlockers) {

        static Config load() {
            Path root = path(first("db2luw.fk.sql.root", "db2luw.sql.root"));
            String fileSuffix = value("db2luw.fk.sql.fileSuffix",
                    value("db2luw.sql.fileSuffix", ".db2luw.sql"));
            String url = first("db2luw.fk.jdbc.url", "db2luw.jdbc.url");
            String user = first("db2luw.fk.jdbc.user", "db2luw.jdbc.user");
            String password = firstOrDefault("", "db2luw.fk.jdbc.password", "db2luw.jdbc.password");
            String driver = value("db2luw.fk.jdbc.driver",
                    value("db2luw.jdbc.driver", "com.ibm.db2.jcc.DB2Driver"));
            String expectedDatabase = value("db2luw.fk.expectedDatabase",
                    value("db2luw.sql.expectedDatabase", "SFORGE"));
            String expectedSchema = value("db2luw.fk.expectedSchema", "TSTSHMA");
            Path reportBase = path(value("db2luw.fk.reportDir",
                    "target/db2luw-fk-validation-report"));
            return new Config(root, fileSuffix, url, user, password, driver,
                    expectedDatabase, expectedSchema, reportBase,
                    integer("db2luw.fk.maxFiles", 0),
                    integer("db2luw.fk.progressEveryTables", 100),
                    integer("db2luw.fk.loginTimeoutSeconds", 15),
                    integer("db2luw.fk.statementTimeoutSeconds", 60),
                    bool("db2luw.fk.failOnErrors", true),
                    bool("db2luw.fk.failOnBlockers", false));
        }

        boolean enabled() {
            return root != null && url != null && !url.isBlank() && user != null && !user.isBlank();
        }

        void validate() {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Db2 LUW FK SQL root not found: " + root);
            }
            if (fileSuffix == null || fileSuffix.isBlank()) {
                throw new IllegalArgumentException("Db2 LUW FK file suffix is blank");
            }
            if (expectedSchema == null || expectedSchema.isBlank()) {
                throw new IllegalArgumentException("db2luw.fk.expectedSchema must not be blank");
            }
            if (maxFiles < 0 || progressEveryTables < 1 || loginTimeoutSeconds < 1 || statementTimeoutSeconds < 1) {
                throw new IllegalArgumentException("Invalid Db2 LUW FK numeric configuration");
            }
        }

        private static String first(String... names) {
            for (String name : names) {
                String value = System.getProperty(name);
                if (value != null && !value.isBlank()) return value.trim();
            }
            return null;
        }

        private static String firstOrDefault(String fallback, String... names) {
            String value = first(names);
            return value == null ? fallback : value;
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
