package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Executes SchemaForge-generated Db2 LUW SQL recursively through JDBC.
 *
 * <p>This runner is intentionally disabled during normal builds. HISTORICAL mode is intended for
 * the Legacy Word canonical corpus: it drops/recreates the table represented by each script and
 * skips cross-table foreign keys and grants. That validates every historical table script without
 * pretending that thousands of historical revisions form one coherent final schema.</p>
 *
 * <p>Use only a disposable Db2 LUW database. Db2 DDL can commit and dropBeforeCreate is destructive.</p>
 */
class Db2LuwDirectoryExecutionTest {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String CONFIRMATION =
            "I_UNDERSTAND_DB2_LUW_DDL_MAY_COMMIT_AND_DROP_TABLES";

    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern CREATE_SEQUENCE_PATTERN = Pattern.compile(
            "(?is)^\\s*CREATE\\s+SEQUENCE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern CREATE_INDEX_PATTERN = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(" + IDENTIFIER + ")");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void executesGeneratedDb2LuwDirectory() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "Db2 LUW directory execution disabled; provide db2luw.sql.root, db2luw.jdbc.url and db2luw.jdbc.user");
        config.validate();

        List<Path> discovered = findSqlFiles(config.root(), config.fileSuffix());
        if (discovered.isEmpty()) {
            fail("No Db2 LUW SQL files found below " + config.root() + " with suffix " + config.fileSuffix());
        }
        if (config.startFileNumber() > discovered.size()) {
            fail("db2luw.sql.startFileNumber=" + config.startFileNumber()
                    + " exceeds discovered file count " + discovered.size());
        }

        int from = config.startFileNumber() - 1;
        int to = config.maxFiles() <= 0
                ? discovered.size()
                : Math.min(discovered.size(), from + config.maxFiles());
        List<Path> files = discovered.subList(from, to);

        String runId = RUN_ID.format(LocalDateTime.now());
        Path reportDir = Path.of("target", "db2luw-sql-execution-report", runId)
                .toAbsolutePath().normalize();
        Files.createDirectories(reportDir);
        RunReport report = new RunReport(config, reportDir, discovered.size(), files.size());
        Instant runStarted = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName(config.driver());
            try (Connection connection = DriverManager.getConnection(
                    config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                verifyExpectedDatabase(connection, config.expectedDatabase());
                report.database = scalar(connection, "VALUES CURRENT SERVER");
                report.authorizationId = scalar(connection, "VALUES CURRENT USER");

                int selectedDone = 0;
                int absoluteSequence = from;
                for (Path file : files) {
                    selectedDone++;
                    absoluteSequence++;
                    executeFile(connection, config, report, file, absoluteSequence);
                    if (selectedDone % config.progressEveryFiles() == 0 || selectedDone == files.size()) {
                        System.out.printf(Locale.ROOT,
                                "Db2 LUW scripts: %,d / %,d selected, absolute=%,d / %,d, statements=%,d, errors=%,d%n",
                                selectedDone, files.size(), absoluteSequence, discovered.size(),
                                report.executed, report.failed);
                    }
                }
            }
        } catch (Throwable throwable) {
            fatal = throwable;
            report.fatalMessages.add(rootMessage(throwable));
        } finally {
            report.elapsed = Duration.between(runStarted, Instant.now());
            report.write();
            report.printSummary();
        }

        if (fatal != null) {
            if (fatal instanceof Exception exception) throw exception;
            throw new RuntimeException(fatal);
        }
        if (config.failOnErrors() && report.actionableFailed > 0) {
            fail("Db2 LUW execution completed with " + report.actionableFailed
                    + " actionable failures. Report: " + reportDir);
        }
    }

    private void executeFile(Connection connection, Config config, RunReport report,
                             Path file, int fileSequence) throws Exception {
        Instant fileStarted = Instant.now();
        List<String> statements;
        Set<String> tables = new LinkedHashSet<>();
        Set<String> sequences = new LinkedHashSet<>();

        try {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            statements = splitter.parse(script, DatabasePlatform.DB2_LUW);
            for (String statement : statements) {
                String executable = stripLeadingComments(statement);
                Matcher table = CREATE_TABLE_PATTERN.matcher(executable);
                if (table.find()) tables.add(normalizeQualifiedName(table.group(1)));
                Matcher sequence = CREATE_SEQUENCE_PATTERN.matcher(executable);
                if (sequence.find()) sequences.add(normalizeQualifiedName(sequence.group(1)));
            }
        } catch (Exception exception) {
            report.fileReadFailures++;
            report.errors.add(new ErrorRow(fileSequence, relative(config.root(), file), 0,
                    "FILE_READ", "", "", 0, rootMessage(exception), ""));
            report.files.add(new FileRow(fileSequence, relative(config.root(), file), "FILE_READ_FAILED",
                    0, 0, 1, 1, 0, 0, 0, ""));
            return;
        }

        if (config.createMissingSchemas()) {
            for (String object : concat(tables, sequences)) {
                String schemaToken = schemaToken(object);
                if (schemaToken != null) ensureSchema(connection, config, report, schemaToken);
            }
        }

        if (config.dropBeforeCreate()) {
            for (String table : tables) dropTable(connection, config, report, table);
            for (String sequence : sequences) dropSequence(connection, config, report, sequence);
        }

        int succeeded = 0;
        int failed = 0;
        int actionable = 0;
        int ignored = 0;
        int skipped = 0;
        int statementIndex = 0;

        try (Statement jdbc = connection.createStatement()) {
            jdbc.setQueryTimeout(config.statementTimeoutSeconds());
            for (String raw : statements) {
                statementIndex++;
                String executable = stripLeadingComments(raw).trim();
                StatementKind kind = StatementKind.of(executable);

                if (config.shouldSkip(kind)) {
                    skipped++;
                    report.skipped++;
                    continue;
                }

                report.executed++;
                try {
                    jdbc.execute(raw);
                    succeeded++;
                    report.succeeded++;
                    if (kind == StatementKind.CREATE_TABLE) report.createdTables++;
                } catch (SQLException exception) {
                    failed++;
                    report.failed++;
                    boolean isIgnored = ignorable(exception, kind, config);
                    if (isIgnored) {
                        ignored++;
                        report.ignoredFailed++;
                    } else {
                        actionable++;
                        report.actionableFailed++;
                    }
                    report.errors.add(new ErrorRow(
                            fileSequence,
                            relative(config.root(), file),
                            statementIndex,
                            kind.name(),
                            objectName(executable),
                            exception.getSQLState(),
                            exception.getErrorCode(),
                            safeMessage(exception),
                            compactSql(raw)));

                    if (connectionFailure(exception)) throw exception;
                    if (kind == StatementKind.CREATE_TABLE && config.stopAfterCreateTableFailure()) {
                        int remaining = statements.size() - statementIndex;
                        skipped += remaining;
                        report.skipped += remaining;
                        break;
                    }
                }
            }
        }

        String status = failed == 0 ? "PASSED"
                : actionable == 0 ? "PASSED_WITH_IGNORED_ERRORS"
                : succeeded == 0 ? "FAILED" : "PARTIAL";
        report.files.add(new FileRow(
                fileSequence,
                relative(config.root(), file),
                status,
                statements.size(),
                succeeded,
                failed,
                actionable,
                ignored,
                skipped,
                Duration.between(fileStarted, Instant.now()).toMillis(),
                String.join("|", tables)));
    }

    private void ensureSchema(Connection connection, Config config, RunReport report,
                              String schemaToken) throws SQLException {
        String catalogName = unquoteIdentifier(schemaToken).toUpperCase(Locale.ROOT);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM SYSCAT.SCHEMATA WHERE SCHEMANAME = ? WITH UR")) {
            statement.setString(1, catalogName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getInt(1) > 0) return;
            }
        }

        report.schemaCreateAttempted++;
        String authorization = scalar(connection, "VALUES CURRENT USER").trim();
        String sql = "CREATE SCHEMA " + schemaToken + " AUTHORIZATION " + safeAuthId(authorization);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute(sql);
            report.schemaCreateSucceeded++;
            report.createdSchemas.add(catalogName);
        } catch (SQLException exception) {
            // Concurrent/repeated schema creation is harmless if the schema now exists.
            if (schemaExists(connection, catalogName)) return;
            throw exception;
        }
    }

    private boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM SYSCAT.SCHEMATA WHERE SCHEMANAME = ? WITH UR")) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private void dropTable(Connection connection, Config config, RunReport report, String table) throws SQLException {
        report.cleanupAttempted++;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute("DROP TABLE " + table);
            report.cleanupSucceeded++;
        } catch (SQLException exception) {
            if (objectNotFound(exception)) {
                report.cleanupNotFound++;
                return;
            }
            report.cleanupFailed++;
            report.errors.add(new ErrorRow(0, "<cleanup>", 0, "DROP_TABLE", table,
                    exception.getSQLState(), exception.getErrorCode(), safeMessage(exception),
                    "DROP TABLE " + table));
            if (connectionFailure(exception)) throw exception;
        }
    }

    private void dropSequence(Connection connection, Config config, RunReport report, String sequence) throws SQLException {
        report.cleanupAttempted++;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute("DROP SEQUENCE " + sequence);
            report.cleanupSucceeded++;
        } catch (SQLException exception) {
            if (objectNotFound(exception)) {
                report.cleanupNotFound++;
                return;
            }
            report.cleanupFailed++;
            report.errors.add(new ErrorRow(0, "<cleanup>", 0, "DROP_SEQUENCE", sequence,
                    exception.getSQLState(), exception.getErrorCode(), safeMessage(exception),
                    "DROP SEQUENCE " + sequence));
            if (connectionFailure(exception)) throw exception;
        }
    }

    private static List<String> concat(Set<String> first, Set<String> second) {
        List<String> values = new ArrayList<>(first.size() + second.size());
        values.addAll(first);
        values.addAll(second);
        return values;
    }

    private static List<Path> findSqlFiles(Path root, String suffix) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList();
        }
    }

    private static void verifyExpectedDatabase(Connection connection, String expected) throws SQLException {
        if (expected == null || expected.isBlank()) return;
        String actual = scalar(connection, "VALUES CURRENT SERVER");
        if (!expected.equalsIgnoreCase(actual.trim())) {
            throw new IllegalStateException("Refusing Db2 LUW execution: expected database "
                    + expected + " but connected to " + actual);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no rows: " + sql);
            return rs.getString(1);
        }
    }

    private static boolean ignorable(SQLException exception, StatementKind kind, Config config) {
        if (!config.ignoreAlreadyExists()) return false;
        // SQLSTATE 42710 = object already exists. In HISTORICAL replay this may happen for
        // supporting objects that are shared by more than one historical table script.
        return "42710".equals(exception.getSQLState()) && kind != StatementKind.CREATE_TABLE;
    }

    private static boolean objectNotFound(SQLException exception) {
        return "42704".equals(exception.getSQLState()) || exception.getErrorCode() == -204;
    }

    private static boolean connectionFailure(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("08");
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
            } else {
                value = trimmed;
            }
        } while (changed);
        return value;
    }

    private static String objectName(String sql) {
        Matcher table = CREATE_TABLE_PATTERN.matcher(sql);
        if (table.find()) return normalizeQualifiedName(table.group(1));
        Matcher alter = ALTER_TABLE_PATTERN.matcher(sql);
        if (alter.find()) return normalizeQualifiedName(alter.group(1));
        Matcher sequence = CREATE_SEQUENCE_PATTERN.matcher(sql);
        if (sequence.find()) return normalizeQualifiedName(sequence.group(1));
        Matcher index = CREATE_INDEX_PATTERN.matcher(sql);
        if (index.find()) return index.group(1).trim();
        return "";
    }

    private static String normalizeQualifiedName(String value) {
        return value == null ? "" : value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String schemaToken(String qualifiedName) {
        boolean quoted = false;
        for (int i = 0; i < qualifiedName.length(); i++) {
            char ch = qualifiedName.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < qualifiedName.length() && qualifiedName.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == '.' && !quoted) {
                return qualifiedName.substring(0, i).trim();
            }
        }
        return null;
    }

    private static String unquoteIdentifier(String token) {
        String value = token.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }

    private static String safeAuthId(String authId) {
        String value = authId == null ? "" : authId.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9_$#@]*")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String compactSql(String sql) {
        String compact = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return compact.length() <= 1000 ? compact : compact.substring(0, 1000) + "...";
    }

    private static String relative(Path root, Path file) {
        return normalize(root.relativize(file));
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + safeMessage(current);
    }

    private enum ExecutionMode {
        HISTORICAL,
        FULL;

        static ExecutionMode parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (Exception exception) {
                throw new IllegalArgumentException("Unsupported db2luw.sql.executionMode: " + value
                        + " (expected HISTORICAL or FULL)");
            }
        }
    }

    private enum StatementKind {
        CREATE_TABLE,
        CREATE_SEQUENCE,
        ALTER_TABLE_FOREIGN_KEY,
        ALTER_TABLE,
        CREATE_INDEX,
        GRANT,
        COMMENT,
        OTHER;

        static StatementKind of(String sql) {
            String upper = sql.toUpperCase(Locale.ROOT);
            if (CREATE_TABLE_PATTERN.matcher(sql).find()) return CREATE_TABLE;
            if (CREATE_SEQUENCE_PATTERN.matcher(sql).find()) return CREATE_SEQUENCE;
            if (upper.startsWith("ALTER TABLE") && upper.contains("FOREIGN KEY")) return ALTER_TABLE_FOREIGN_KEY;
            if (upper.startsWith("ALTER TABLE")) return ALTER_TABLE;
            if (CREATE_INDEX_PATTERN.matcher(sql).find()) return CREATE_INDEX;
            if (upper.startsWith("GRANT ")) return GRANT;
            if (upper.startsWith("COMMENT ON ")) return COMMENT;
            return OTHER;
        }
    }

    private record Config(
            Path root,
            String fileSuffix,
            String url,
            String user,
            String password,
            String driver,
            String expectedDatabase,
            ExecutionMode executionMode,
            boolean createMissingSchemas,
            boolean dropBeforeCreate,
            boolean skipForeignKeys,
            boolean skipGrants,
            boolean failOnErrors,
            boolean ignoreAlreadyExists,
            boolean stopAfterCreateTableFailure,
            int startFileNumber,
            int maxFiles,
            int progressEveryFiles,
            int loginTimeoutSeconds,
            int statementTimeoutSeconds,
            String confirmation) {

        static Config fromSystemProperties() {
            String root = System.getProperty("db2luw.sql.root", "").trim();
            String url = System.getProperty("db2luw.jdbc.url", "").trim();
            String user = System.getProperty("db2luw.jdbc.user", "").trim();
            ExecutionMode mode = ExecutionMode.parse(System.getProperty(
                    "db2luw.sql.executionMode", "HISTORICAL"));
            return new Config(
                    root.isEmpty() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("db2luw.sql.fileSuffix", ".db2luw.sql"),
                    url,
                    user,
                    System.getProperty("db2luw.jdbc.password", ""),
                    System.getProperty("db2luw.jdbc.driver", "com.ibm.db2.jcc.DB2Driver"),
                    System.getProperty("db2luw.sql.expectedDatabase", "SFORGE"),
                    mode,
                    bool("db2luw.sql.createMissingSchemas", true),
                    bool("db2luw.sql.dropBeforeCreate", mode == ExecutionMode.HISTORICAL),
                    bool("db2luw.sql.skipForeignKeys", mode == ExecutionMode.HISTORICAL),
                    bool("db2luw.sql.skipGrants", true),
                    bool("db2luw.sql.failOnErrors", false),
                    bool("db2luw.sql.ignoreAlreadyExists", true),
                    bool("db2luw.sql.stopAfterCreateTableFailure", true),
                    integer("db2luw.sql.startFileNumber", 1),
                    integer("db2luw.sql.maxFiles", 0),
                    integer("db2luw.sql.progressEveryFiles", 100),
                    integer("db2luw.sql.loginTimeoutSeconds", 15),
                    integer("db2luw.sql.statementTimeoutSeconds", 60),
                    System.getProperty("db2luw.sql.confirmDestructive", ""));
        }

        boolean enabled() {
            return root != null && !url.isBlank() && !user.isBlank();
        }

        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Not a directory: " + root);
            if (fileSuffix == null || fileSuffix.isBlank()) throw new IllegalArgumentException("fileSuffix is blank");
            if (startFileNumber < 1) throw new IllegalArgumentException("startFileNumber must be >= 1");
            if (maxFiles < 0) throw new IllegalArgumentException("maxFiles must be >= 0");
            if (progressEveryFiles < 1) throw new IllegalArgumentException("progressEveryFiles must be >= 1");
            if (loginTimeoutSeconds < 1 || statementTimeoutSeconds < 1) {
                throw new IllegalArgumentException("timeouts must be >= 1 second");
            }
            if (dropBeforeCreate && !CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("Explicit destructive confirmation required: -Ddb2luw.sql.confirmDestructive="
                        + CONFIRMATION);
            }
            if (executionMode == ExecutionMode.FULL && skipForeignKeys) {
                throw new IllegalArgumentException("FULL mode cannot skip foreign keys");
            }
        }

        boolean shouldSkip(StatementKind kind) {
            if (skipGrants && kind == StatementKind.GRANT) return true;
            return skipForeignKeys && kind == StatementKind.ALTER_TABLE_FOREIGN_KEY;
        }

        private static boolean bool(String name, boolean defaultValue) {
            return Boolean.parseBoolean(System.getProperty(name, Boolean.toString(defaultValue)));
        }

        private static int integer(String name, int defaultValue) {
            return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        }
    }

    private static final class RunReport {
        private final Config config;
        private final Path reportDir;
        private final int discoveredFiles;
        private final int selectedFiles;
        private final List<FileRow> files = new ArrayList<>();
        private final List<ErrorRow> errors = new ArrayList<>();
        private final List<String> fatalMessages = new ArrayList<>();
        private final Set<String> createdSchemas = new LinkedHashSet<>();

        private String database = "";
        private String authorizationId = "";
        private long executed;
        private long succeeded;
        private long failed;
        private long actionableFailed;
        private long ignoredFailed;
        private long skipped;
        private long createdTables;
        private long fileReadFailures;
        private long cleanupAttempted;
        private long cleanupSucceeded;
        private long cleanupNotFound;
        private long cleanupFailed;
        private long schemaCreateAttempted;
        private long schemaCreateSucceeded;
        private Duration elapsed = Duration.ZERO;

        private RunReport(Config config, Path reportDir, int discoveredFiles, int selectedFiles) {
            this.config = config;
            this.reportDir = reportDir;
            this.discoveredFiles = discoveredFiles;
            this.selectedFiles = selectedFiles;
        }

        void write() {
            try {
                Files.createDirectories(reportDir);
                Path summary = reportDir.resolve("summary.txt");
                Path fileCsv = reportDir.resolve("files.csv");
                Path errorCsv = reportDir.resolve("errors.csv");
                Path schemaCsv = reportDir.resolve("created-schemas.csv");

                Files.writeString(summary, summaryText(), StandardCharsets.UTF_8);

                List<String> fileLines = new ArrayList<>();
                fileLines.add("sequence,file,status,statements,succeeded,failed,actionable,ignored,skipped,elapsed_ms,tables");
                for (FileRow row : files) {
                    fileLines.add(csv(row.sequence(), row.file(), row.status(), row.statements(), row.succeeded(),
                            row.failed(), row.actionable(), row.ignored(), row.skipped(), row.elapsedMs(), row.tables()));
                }
                Files.write(fileCsv, fileLines, StandardCharsets.UTF_8);

                List<String> errorLines = new ArrayList<>();
                errorLines.add("sequence,file,statement,kind,object,sqlstate,sqlcode,message,sql");
                for (ErrorRow row : errors) {
                    errorLines.add(csv(row.sequence(), row.file(), row.statement(), row.kind(), row.object(),
                            row.sqlState(), row.sqlCode(), row.message(), row.sql()));
                }
                Files.write(errorCsv, errorLines, StandardCharsets.UTF_8);

                List<String> schemaLines = new ArrayList<>();
                schemaLines.add("schema");
                createdSchemas.forEach(schema -> schemaLines.add(csv(schema)));
                Files.write(schemaCsv, schemaLines, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new RuntimeException("Cannot write Db2 LUW execution report to " + reportDir, exception);
            }
        }

        void printSummary() {
            System.out.println(summaryText());
        }

        private String summaryText() {
            String nl = System.lineSeparator();
            return "Db2 LUW directory execution" + nl
                    + "Root                  : " + config.root() + nl
                    + "Database              : " + database + nl
                    + "Authorization ID      : " + authorizationId + nl
                    + "Mode                  : " + config.executionMode() + nl
                    + "Discovered files      : " + discoveredFiles + nl
                    + "Selected files        : " + selectedFiles + nl
                    + "File read failures    : " + fileReadFailures + nl
                    + "Statements executed   : " + executed + nl
                    + "Statements succeeded  : " + succeeded + nl
                    + "Statements failed     : " + failed + nl
                    + "Actionable failures   : " + actionableFailed + nl
                    + "Ignored failures      : " + ignoredFailed + nl
                    + "Statements skipped    : " + skipped + nl
                    + "CREATE TABLE success  : " + createdTables + nl
                    + "Schemas created       : " + schemaCreateSucceeded + nl
                    + "Cleanup attempted     : " + cleanupAttempted + nl
                    + "Cleanup succeeded     : " + cleanupSucceeded + nl
                    + "Cleanup not found     : " + cleanupNotFound + nl
                    + "Cleanup failed        : " + cleanupFailed + nl
                    + "Elapsed ms            : " + elapsed.toMillis() + nl
                    + "Report directory      : " + reportDir + nl
                    + (config.executionMode() == ExecutionMode.HISTORICAL
                    ? "Historical note       : final database state is replay evidence, not a consolidated canonical schema." + nl
                    : "")
                    + (fatalMessages.isEmpty() ? "" : "Fatal                 : " + String.join(" | ", fatalMessages) + nl);
        }
    }

    private record FileRow(int sequence, String file, String status, int statements,
                           int succeeded, int failed, int actionable, int ignored, int skipped,
                           long elapsedMs, String tables) {
    }

    private record ErrorRow(int sequence, String file, int statement, String kind, String object,
                            String sqlState, int sqlCode, String message, String sql) {
    }

    private static String csv(Object... values) {
        List<String> cells = new ArrayList<>(values.length);
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value);
            cells.add('"' + text.replace("\"", "\"\"") + '"');
        }
        return String.join(",", cells);
    }
}
