package com.behsazan.schemaforge.integration;

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
 * Executes generated SQL Server DDL files recursively through JDBC and writes durable execution reports.
 *
 * <p>The test is intentionally disabled during ordinary builds. It becomes active only when the SQL root,
 * JDBC URL, and JDBC user are supplied. The default {@code HISTORICAL} mode is designed for validating many
 * historical versions of the same logical table: cross-table foreign keys and grants are skipped, and a
 * disposable schema can optionally drop each table before its corresponding script is executed.</p>
 *
 * <p>SQL Server Management Studio/sqlcmd batch separators such as {@code GO} are not T-SQL statements.
 * The splitter removes standalone {@code GO} lines before JDBC execution while preserving comments, string
 * literals, delimited identifiers, and semicolons inside quoted content.</p>
 *
 * <p>Use only a disposable validation database/schema when {@code dropBeforeCreate=true}.</p>
 */
class SqlServerDirectoryExecutionTest {

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String IDENTIFIER =
            "(?:\\[(?:[^]]|]])*\\]|\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:(?:UNLOGGED|TEMP|TEMPORARY)\\s+)?TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + QUALIFIED_NAME + ")");

    private static final Pattern OBJECT_NAME = Pattern.compile(
            "(?is)^\\s*(?:CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "|CREATE\\s+(?:(?:UNLOGGED|TEMP|TEMPORARY)\\s+)?TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "|ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?"
                    + ")(" + QUALIFIED_NAME + ")");

    private static final Pattern ADD_FOREIGN_KEY = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")"
                    + "\\s+WITH\\s+CHECK\\s+ADD\\s+CONSTRAINT\\s+(" + IDENTIFIER + ")"
                    + "\\s+FOREIGN\\s+KEY\\b");

    private static final Pattern CHECK_CONSTRAINT = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")"
                    + "\\s+CHECK\\s+CONSTRAINT\\s+(" + IDENTIFIER + ")\\s*;?\\s*$");

    @Test
    void executeAllSqlServerScriptsRecursivelyAndCollectErrors() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set sqlserver.sql.root, sqlserver.jdbc.url and sqlserver.jdbc.user to run this test.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) {
            fail("No SQL Server SQL files found below " + config.root()
                    + " with suffix " + config.fileSuffix());
        }

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);

        RunReport report = new RunReport(config, reportDir, files.size());
        Instant runStarted = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

            try (Connection connection = DriverManager.getConnection(
                    config.url(), config.user(), config.password())) {
                // Auto-commit keeps every DDL statement independent so one failure does not prevent
                // the runner from collecting later SQL Server errors.
                connection.setAutoCommit(true);
                report.readDatabaseInfo(connection);
                report.readExpectedSchemaInfo(connection);

                int sequence = 0;
                for (Path file : files) {
                    sequence++;
                    executeFile(connection, config, report, file, sequence);
                    if (sequence % config.progressEveryFiles() == 0 || sequence == files.size()) {
                        System.out.printf(Locale.ROOT,
                                "SQL Server scripts: %,d / %,d, statements=%,d, errors=%,d%n",
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
            fail("SQL Server execution completed with " + report.actionableFailed()
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
        Set<String> skippedForeignKeyConstraints = new LinkedHashSet<>();

        try {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            SplitResult split = new SqlServerStatementSplitter().split(script);
            statements = split.statements();
            report.batchSeparatorsSkipped += split.batchSeparatorsSkipped();
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
                    if (type == StatementType.ALTER_FOREIGN_KEY) {
                        String key = foreignKeyConstraintKey(unit.sql());
                        if (!key.isBlank()) {
                            skippedForeignKeyConstraints.add(key);
                        }
                    }
                    skipped++;
                    report.skipped++;
                    continue;
                }
                if (config.executionMode() == ExecutionMode.HISTORICAL) {
                    String key = checkedConstraintKey(unit.sql());
                    if (!key.isBlank() && skippedForeignKeyConstraints.remove(key)) {
                        skipped++;
                        report.skipped++;
                        continue;
                    }
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
                        throw new SQLException(
                                "SQL Server connection failed while executing " + file,
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
        String sql = dropTableSql(table);
        report.cleanupAttempted++;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute(sql);
            report.cleanupSucceeded++;
        } catch (SQLException exception) {
            report.cleanupFailed++;
            report.addCleanupError(file, fileSequence, sql, table, exception);
            if (connectionFailure(exception)) {
                throw exception;
            }
        }
    }

    static String dropTableSql(String table) {
        // SQL Server supports DROP TABLE IF EXISTS, but unlike PostgreSQL it does not
        // support a trailing CASCADE clause. HISTORICAL mode already skips cross-table
        // foreign-key creation, so the plain SQL Server DROP TABLE form is sufficient.
        return "DROP TABLE IF EXISTS " + table;
    }

    private static List<Path> findSqlFiles(Path root, String suffix, int maxFiles) throws IOException {
        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(root)) {
            Stream<Path> result = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(normalizedSuffix))
                    .sorted(Comparator.comparing(path -> relative(root, path)));
            if (maxFiles > 0) {
                result = result.limit(maxFiles);
            }
            return result.toList();
        }
    }

    private static void verifyDestructiveTableOwner(String table, String expectedSchema) {
        if (expectedSchema.isBlank()) {
            throw new IllegalStateException(
                    "Destructive mode requires sqlserver.sql.expectedSchema");
        }
        int separator = unquotedDot(table);
        if (separator < 0) {
            throw new IllegalStateException(
                    "Refusing to drop unqualified table in destructive mode: " + table);
        }
        String owner = unquoteIdentifier(table.substring(0, separator).trim());
        if (!owner.equalsIgnoreCase(expectedSchema)) {
            throw new IllegalStateException(
                    "Refusing to drop table outside expected schema " + expectedSchema + ": " + table);
        }
    }

    private static int unquotedDot(String value) {
        boolean doubleQuoted = false;
        boolean bracketQuoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (bracketQuoted) {
                if (ch == ']' && i + 1 < value.length() && value.charAt(i + 1) == ']') {
                    i++;
                } else if (ch == ']') {
                    bracketQuoted = false;
                }
            } else if (doubleQuoted) {
                if (ch == '"' && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    i++;
                } else if (ch == '"') {
                    doubleQuoted = false;
                }
            } else if (ch == '[') {
                bracketQuoted = true;
            } else if (ch == '"') {
                doubleQuoted = true;
            } else if (ch == '.') {
                return i;
            }
        }
        return -1;
    }

    private static String unquoteIdentifier(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("]]", "]");
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    static String foreignKeyConstraintKey(String sql) {
        Matcher matcher = ADD_FOREIGN_KEY.matcher(stripLeadingComments(sql));
        return matcher.find() ? constraintKey(matcher.group(1), matcher.group(2)) : "";
    }

    static String checkedConstraintKey(String sql) {
        Matcher matcher = CHECK_CONSTRAINT.matcher(stripLeadingComments(sql));
        return matcher.find() ? constraintKey(matcher.group(1), matcher.group(2)) : "";
    }

    private static String constraintKey(String tableName, String constraintName) {
        return normalizeName(tableName).toUpperCase(Locale.ROOT)
                + "|" + unquoteIdentifier(constraintName).toUpperCase(Locale.ROOT);
    }

    private static boolean connectionFailure(SQLException exception) {
        String state = safe(exception.getSQLState()).toUpperCase(Locale.ROOT);
        return state.startsWith("08");
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
        CREATE_SCHEMA, CREATE_TABLE, ALTER_TABLE, ALTER_FOREIGN_KEY, CREATE_INDEX, COMMENT,
        GRANT, CREATE_SEQUENCE, CREATE_VIEW, OTHER, CLEANUP;

        static StatementType of(String sql) {
            String value = stripLeadingComments(sql).toUpperCase(Locale.ROOT);
            if (value.startsWith("CREATE SCHEMA") || value.startsWith("IF SCHEMA_ID(")) return CREATE_SCHEMA;
            if (value.matches("(?s)^CREATE\\s+(?:(?:UNLOGGED|TEMP|TEMPORARY)\\s+)?TABLE\\b.*")) {
                return CREATE_TABLE;
            }
            if (value.startsWith("ALTER TABLE")) {
                if (value.contains(" FOREIGN KEY ") && value.contains(" REFERENCES ")) {
                    return ALTER_FOREIGN_KEY;
                }
                return ALTER_TABLE;
            }
            if (value.startsWith("CREATE INDEX")
                    || value.startsWith("CREATE UNIQUE INDEX")) return CREATE_INDEX;
            if (value.startsWith("COMMENT ON") || value.startsWith("EXEC SYS.SP_ADDEXTENDEDPROPERTY")) return COMMENT;
            if (value.startsWith("GRANT ")) return GRANT;
            if (value.startsWith("CREATE SEQUENCE")) return CREATE_SEQUENCE;
            if (value.startsWith("CREATE VIEW")
                    || value.startsWith("CREATE OR REPLACE VIEW")) return CREATE_VIEW;
            return OTHER;
        }
    }

    private record SqlUnit(String sql, int startLine) {
    }

    private record SplitResult(List<SqlUnit> statements, int batchSeparatorsSkipped) {
    }

    /**
     * Splits SQL Server SQL for JDBC execution and removes line-oriented {@code SQL Server client} meta-commands.
     */
    private static final class SqlServerStatementSplitter {

        SplitResult split(String script) {
            String[] lines = script.replace("\r\n", "\n")
                    .replace('\r', '\n').split("\\n", -1);
            List<SqlUnit> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            State state = new State();
            int startLine = 0;
            int batchSeparators = 0;

            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                String line = lines[lineIndex];
                String trimmed = line.trim();
                int lineNumber = lineIndex + 1;

                if (!state.active() && trimmed.matches("(?i)^GO(?:\\s+\\d+)?$")) {
                    add(result, current, startLine == 0 ? lineNumber : startLine);
                    current.setLength(0);
                    startLine = 0;
                    batchSeparators++;
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
                    } else if (state.bracketQuote) {
                        current.append(ch);
                        if (ch == ']' && next == ']') {
                            current.append(next);
                            i++;
                        } else if (ch == ']') {
                            state.bracketQuote = false;
                        }
                    } else if (state.blockCommentDepth > 0) {
                        current.append(ch);
                        if (ch == '/' && next == '*') {
                            current.append(next);
                            i++;
                            state.blockCommentDepth++;
                        } else if (ch == '*' && next == '/') {
                            current.append(next);
                            i++;
                            state.blockCommentDepth--;
                        }
                    } else if (ch == '-' && next == '-') {
                        current.append(line.substring(i));
                        break;
                    } else if (ch == '/' && next == '*') {
                        current.append(ch).append(next);
                        i++;
                        state.blockCommentDepth = 1;
                    } else if (ch == '\'') {
                        current.append(ch);
                        state.singleQuote = true;
                    } else if (ch == '"') {
                        current.append(ch);
                        state.doubleQuote = true;
                    } else if (ch == '[') {
                        current.append(ch);
                        state.bracketQuote = true;
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
            return new SplitResult(List.copyOf(result), batchSeparators);
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
            boolean bracketQuote;
            int blockCommentDepth;

            boolean active() {
                return singleQuote || doubleQuote || bracketQuote || blockCommentDepth > 0;
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
            String sqlState,
            int errorCode,
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
        private final Map<String, Long> sqlStates = new TreeMap<>();
        private final Map<Integer, Long> errorCodes = new TreeMap<>();
        private String database = "";
        private String databaseVersion = "";
        private String databaseName = "";
        private String currentUser = "";
        private String currentSchema = "";
        private String expectedSchemaStatus = "not checked";
        private long executed;
        private long succeeded;
        private long failed;
        private long actionableFailed;
        private long ignoredFailed;
        private long skipped;
        private long batchSeparatorsSkipped;
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
                         "SELECT DB_NAME(), SUSER_SNAME(), SCHEMA_NAME()")) {
                if (result.next()) {
                    databaseName = safe(result.getString(1));
                    currentUser = safe(result.getString(2));
                    currentSchema = safe(result.getString(3));
                }
            }
        }

        void readExpectedSchemaInfo(Connection connection) throws SQLException {
            if (config.expectedSchema().isBlank()) {
                expectedSchemaStatus = "not configured";
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT USER_NAME(schema_id) FROM sys.schemas WHERE name = ?")) {
                statement.setString(1, config.expectedSchema());
                try (ResultSet result = statement.executeQuery()) {
                    expectedSchemaStatus = result.next()
                            ? "exists; owner=" + result.getString(1)
                            : "absent before run; scripts may create it";
                }
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
            String state = normalizedState(exception.getSQLState());
            int errorCode = exception.getErrorCode();
            boolean ignored = config.ignoredSqlStates().contains(state)
                    || config.ignoredErrorCodes().contains(errorCode);
            if (ignored) ignoredFailed++; else actionableFailed++;
            sqlStates.merge(state.isBlank() ? "NO_SQLSTATE" : state, 1L, Long::sum);
            errorCodes.merge(errorCode, 1L, Long::sum);

            errors.add(new ErrorRow(
                    fileSequence,
                    relative(config.root(), file),
                    statementIndex,
                    unit.startLine(),
                    type.name(),
                    objectName,
                    state,
                    errorCode,
                    category(state, errorCode),
                    ignored,
                    duration.toMillis(),
                    safe(exception.getMessage()),
                    excerpt(unit.sql(), 1200)));
            return !ignored;
        }

        void addCleanupError(
                Path file, int fileSequence, String sql, String objectName, SQLException exception) {
            String state = normalizedState(exception.getSQLState());
            int errorCode = exception.getErrorCode();
            boolean ignored = config.ignoredSqlStates().contains(state)
                    || config.ignoredErrorCodes().contains(errorCode);
            if (ignored) ignoredFailed++; else actionableFailed++;
            sqlStates.merge(state.isBlank() ? "NO_SQLSTATE" : state, 1L, Long::sum);
            errorCodes.merge(errorCode, 1L, Long::sum);
            errors.add(new ErrorRow(
                    fileSequence, relative(config.root(), file), 0, 0,
                    StatementType.CLEANUP.name(), objectName, state, errorCode,
                    category(state, errorCode), ignored, 0,
                    safe(exception.getMessage()), excerpt(sql, 1200)));
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
                    "", 0, "FILE_OR_PARSER_ERROR", false, 0, rootMessage(exception), ""));
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
            writeErrors(directory.resolve("sqlserver-sql-execution-errors.csv"));
            writeFiles(directory.resolve("sqlserver-sql-execution-files.csv"));
            writeSummary(directory.resolve("sqlserver-sql-execution-summary.txt"));
        }

        void printSummary() {
            System.out.println("============================================================");
            System.out.println("Files discovered     : " + discoveredFiles);
            System.out.println("Statements executed  : " + executed);
            System.out.println("Statements succeeded : " + succeeded);
            System.out.println("Statements failed    : " + failed);
            System.out.println("Actionable failures  : " + actionableFailed);
            System.out.println("Ignored failures     : " + ignoredFailed);
            System.out.println("Statements skipped   : " + skipped);
            System.out.println("batch separators skipped: " + batchSeparatorsSkipped);
            System.out.println("Cleanup attempted    : " + cleanupAttempted);
            System.out.println("Cleanup succeeded    : " + cleanupSucceeded);
            System.out.println("Cleanup failed       : " + cleanupFailed);
            System.out.println("Execution mode       : " + config.executionMode());
            System.out.println("Elapsed              : " + elapsed);
            System.out.println("Reports              : " + directory.toAbsolutePath());
            System.out.println("============================================================");
        }

        private void writeErrors(Path output) throws IOException {
            StringBuilder csv = new StringBuilder("\uFEFF")
                    .append("file_sequence,file,statement_index,start_line,statement_type,")
                    .append("object_name,sql_state,error_code,category,ignored,elapsed_ms,message,sql_excerpt\n");
            for (ErrorRow row : errors) {
                csv.append(row.fileSequence()).append(',')
                        .append(csv(row.file())).append(',')
                        .append(row.statementIndex()).append(',')
                        .append(row.startLine()).append(',')
                        .append(row.statementType()).append(',')
                        .append(csv(row.objectName())).append(',')
                        .append(csv(row.sqlState())).append(',')
                        .append(row.errorCode()).append(',')
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
                    .append("SQL Server SQL execution summary\n")
                    .append("================================\n")
                    .append("Database product      : ").append(database).append('\n')
                    .append("Database version      : ").append(databaseVersion).append('\n')
                    .append("Database name         : ").append(databaseName).append('\n')
                    .append("Current user          : ").append(currentUser).append('\n')
                    .append("Current schema        : ").append(currentSchema).append('\n')
                    .append("Expected schema       : ").append(config.expectedSchema()).append('\n')
                    .append("Expected schema status: ").append(expectedSchemaStatus).append('\n')
                    .append("Root directory        : ").append(config.root()).append('\n')
                    .append("File suffix           : ").append(config.fileSuffix()).append('\n')
                    .append("Files discovered      : ").append(discoveredFiles).append('\n')
                    .append("Statements executed   : ").append(executed).append('\n')
                    .append("Statements succeeded  : ").append(succeeded).append('\n')
                    .append("Statements failed     : ").append(failed).append('\n')
                    .append("Actionable failures   : ").append(actionableFailed).append('\n')
                    .append("Ignored failures      : ").append(ignoredFailed).append('\n')
                    .append("Statements skipped    : ").append(skipped).append('\n')
                    .append("batch separators skipped : ").append(batchSeparatorsSkipped).append('\n')
                    .append("Cleanup attempted     : ").append(cleanupAttempted).append('\n')
                    .append("Cleanup succeeded     : ").append(cleanupSucceeded).append('\n')
                    .append("Cleanup failed        : ").append(cleanupFailed).append('\n')
                    .append("Execution mode        : ").append(config.executionMode()).append('\n')
                    .append("Stop after CREATE err : ").append(config.stopAfterCreateTableFailure()).append('\n')
                    .append("Drop before CREATE    : ").append(config.dropBeforeCreate()).append('\n')
                    .append("Elapsed               : ").append(elapsed).append("\n\n")
                    .append("Errors by SQLSTATE\n")
                    .append("------------------\n");

            sqlStates.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(entry -> text.append(String.format(Locale.ROOT,
                            "%s : %,d%n", entry.getKey(), entry.getValue())));
            if (sqlStates.isEmpty()) text.append("No SQL Server errors.\n");

            text.append("\nErrors by SQL Server code\n")
                    .append("-------------------------\n");
            errorCodes.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                    .forEach(entry -> text.append(String.format(Locale.ROOT,
                            "%d : %,d%n", entry.getKey(), entry.getValue())));
            if (errorCodes.isEmpty()) text.append("No SQL Server errors.\n");

            if (!fatalMessages.isEmpty()) {
                text.append("\nFatal errors\n------------\n");
                fatalMessages.forEach(value -> text.append(value).append('\n'));
            }
            Files.writeString(output, text, StandardCharsets.UTF_8);
        }

        private static String category(String state, int errorCode) {
            String value = normalizedState(state);
            if (value.startsWith("08")) return "CONNECTION_ERROR";
            return switch (errorCode) {
                case 102, 156, 319 -> "SYNTAX_OR_INVALID_DDL";
                case 207 -> "INVALID_IDENTIFIER";
                case 208, 4902 -> "MISSING_TABLE_OR_OBJECT";
                case 2714, 2759 -> "DUPLICATE_OBJECT";
                case 1913 -> "DUPLICATE_INDEX";
                case 1776 -> "FK_TARGET_NOT_UNIQUE";
                case 1778 -> "FK_COLUMN_TYPE_MISMATCH";
                case 1767 -> "FK_TARGET_TABLE_INVALID";
                case 1785 -> "MULTIPLE_CASCADE_PATHS";
                case 1750 -> "CONSTRAINT_OR_INDEX_CREATION_FAILED";
                case 547 -> "CONSTRAINT_VIOLATION";
                case 2601, 2627 -> "UNIQUE_VIOLATION";
                case 8115 -> "NUMERIC_VALUE_OUT_OF_RANGE";
                case 8152, 2628 -> "VALUE_TOO_LARGE";
                case 245 -> "CONVERSION_ERROR";
                case 241 -> "INVALID_DATETIME";
                case 2760 -> "MISSING_SCHEMA_OR_PERMISSION";
                case 229 -> "INSUFFICIENT_PRIVILEGES";
                case 15151, 15401 -> "MISSING_OBJECT_OR_PRINCIPAL";
                case 3726 -> "DEPENDENT_OBJECTS_EXIST";
                case 3701 -> "MISSING_DROP_TARGET";
                case 195 -> "FUNCTION_NOT_SUPPORTED_OR_UNKNOWN";
                default -> switch (value) {
                    case "42000" -> "SYNTAX_OR_ACCESS_RULE";
                    case "23000" -> "INTEGRITY_CONSTRAINT";
                    case "22001" -> "VALUE_TOO_LARGE";
                    case "22003" -> "NUMERIC_VALUE_OUT_OF_RANGE";
                    case "28000" -> "AUTHENTICATION_OR_AUTHORIZATION";
                    default -> value.isBlank() ? "JDBC_OR_UNKNOWN_ERROR" : "OTHER_SQLSERVER_ERROR";
                };
            };
        }

        private static String excerpt(String sql, int limit) {
            String value = safe(sql).replaceAll("\\s+", " ").trim();
            return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
        }

        private static String csv(String value) {
            return '"' + safe(value).replace("\"", "\"\"") + '"';
        }
    }

    private record Config(
            Path root,
            String url,
            String user,
            String password,
            Path reportBase,
            String expectedSchema,
            String fileSuffix,
            boolean dropBeforeCreate,
            boolean confirmDestructive,
            boolean failOnErrors,
            Set<String> ignoredSqlStates,
            Set<Integer> ignoredErrorCodes,
            Set<StatementType> skippedTypes,
            ExecutionMode executionMode,
            boolean stopAfterCreateTableFailure,
            int statementTimeoutSeconds,
            int loginTimeoutSeconds,
            int progressEveryFiles,
            int maxFiles,
            boolean enabled) {

        static Config load() {
            String root = setting("sqlserver.sql.root", "SQLSERVER_SQL_ROOT");
            String url = setting("sqlserver.jdbc.url", "SQLSERVER_JDBC_URL");
            String user = setting("sqlserver.jdbc.user", "SQLSERVER_JDBC_USER");
            boolean enabled = !root.isBlank() || !url.isBlank() || !user.isBlank();

            return new Config(
                    root.isBlank() ? Path.of(".") : Path.of(root).toAbsolutePath().normalize(),
                    url,
                    user,
                    secretSetting("sqlserver.jdbc.password", "SQLSERVER_JDBC_PASSWORD"),
                    Path.of(value("sqlserver.sql.report.dir",
                            "target/sqlserver-sql-execution-report")).toAbsolutePath().normalize(),
                    value("sqlserver.sql.expectedSchema", ""),
                    value("sqlserver.sql.fileSuffix", ".sqlserver.sql"),
                    bool("sqlserver.sql.dropBeforeCreate", false),
                    bool("sqlserver.sql.confirmDestructive", false),
                    bool("sqlserver.sql.failOnErrors", false),
                    stringSet(value("sqlserver.sql.ignoreSqlStates", "")),
                    integerSet(value("sqlserver.sql.ignoreErrorCodes", "")),
                    typeSet(value("sqlserver.sql.skipStatementTypes", "")),
                    ExecutionMode.parse(value("sqlserver.sql.executionMode", "HISTORICAL")),
                    bool("sqlserver.sql.stopAfterCreateTableFailure", true),
                    integer("sqlserver.sql.statementTimeoutSeconds", 60),
                    integer("sqlserver.sql.loginTimeoutSeconds", 20),
                    integer("sqlserver.sql.progressEveryFiles", 100),
                    integer("sqlserver.sql.maxFiles", 0),
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
                    "sqlserver.sql.root is not a directory: " + root);
            if (url.isBlank()) throw new IllegalArgumentException("sqlserver.jdbc.url is required");
            if (!url.startsWith("jdbc:sqlserver:")) throw new IllegalArgumentException(
                    "sqlserver.jdbc.url must start with jdbc:sqlserver:");
            if (user.isBlank()) throw new IllegalArgumentException("sqlserver.jdbc.user is required");
            if (fileSuffix.isBlank()) throw new IllegalArgumentException(
                    "sqlserver.sql.fileSuffix must not be blank");
            if (dropBeforeCreate && !confirmDestructive) throw new IllegalStateException(
                    "dropBeforeCreate requires sqlserver.sql.confirmDestructive=true; "
                            + "use only a disposable schema/database");
            if (dropBeforeCreate && expectedSchema.isBlank()) throw new IllegalStateException(
                    "dropBeforeCreate also requires sqlserver.sql.expectedSchema");
            if (!expectedSchema.isBlank() && !expectedSchema.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException(
                        "sqlserver.sql.expectedSchema must be an unquoted SQL Server identifier");
            }
            if (statementTimeoutSeconds < 1 || loginTimeoutSeconds < 1
                    || progressEveryFiles < 1 || maxFiles < 0) {
                throw new IllegalArgumentException("SQL Server test numeric properties are invalid");
            }
        }

        private static String setting(String property, String environment) {
            String result = System.getProperty(property);
            if (result == null || result.isBlank()) result = System.getenv(environment);
            return result == null ? "" : result.trim();
        }

        private static String secretSetting(String property, String environment) {
            String result = System.getProperty(property);
            if (result == null) result = System.getenv(environment);
            return result == null ? "" : result;
        }

        private static String value(String property, String defaultValue) {
            String result = System.getProperty(property);
            return result == null || result.isBlank() ? defaultValue : result.trim();
        }

        private static boolean bool(String property, boolean defaultValue) {
            return Boolean.parseBoolean(value(property, Boolean.toString(defaultValue)));
        }

        private static int integer(String property, int defaultValue) {
            return Integer.parseInt(value(property, Integer.toString(defaultValue)));
        }

        private static Set<String> stringSet(String value) {
            if (value.isBlank()) return Set.of();
            Set<String> result = new LinkedHashSet<>();
            for (String token : value.split(",")) {
                String normalized = normalizedState(token);
                if (!normalized.isBlank()) result.add(normalized);
            }
            return Set.copyOf(result);
        }

        private static Set<Integer> integerSet(String value) {
            if (value.isBlank()) return Set.of();
            Set<Integer> result = new LinkedHashSet<>();
            for (String token : value.split(",")) {
                if (!token.isBlank()) result.add(Integer.parseInt(token.trim()));
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

    private static String normalizedState(String value) {
        return safe(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
