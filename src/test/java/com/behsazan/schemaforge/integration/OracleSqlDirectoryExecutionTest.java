package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Executes every Oracle SQL script below a directory and collects all statement errors.
 *
 * <p>The test is disabled during normal builds. Supply the required properties to run it:</p>
 *
 * <pre>
 * mvn -Dtest=OracleSqlDirectoryExecutionTest test ^
 *   -Doracle.sql.root="D:\\OracleValidation\\LegacyOracleSql-fixed2" ^
 *   -Doracle.jdbc.url="jdbc:oracle:thin:@//localhost:1521/FREEPDB1" ^
 *   -Doracle.jdbc.user=TSTSHMA ^
 *   -Doracle.jdbc.password=secret
 * </pre>
 *
 * <p>For historical versions of the same table, use a disposable schema and enable:</p>
 *
 * <pre>
 * -Doracle.sql.dropBeforeCreate=true
 * -Doracle.sql.confirmDestructive=true
 * </pre>
 */
class OracleSqlDirectoryExecutionTest {

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+"
                    + "((?:\"[^\"]+\"|[A-Z0-9_$#]+)"
                    + "(?:\\s*\\.\\s*(?:\"[^\"]+\"|[A-Z0-9_$#]+))?)");

    private static final Pattern OBJECT_NAME = Pattern.compile(
            "(?is)^\\s*(?:CREATE\\s+(?:UNIQUE\\s+)?INDEX|CREATE\\s+TABLE|ALTER\\s+TABLE)\\s+"
                    + "((?:\"[^\"]+\"|[A-Z0-9_$#]+)"
                    + "(?:\\s*\\.\\s*(?:\"[^\"]+\"|[A-Z0-9_$#]+))?)");

    @Test
    void executeAllOracleScriptsRecursivelyAndCollectErrors() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set oracle.sql.root, oracle.jdbc.url and oracle.jdbc.user to run this test.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.maxFiles());
        if (files.isEmpty()) {
            fail("No SQL files found below " + config.root());
        }

        Path reportDir = config.reportBase()
                .resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);

        RunReport report = new RunReport(config, reportDir, files.size());
        Instant runStarted = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName("oracle.jdbc.OracleDriver");

            try (Connection connection = DriverManager.getConnection(
                    config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                report.readDatabaseInfo(connection);
                verifySchema(connection, config.expectedSchema());

                int sequence = 0;
                for (Path file : files) {
                    sequence++;
                    executeFile(connection, config, report, file, sequence);
                    if (sequence % config.progressEveryFiles() == 0 || sequence == files.size()) {
                        System.out.printf(Locale.ROOT,
                                "Oracle scripts: %,d / %,d, statements=%,d, errors=%,d%n",
                                sequence, files.size(), report.executed(), report.failed());
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
            if (fatal instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(fatal);
        }

        if (config.failOnErrors() && report.actionableFailed() > 0) {
            fail("Oracle execution completed with " + report.actionableFailed()
                    + " actionable errors. Report: " + reportDir);
        }
    }

    private void executeFile(
            Connection connection,
            Config config,
            RunReport report,
            Path file,
            int fileSequence) throws Exception {

        Instant fileStarted = Instant.now();
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        int actionable = 0;
        int ignored = 0;
        List<SqlUnit> statements;
        Set<String> tables = new LinkedHashSet<>();

        try {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            statements = new OracleStatementSplitter().split(script);
            for (SqlUnit unit : statements) {
                Matcher matcher = CREATE_TABLE.matcher(stripLeadingComments(unit.sql()));
                if (matcher.find()) {
                    tables.add(normalizeName(matcher.group(1)));
                }
            }
        } catch (Exception exception) {
            report.addFileError(file, fileSequence, exception);
            return;
        }

        if (config.dropBeforeCreate()) {
            for (String table : tables) {
                dropTable(connection, config, report, file, fileSequence, table);
            }
        }

        try (Statement jdbc = connection.createStatement()) {
            jdbc.setQueryTimeout(config.statementTimeoutSeconds());
            int statementIndex = 0;

            for (SqlUnit unit : statements) {
                statementIndex++;
                StatementType type = StatementType.of(unit.sql());
                if (config.shouldSkip(type)) {
                    skipped++;
                    report.skipped++;
                    continue;
                }

                report.executed++;
                Instant started = Instant.now();
                try {
                    jdbc.execute(unit.sql());
                    succeeded++;
                    report.succeeded++;
                } catch (SQLException exception) {
                    failed++;
                    boolean isActionable = report.addSqlError(
                            file, fileSequence, statementIndex, unit, type,
                            objectName(unit.sql()), exception,
                            Duration.between(started, Instant.now()));
                    if (isActionable) {
                        actionable++;
                    } else {
                        ignored++;
                    }
                    if (connectionFailure(exception)) {
                        throw new SQLRecoverableException(
                                "Oracle connection failed while executing " + file,
                                exception.getSQLState(), exception.getErrorCode(), exception);
                    }
                    if (type == StatementType.CREATE_TABLE && config.stopAfterCreateTableFailure()) {
                        int remaining = statements.size() - statementIndex;
                        skipped += remaining;
                        report.skipped += remaining;
                        break;
                    }
                }
            }
        }

        boolean createTableFailed = report.hasCreateTableError(fileSequence);
        report.files.add(new FileRow(
                fileSequence,
                relative(config.root(), file),
                createTableFailed ? "FAILED_CREATE_TABLE"
                        : failed == 0 ? "PASSED" : succeeded == 0 ? "FAILED" : "PARTIAL",
                statements.size(), succeeded, failed, actionable, ignored, skipped,
                Duration.between(fileStarted, Instant.now()).toMillis(),
                String.join("|", tables)));
    }

    private void dropTable(
            Connection connection,
            Config config,
            RunReport report,
            Path file,
            int fileSequence,
            String table) throws SQLException {

        verifyDestructiveTableOwner(table, config.expectedSchema());
        String sql = "DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE";
        report.cleanupAttempted++;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute(sql);
            report.cleanupSucceeded++;
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 942) {
                report.cleanupSucceeded++; // already absent is a successful cleanup state
                return;
            }
            report.cleanupFailed++;
            report.addCleanupError(file, fileSequence, sql, table, exception);
            if (connectionFailure(exception)) {
                throw exception;
            }
        }
    }

    private static List<Path> findSqlFiles(Path root, int maxFiles) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            Stream<Path> result = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> relative(root, path)));
            if (maxFiles > 0) {
                result = result.limit(maxFiles);
            }
            return result.toList();
        }
    }

    private static void verifyDestructiveTableOwner(String table, String expectedSchema) {
        int separator = table.indexOf('.');
        if (separator < 0) {
            return;
        }
        String owner = table.substring(0, separator).replace("\"", "");
        if (!owner.equalsIgnoreCase(expectedSchema)) {
            throw new IllegalStateException("Refusing to drop table outside expected schema: " + table);
        }
    }

    private static void verifySchema(Connection connection, String expected) throws SQLException {
        if (expected.isBlank()) {
            return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual")) {
            String actual = result.next() ? result.getString(1) : "";
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IllegalStateException(
                        "Expected schema " + expected + " but connected schema is " + actual);
            }
        }
    }

    private static boolean connectionFailure(SQLException exception) {
        int code = Math.abs(exception.getErrorCode());
        return exception instanceof SQLRecoverableException
                || Set.of(28, 1012, 3113, 3114, 3135, 12514, 12541, 17002, 17410)
                .contains(code);
    }

    private static String objectName(String sql) {
        Matcher matcher = OBJECT_NAME.matcher(stripLeadingComments(sql));
        return matcher.find() ? normalizeName(matcher.group(1)) : "";
    }

    private static String normalizeName(String value) {
        return value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
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

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName()
                + (current.getMessage() == null ? "" : ": " + current.getMessage());
    }

    private enum ExecutionMode {
        HISTORICAL, FULL;

        static ExecutionMode parse(String value) {
            return value == null || value.isBlank()
                    ? HISTORICAL
                    : ExecutionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private enum StatementType {
        CREATE_TABLE, ALTER_TABLE, ALTER_FOREIGN_KEY, CREATE_INDEX, COMMENT, GRANT,
        CREATE_SEQUENCE, CREATE_VIEW, CLEANUP, OTHER;

        static StatementType of(String sql) {
            String value = stripLeadingComments(sql).toUpperCase(Locale.ROOT);
            if (value.startsWith("CREATE TABLE")
                    || value.startsWith("CREATE GLOBAL TEMPORARY TABLE")) return CREATE_TABLE;
            if (value.startsWith("ALTER TABLE")) {
                if (value.contains(" FOREIGN KEY ") && value.contains(" REFERENCES ")) {
                    return ALTER_FOREIGN_KEY;
                }
                return ALTER_TABLE;
            }
            if (value.startsWith("CREATE INDEX")
                    || value.startsWith("CREATE UNIQUE INDEX")) return CREATE_INDEX;
            if (value.startsWith("COMMENT ON")) return COMMENT;
            if (value.startsWith("GRANT ")) return GRANT;
            if (value.startsWith("CREATE SEQUENCE")) return CREATE_SEQUENCE;
            if (value.startsWith("CREATE VIEW")
                    || value.startsWith("CREATE OR REPLACE VIEW")) return CREATE_VIEW;
            return OTHER;
        }
    }

    record SqlUnit(String sql, int startLine) {
    }

    /** Splits generated Oracle DDL while ignoring SQL*Plus commands and comment semicolons. */
    static final class OracleStatementSplitter {
        private static final List<String> CLIENT_COMMANDS = List.of(
                "PROMPT", "SET ", "WHENEVER ", "SPOOL", "EXIT", "SHOW ",
                "REM ", "REMARK ", "DEFINE ", "UNDEFINE ", "COLUMN ");

        List<SqlUnit> split(String script) {
            String[] lines = script.replace("\r\n", "\n")
                    .replace('\r', '\n').split("\\n", -1);
            List<SqlUnit> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            State state = new State();
            int startLine = 0;

            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                String line = lines[lineIndex];
                String trimmed = line.trim();
                int lineNumber = lineIndex + 1;

                if (!state.active()
                        && (clientCommand(trimmed) || trimmed.equals("/"))
                        && (current.toString().isBlank() || onlyComments(current.toString()))) {
                    // SQL*Plus commands are client directives, not JDBC SQL. Integrated scripts
                    // may place comments before PROMPT/SET lines, so a comment-only buffer must
                    // not cause the directive to be merged with the following CREATE statement.
                    current.setLength(0);
                    startLine = 0;
                    continue;
                }

                for (int i = 0; i < line.length(); i++) {
                    char ch = line.charAt(i);
                    char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                    if (startLine == 0 && !Character.isWhitespace(ch)) {
                        startLine = lineNumber;
                    }

                    if (state.singleQuote) {
                        current.append(ch);
                        if (ch == '\'' && next == '\'') {
                            current.append(next);
                            i++;
                        } else if (ch == '\'') {
                            state.singleQuote = false;
                        }
                    } else if (state.doubleQuote) {
                        current.append(ch);
                        if (ch == '"' && next == '"') {
                            current.append(next);
                            i++;
                        } else if (ch == '"') {
                            state.doubleQuote = false;
                        }
                    } else if (state.blockComment) {
                        current.append(ch);
                        if (ch == '*' && next == '/') {
                            current.append(next);
                            i++;
                            state.blockComment = false;
                        }
                    } else if (ch == '-' && next == '-') {
                        current.append(line.substring(i));
                        break;
                    } else if (ch == '/' && next == '*') {
                        current.append(ch).append(next);
                        i++;
                        state.blockComment = true;
                    } else if (ch == '\'') {
                        current.append(ch);
                        state.singleQuote = true;
                    } else if (ch == '"') {
                        current.append(ch);
                        state.doubleQuote = true;
                    } else if (ch == ';') {
                        add(result, current, startLine == 0 ? lineNumber : startLine);
                        current.setLength(0);
                        startLine = 0;
                    } else {
                        current.append(ch);
                    }
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
            }
            add(result, current, startLine == 0 ? 1 : startLine);
            return List.copyOf(result);
        }

        private static boolean clientCommand(String trimmed) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            return CLIENT_COMMANDS.stream().anyMatch(upper::startsWith);
        }

        private static void add(List<SqlUnit> result, StringBuilder value, int line) {
            String sql = value.toString().trim();
            if (!sql.isBlank() && !onlyComments(sql)) {
                result.add(new SqlUnit(sql, line));
            }
        }

        private static boolean onlyComments(String sql) {
            String noBlocks = sql.replaceAll("(?s)/\\*.*?\\*/", "");
            return noBlocks.lines()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("--"))
                    .allMatch(String::isBlank);
        }

        private static final class State {
            boolean singleQuote;
            boolean doubleQuote;
            boolean blockComment;

            boolean active() {
                return singleQuote || doubleQuote || blockComment;
            }
        }
    }

    private record ErrorRow(
            int fileSequence,
            String file,
            int statementIndex,
            int startLine,
            String statementType,
            String objectName,
            int errorCode,
            String oraCode,
            String sqlState,
            String category,
            boolean ignored,
            long elapsedMs,
            String message,
            String sqlExcerpt) {
    }

    private record FileRow(
            int sequence,
            String file,
            String status,
            int parsed,
            int succeeded,
            int failed,
            int actionable,
            int ignored,
            int skipped,
            long elapsedMs,
            String tables) {
    }

    private static final class RunReport {
        private final Config config;
        private final Path directory;
        private final int discoveredFiles;
        private final List<ErrorRow> errors = new ArrayList<>();
        private final List<FileRow> files = new ArrayList<>();
        private final List<String> fatalMessages = new ArrayList<>();
        private final Map<Integer, Long> errorCodes = new TreeMap<>();
        private String database = "";
        private String databaseVersion = "";
        private String currentSchema = "";
        private long executed;
        private long succeeded;
        private long failed;
        private long actionableFailed;
        private long ignoredFailed;
        private long skipped;
        private long cleanupAttempted;
        private long cleanupSucceeded;
        private long cleanupFailed;
        private Duration elapsed = Duration.ZERO;

        RunReport(Config config, Path directory, int discoveredFiles) {
            this.config = config;
            this.directory = directory;
            this.discoveredFiles = discoveredFiles;
        }

        void readDatabaseInfo(Connection connection) throws SQLException {
            database = connection.getMetaData().getDatabaseProductName();
            databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual")) {
                currentSchema = result.next() ? result.getString(1) : "";
            }
        }

        boolean addSqlError(
                Path file,
                int fileSequence,
                int statementIndex,
                SqlUnit unit,
                StatementType type,
                String objectName,
                SQLException exception,
                Duration duration) {

            failed++;
            int code = Math.abs(exception.getErrorCode());
            boolean ignored = config.ignoredCodes().contains(code);
            if (ignored) ignoredFailed++; else actionableFailed++;
            errorCodes.merge(code, 1L, Long::sum);

            errors.add(new ErrorRow(
                    fileSequence,
                    relative(config.root(), file),
                    statementIndex,
                    unit.startLine(),
                    type.name(),
                    objectName,
                    code,
                    code == 0 ? "" : String.format(Locale.ROOT, "ORA-%05d", code),
                    safe(exception.getSQLState()),
                    category(code),
                    ignored,
                    duration.toMillis(),
                    safe(exception.getMessage()),
                    excerpt(unit.sql(), 1000)));
            return !ignored;
        }

        void addCleanupError(
                Path file, int fileSequence, String sql, String objectName, SQLException exception) {
            int code = Math.abs(exception.getErrorCode());
            boolean ignored = config.ignoredCodes().contains(code);
            if (ignored) ignoredFailed++; else actionableFailed++;
            errorCodes.merge(code, 1L, Long::sum);
            errors.add(new ErrorRow(
                    fileSequence, relative(config.root(), file), 0, 0,
                    StatementType.CLEANUP.name(), objectName, code,
                    code == 0 ? "" : String.format(Locale.ROOT, "ORA-%05d", code),
                    safe(exception.getSQLState()), category(code), ignored, 0,
                    safe(exception.getMessage()), excerpt(sql, 1000)));
        }

        boolean hasCreateTableError(int fileSequence) {
            return errors.stream().anyMatch(error -> error.fileSequence() == fileSequence
                    && error.statementType().equals(StatementType.CREATE_TABLE.name()));
        }

        void addFileError(Path file, int sequence, Exception exception) {
            failed++;
            actionableFailed++;
            errors.add(new ErrorRow(
                    sequence, relative(config.root(), file), 0, 0, "FILE", "",
                    0, "", "", "FILE_OR_PARSER_ERROR", false, 0,
                    rootMessage(exception), ""));
            files.add(new FileRow(
                    sequence, relative(config.root(), file), "FAILED_TO_PROCESS",
                    0, 0, 1, 1, 0, 0, 0, ""));
        }

        long executed() {
            return executed;
        }

        long failed() {
            return failed;
        }

        long actionableFailed() {
            return actionableFailed;
        }

        void write() throws IOException {
            writeErrors(directory.resolve("oracle-sql-execution-errors.csv"));
            writeFiles(directory.resolve("oracle-sql-execution-files.csv"));
            writeSummary(directory.resolve("oracle-sql-execution-summary.txt"));
        }

        void printSummary() {
            System.out.println("============================================================");
            System.out.println("Files discovered    : " + discoveredFiles);
            System.out.println("Statements executed : " + executed);
            System.out.println("Statements succeeded: " + succeeded);
            System.out.println("Statements failed   : " + failed);
            System.out.println("Actionable failures : " + actionableFailed);
            System.out.println("Ignored failures    : " + ignoredFailed);
            System.out.println("Statements skipped  : " + skipped);
            System.out.println("Cleanup attempted   : " + cleanupAttempted);
            System.out.println("Cleanup succeeded   : " + cleanupSucceeded);
            System.out.println("Cleanup failed      : " + cleanupFailed);
            System.out.println("Execution mode      : " + config.executionMode());
            System.out.println("Elapsed             : " + elapsed);
            System.out.println("Reports             : " + directory.toAbsolutePath());
            System.out.println("============================================================");
        }

        private void writeErrors(Path output) throws IOException {
            StringBuilder csv = new StringBuilder("\uFEFF")
                    .append("file_sequence,file,statement_index,start_line,statement_type,")
                    .append("object_name,error_code,ora_code,sql_state,category,ignored,")
                    .append("elapsed_ms,message,sql_excerpt\n");
            for (ErrorRow row : errors) {
                csv.append(row.fileSequence()).append(',')
                        .append(csv(row.file())).append(',')
                        .append(row.statementIndex()).append(',')
                        .append(row.startLine()).append(',')
                        .append(row.statementType()).append(',')
                        .append(csv(row.objectName())).append(',')
                        .append(row.errorCode()).append(',')
                        .append(row.oraCode()).append(',')
                        .append(csv(row.sqlState())).append(',')
                        .append(row.category()).append(',')
                        .append(row.ignored()).append(',')
                        .append(row.elapsedMs()).append(',')
                        .append(csv(row.message())).append(',')
                        .append(csv(row.sqlExcerpt())).append('\n');
            }
            Files.writeString(output, csv, StandardCharsets.UTF_8);
        }

        private void writeFiles(Path output) throws IOException {
            StringBuilder csv = new StringBuilder("\uFEFF")
                    .append("sequence,file,status,parsed,succeeded,failed,actionable,")
                    .append("ignored,skipped,elapsed_ms,created_tables\n");
            files.stream().sorted(Comparator.comparingInt(FileRow::sequence)).forEach(row ->
                    csv.append(row.sequence()).append(',')
                            .append(csv(row.file())).append(',')
                            .append(row.status()).append(',')
                            .append(row.parsed()).append(',')
                            .append(row.succeeded()).append(',')
                            .append(row.failed()).append(',')
                            .append(row.actionable()).append(',')
                            .append(row.ignored()).append(',')
                            .append(row.skipped()).append(',')
                            .append(row.elapsedMs()).append(',')
                            .append(csv(row.tables())).append('\n'));
            Files.writeString(output, csv, StandardCharsets.UTF_8);
        }

        private void writeSummary(Path output) throws IOException {
            StringBuilder text = new StringBuilder()
                    .append("Oracle SQL execution summary\n")
                    .append("============================\n")
                    .append("Database             : ").append(database).append('\n')
                    .append("Database version     : ").append(databaseVersion).append('\n')
                    .append("Current schema       : ").append(currentSchema).append('\n')
                    .append("Root directory       : ").append(config.root()).append('\n')
                    .append("Files discovered     : ").append(discoveredFiles).append('\n')
                    .append("Statements executed  : ").append(executed).append('\n')
                    .append("Statements succeeded : ").append(succeeded).append('\n')
                    .append("Statements failed    : ").append(failed).append('\n')
                    .append("Actionable failures  : ").append(actionableFailed).append('\n')
                    .append("Ignored failures     : ").append(ignoredFailed).append('\n')
                    .append("Statements skipped   : ").append(skipped).append('\n')
                    .append("Cleanup attempted    : ").append(cleanupAttempted).append('\n')
                    .append("Cleanup succeeded    : ").append(cleanupSucceeded).append('\n')
                    .append("Cleanup failed       : ").append(cleanupFailed).append('\n')
                    .append("Execution mode       : ").append(config.executionMode()).append('\n')
                    .append("Stop after CREATE err: ").append(config.stopAfterCreateTableFailure()).append('\n')
                    .append("Drop before CREATE   : ").append(config.dropBeforeCreate()).append('\n')
                    .append("Elapsed              : ").append(elapsed).append("\n\n")
                    .append("Errors by ORA code\n")
                    .append("------------------\n");

            errorCodes.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                    .forEach(entry -> text.append(String.format(Locale.ROOT,
                            "ORA-%05d : %,d%n", entry.getKey(), entry.getValue())));
            if (errorCodes.isEmpty()) text.append("No Oracle errors.\n");

            if (!fatalMessages.isEmpty()) {
                text.append("\nFatal errors\n------------\n");
                fatalMessages.forEach(value -> text.append(value).append('\n'));
            }
            Files.writeString(output, text, StandardCharsets.UTF_8);
        }

        private static String category(int code) {
            return switch (code) {
                case 900, 902, 903, 905, 906, 907, 911, 917, 922, 933, 936, 1747, 1756 ->
                        "SYNTAX_OR_INVALID_DDL";
                case 904 -> "INVALID_IDENTIFIER";
                case 3050 -> "RESERVED_IDENTIFIER";
                case 910 -> "DATATYPE_LENGTH";
                case 932 -> "TYPE_MISMATCH";
                case 1401 -> "VALUE_TOO_LARGE";
                case 1408 -> "DUPLICATE_INDEX";
                case 1722 -> "INVALID_NUMBER";
                case 955, 1430, 2260, 2261, 2264, 2275 -> "DUPLICATE_OBJECT";
                case 942, 4043 -> "MISSING_OBJECT";
                case 972 -> "IDENTIFIER_TOO_LONG";
                case 1031 -> "INSUFFICIENT_PRIVILEGES";
                case 1917, 1918, 1919 -> "MISSING_USER_OR_ROLE";
                case 959 -> "MISSING_TABLESPACE";
                case 1950 -> "NO_TABLESPACE_QUOTA";
                case 1438 -> "PRECISION_OR_SCALE";
                case 2267 -> "FK_COLUMN_TYPE_MISMATCH";
                case 2270 -> "FK_TARGET_NOT_UNIQUE";
                case 28, 1012, 3113, 3114, 3135, 12514, 12541, 17002, 17410 ->
                        "CONNECTION_ERROR";
                default -> "OTHER_ORACLE_ERROR";
            };
        }

        private static String excerpt(String sql, int limit) {
            String value = safe(sql).replaceAll("\\s+", " ").trim();
            return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
        }

        private static String csv(String value) {
            return '"' + safe(value).replace("\"", "\"\"") + '"';
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    private record Config(
            Path root,
            String url,
            String user,
            String password,
            Path reportBase,
            String expectedSchema,
            boolean dropBeforeCreate,
            boolean confirmDestructive,
            boolean failOnErrors,
            Set<Integer> ignoredCodes,
            Set<StatementType> skippedTypes,
            ExecutionMode executionMode,
            boolean stopAfterCreateTableFailure,
            int statementTimeoutSeconds,
            int loginTimeoutSeconds,
            int progressEveryFiles,
            int maxFiles,
            boolean enabled) {

        static Config load() {
            String root = setting("oracle.sql.root", "ORACLE_SQL_ROOT");
            String url = setting("oracle.jdbc.url", "ORACLE_JDBC_URL");
            String user = setting("oracle.jdbc.user", "ORACLE_JDBC_USER");
            boolean enabled = !root.isBlank() || !url.isBlank() || !user.isBlank();

            return new Config(
                    root.isBlank() ? Path.of(".") : Path.of(root).toAbsolutePath().normalize(),
                    url,
                    user,
                    setting("oracle.jdbc.password", "ORACLE_JDBC_PASSWORD"),
                    Path.of(value("oracle.sql.report.dir",
                            "target/oracle-sql-execution-report")).toAbsolutePath().normalize(),
                    value("oracle.sql.expectedSchema", ""),
                    bool("oracle.sql.dropBeforeCreate", false),
                    bool("oracle.sql.confirmDestructive", false),
                    bool("oracle.sql.failOnErrors", false),
                    integerSet(value("oracle.sql.ignoreErrorCodes", "")),
                    typeSet(value("oracle.sql.skipStatementTypes", "")),
                    ExecutionMode.parse(value("oracle.sql.executionMode", "HISTORICAL")),
                    bool("oracle.sql.stopAfterCreateTableFailure", true),
                    integer("oracle.sql.statementTimeoutSeconds", 60),
                    integer("oracle.sql.loginTimeoutSeconds", 20),
                    integer("oracle.sql.progressEveryFiles", 100),
                    integer("oracle.sql.maxFiles", 0),
                    enabled);
        }

        boolean shouldSkip(StatementType type) {
            if (skippedTypes.contains(type)) {
                return true;
            }
            return executionMode == ExecutionMode.HISTORICAL
                    && (type == StatementType.GRANT || type == StatementType.ALTER_FOREIGN_KEY);
        }

        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException(
                    "oracle.sql.root is not a directory: " + root);
            if (url.isBlank()) throw new IllegalArgumentException("oracle.jdbc.url is required");
            if (user.isBlank()) throw new IllegalArgumentException("oracle.jdbc.user is required");
            if (dropBeforeCreate && !confirmDestructive) throw new IllegalStateException(
                    "dropBeforeCreate requires oracle.sql.confirmDestructive=true; "
                            + "use only a disposable schema");
            if (dropBeforeCreate && expectedSchema.isBlank()) throw new IllegalStateException(
                    "dropBeforeCreate also requires oracle.sql.expectedSchema");
            if (statementTimeoutSeconds < 1 || loginTimeoutSeconds < 1
                    || progressEveryFiles < 1 || maxFiles < 0) {
                throw new IllegalArgumentException("Oracle test numeric properties are invalid");
            }
        }

        private static String setting(String property, String environment) {
            String value = System.getProperty(property);
            if (value == null || value.isBlank()) value = System.getenv(environment);
            return value == null ? "" : value.trim();
        }

        private static String value(String property, String defaultValue) {
            String value = System.getProperty(property);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }

        private static boolean bool(String property, boolean defaultValue) {
            return Boolean.parseBoolean(value(property, Boolean.toString(defaultValue)));
        }

        private static int integer(String property, int defaultValue) {
            return Integer.parseInt(value(property, Integer.toString(defaultValue)));
        }

        private static Set<Integer> integerSet(String value) {
            if (value.isBlank()) return Set.of();
            Set<Integer> result = new LinkedHashSet<>();
            for (String token : value.split(",")) {
                result.add(Math.abs(Integer.parseInt(
                        token.trim().toUpperCase(Locale.ROOT).replace("ORA-", ""))));
            }
            return Set.copyOf(result);
        }

        private static Set<StatementType> typeSet(String value) {
            if (value.isBlank()) return Set.of();
            EnumSet<StatementType> result = EnumSet.noneOf(StatementType.class);
            for (String token : value.split(",")) {
                result.add(StatementType.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            }
            return Set.copyOf(result);
        }
    }
}
