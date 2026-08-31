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
import java.sql.DatabaseMetaData;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Executes already-generated SchemaForge Db2 for z/OS SQL files recursively through JDBC.
 *
 * <p>The runner is opt-in and is skipped during normal builds. HISTORICAL mode is intended for
 * the persisted Legacy/Canonical corpus: each script is validated independently, cross-table
 * foreign keys and grants are skipped by default, and created objects are cleaned up after each
 * file. FULL mode executes the directory as supplied and is intended only for a coherent final
 * schema.</p>
 *
 * <p>Db2 for z/OS DDL can commit implicitly. Use only an explicitly approved disposable
 * validation subsystem/qualifier.</p>
 */
class Db2ZosDirectoryExecutionTest {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String DDL_CONFIRMATION = "I_UNDERSTAND_DB2_DDL_MAY_COMMIT";
    private static final String DESTRUCTIVE_CONFIRMATION =
            "I_UNDERSTAND_DB2_ZOS_DDL_MAY_COMMIT_AND_DROP_TABLES";

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
            "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(" + QUALIFIED_NAME + ")");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void executesGeneratedDb2ZosDirectory() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "Db2 z/OS directory execution disabled; provide db2zos.sql.root, "
                        + "schemaforge.db2zos.url and schemaforge.db2zos.user");
        config.validate();

        List<Path> discovered = findSqlFiles(config.root(), config.fileSuffix());
        if (discovered.isEmpty()) {
            fail("No Db2 z/OS SQL files found below " + config.root()
                    + " with suffix " + config.fileSuffix());
        }
        if (config.startFileNumber() > discovered.size()) {
            fail("db2zos.sql.startFileNumber=" + config.startFileNumber()
                    + " exceeds discovered file count " + discovered.size());
        }

        int from = config.startFileNumber() - 1;
        int to = config.maxFiles() <= 0
                ? discovered.size()
                : Math.min(discovered.size(), from + config.maxFiles());
        List<Path> files = discovered.subList(from, to);

        String runId = RUN_ID.format(LocalDateTime.now());
        Path reportDir = Path.of(System.getProperty(
                        "db2zos.sql.report.dir", "target/db2zos-sql-execution-report"), runId)
                .toAbsolutePath().normalize();
        Files.createDirectories(reportDir);
        RunReport report = new RunReport(config, reportDir, discovered.size(), files.size());
        Instant started = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName(config.driver());
            try (Connection connection = DriverManager.getConnection(
                    config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                verifyDb2Zos(connection);
                report.product = product(connection);
                report.currentServer = scalar(connection,
                        "SELECT CURRENT SERVER FROM SYSIBM.SYSDUMMY1");
                report.currentSqlId = scalar(connection,
                        "SELECT CURRENT SQLID FROM SYSIBM.SYSDUMMY1");
                report.authorizationId = scalar(connection,
                        "SELECT CURRENT USER FROM SYSIBM.SYSDUMMY1");
                verifyExpectedServer(report.currentServer, config.expectedServer());

                int selectedDone = 0;
                int absoluteSequence = from;
                for (Path file : files) {
                    selectedDone++;
                    absoluteSequence++;
                    executeFile(connection, config, report, file, absoluteSequence);
                    if (selectedDone % config.progressEveryFiles() == 0 || selectedDone == files.size()) {
                        System.out.printf(Locale.ROOT,
                                "Db2 z/OS scripts: %,d / %,d selected, absolute=%,d / %,d, "
                                        + "statements=%,d, succeeded=%,d, errors=%,d%n",
                                selectedDone, files.size(), absoluteSequence, discovered.size(),
                                report.executed, report.succeeded, report.failed);
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
        if (config.failOnErrors() && report.actionableFailed > 0) {
            fail("Db2 z/OS directory execution completed with " + report.actionableFailed
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
            statements = splitter.parse(script, DatabasePlatform.DB2_ZOS);
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
            report.files.add(new FileRow(fileSequence, relative(config.root(), file),
                    "FILE_READ_FAILED", 0, 0, 1, 1, 0, 0, 0, ""));
            return;
        }

        if (config.dropBeforeCreate()) {
            cleanupObjects(connection, config, report, tables, sequences, "pre-file");
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
                if (executable.isBlank()) continue;
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
        } finally {
            if (config.cleanupAfterEachFile()) {
                cleanupObjects(connection, config, report, tables, sequences, "post-file");
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

    private void cleanupObjects(Connection connection, Config config, RunReport report,
                                Set<String> tables, Set<String> sequences, String phase) throws SQLException {
        for (String table : tables) dropObject(connection, config, report, "TABLE", table, phase);
        for (String sequence : sequences) dropObject(connection, config, report, "SEQUENCE", sequence, phase);
    }

    private void dropObject(Connection connection, Config config, RunReport report,
                            String kind, String object, String phase) throws SQLException {
        report.cleanupAttempted++;
        String sql = "DROP " + kind + " " + object;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute(sql);
            report.cleanupSucceeded++;
        } catch (SQLException exception) {
            if (objectNotFound(exception)) {
                report.cleanupNotFound++;
                return;
            }
            report.cleanupFailed++;
            report.errors.add(new ErrorRow(0, "<cleanup:" + phase + ">", 0,
                    "DROP_" + kind, object, exception.getSQLState(), exception.getErrorCode(),
                    safeMessage(exception), sql));
            if (connectionFailure(exception)) throw exception;
        }
    }

    private static List<Path> findSqlFiles(Path root, String suffix) throws IOException {
        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(normalizedSuffix))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList();
        }
    }

    private static void verifyDb2Zos(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String product = (meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion())
                .toLowerCase(Locale.ROOT);
        if (!product.contains("db2")) {
            throw new IllegalStateException("Refusing Db2 z/OS execution: JDBC target is " + product);
        }
        scalar(connection, "SELECT CURRENT SQLID FROM SYSIBM.SYSDUMMY1");
    }

    private static void verifyExpectedServer(String actual, String expected) {
        if (expected == null || expected.isBlank()) return;
        if (!expected.equalsIgnoreCase(actual.trim())) {
            throw new IllegalStateException("Refusing Db2 z/OS execution: expected CURRENT SERVER "
                    + expected + " but connected to " + actual);
        }
    }

    private static String product(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no rows: " + sql);
            return rs.getString(1);
        }
    }

    private static boolean ignorable(SQLException exception, StatementKind kind, Config config) {
        if (!config.ignoreAlreadyExists()) return false;
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
        if (index.find()) return normalizeQualifiedName(index.group(1));
        return "";
    }

    private static String normalizeQualifiedName(String value) {
        return value == null ? "" : value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String compactSql(String sql) {
        String compact = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return compact.length() <= 1200 ? compact : compact.substring(0, 1200) + "...";
    }

    private static String relative(Path root, Path file) {
        return normalize(root.relativize(file));
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName()
                : message.replaceAll("[\\r\\n]+", " ").trim();
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
                throw new IllegalArgumentException("Unsupported db2zos.sql.executionMode: " + value
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
            if (upper.startsWith("ALTER TABLE") && upper.contains("FOREIGN KEY")) {
                return ALTER_TABLE_FOREIGN_KEY;
            }
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
            String expectedServer,
            ExecutionMode executionMode,
            boolean dropBeforeCreate,
            boolean cleanupAfterEachFile,
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
            String ddlConfirmation,
            String destructiveConfirmation) {

        static Config fromSystemProperties() {
            String root = firstNonBlank(
                    System.getProperty("db2zos.sql.root"),
                    System.getProperty("schemaforge.db2zos.offline.sqlRoot"),
                    System.getenv("DB2ZOS_SQL_ROOT"));
            String url = firstNonBlank(
                    System.getProperty("schemaforge.db2zos.url"),
                    System.getProperty("db2zos.jdbc.url"));
            String user = firstNonBlank(
                    System.getProperty("schemaforge.db2zos.user"),
                    System.getProperty("db2zos.jdbc.user"));
            ExecutionMode mode = ExecutionMode.parse(
                    System.getProperty("db2zos.sql.executionMode", "HISTORICAL"));
            return new Config(
                    root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("db2zos.sql.fileSuffix", ".db2zos.sql"),
                    url,
                    user,
                    firstNonBlank(System.getProperty("schemaforge.db2zos.password"),
                            System.getProperty("db2zos.jdbc.password")),
                    firstNonBlankOrDefault("com.ibm.db2.jcc.DB2Driver",
                            System.getProperty("schemaforge.db2zos.driver"),
                            System.getProperty("db2zos.jdbc.driver")),
                    System.getProperty("db2zos.sql.expectedServer", "").trim(),
                    mode,
                    bool("db2zos.sql.dropBeforeCreate", mode == ExecutionMode.HISTORICAL),
                    bool("db2zos.sql.cleanupAfterEachFile", mode == ExecutionMode.HISTORICAL),
                    bool("db2zos.sql.skipForeignKeys", mode == ExecutionMode.HISTORICAL),
                    bool("db2zos.sql.skipGrants", true),
                    bool("db2zos.sql.failOnErrors", false),
                    bool("db2zos.sql.ignoreAlreadyExists", true),
                    bool("db2zos.sql.stopAfterCreateTableFailure", true),
                    integer("db2zos.sql.startFileNumber", 1),
                    integer("db2zos.sql.maxFiles", 0),
                    integer("db2zos.sql.progressEveryFiles", 50),
                    integer("db2zos.sql.loginTimeoutSeconds", 20),
                    integer("db2zos.sql.statementTimeoutSeconds", 120),
                    System.getProperty("schemaforge.db2zos.execution.confirm", ""),
                    System.getProperty("db2zos.sql.confirmDestructive", ""));
        }

        boolean enabled() {
            return root != null && !url.isBlank() && !user.isBlank();
        }

        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Not a directory: " + root);
            if (fileSuffix == null || fileSuffix.isBlank()) {
                throw new IllegalArgumentException("db2zos.sql.fileSuffix is blank");
            }
            if (!DDL_CONFIRMATION.equals(ddlConfirmation)) {
                throw new IllegalArgumentException("Explicit Db2 z/OS DDL confirmation required: "
                        + "-Dschemaforge.db2zos.execution.confirm=" + DDL_CONFIRMATION);
            }
            if ((dropBeforeCreate || cleanupAfterEachFile)
                    && !DESTRUCTIVE_CONFIRMATION.equals(destructiveConfirmation)) {
                throw new IllegalArgumentException("Explicit destructive confirmation required: "
                        + "-Ddb2zos.sql.confirmDestructive=" + DESTRUCTIVE_CONFIRMATION);
            }
            if (executionMode == ExecutionMode.FULL && skipForeignKeys) {
                throw new IllegalArgumentException("FULL mode cannot skip foreign keys");
            }
            if (startFileNumber < 1 || maxFiles < 0 || progressEveryFiles < 1
                    || loginTimeoutSeconds < 1 || statementTimeoutSeconds < 1) {
                throw new IllegalArgumentException("Invalid Db2 z/OS directory execution numeric option");
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

        private String product = "";
        private String currentServer = "";
        private String currentSqlId = "";
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
                Files.writeString(reportDir.resolve("summary.txt"), summaryText(), StandardCharsets.UTF_8);

                List<String> fileLines = new ArrayList<>();
                fileLines.add("sequence,file,status,statements,succeeded,failed,actionable,ignored,skipped,elapsed_ms,tables");
                for (FileRow row : files) {
                    fileLines.add(csv(row.sequence(), row.file(), row.status(), row.statements(), row.succeeded(),
                            row.failed(), row.actionable(), row.ignored(), row.skipped(), row.elapsedMs(), row.tables()));
                }
                Files.write(reportDir.resolve("files.csv"), fileLines, StandardCharsets.UTF_8);

                List<String> errorLines = new ArrayList<>();
                errorLines.add("sequence,file,statement,kind,object,sqlstate,sqlcode,message,sql");
                for (ErrorRow row : errors) {
                    errorLines.add(csv(row.sequence(), row.file(), row.statement(), row.kind(), row.object(),
                            row.sqlState(), row.sqlCode(), row.message(), row.sql()));
                }
                Files.write(reportDir.resolve("errors.csv"), errorLines, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new RuntimeException("Cannot write Db2 z/OS execution report to " + reportDir, exception);
            }
        }

        void printSummary() {
            System.out.println(summaryText());
        }

        private String summaryText() {
            String nl = System.lineSeparator();
            return "Db2 z/OS directory execution" + nl
                    + "Root                  : " + config.root() + nl
                    + "Product               : " + product + nl
                    + "CURRENT SERVER        : " + currentServer + nl
                    + "CURRENT SQLID         : " + currentSqlId + nl
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
                    + "Cleanup attempted     : " + cleanupAttempted + nl
                    + "Cleanup succeeded     : " + cleanupSucceeded + nl
                    + "Cleanup not found     : " + cleanupNotFound + nl
                    + "Cleanup failed        : " + cleanupFailed + nl
                    + "Elapsed ms            : " + elapsed.toMillis() + nl
                    + "Report directory      : " + reportDir + nl
                    + (config.executionMode() == ExecutionMode.HISTORICAL
                    ? "Historical note       : each script is independent replay evidence; cross-table FKs are skipped by default." + nl
                    : "")
                    + (fatalMessages.isEmpty() ? ""
                    : "Fatal                 : " + String.join(" | ", fatalMessages) + nl);
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String firstNonBlankOrDefault(String defaultValue, String... values) {
        String value = firstNonBlank(values);
        return value.isBlank() ? defaultValue : value;
    }
}
