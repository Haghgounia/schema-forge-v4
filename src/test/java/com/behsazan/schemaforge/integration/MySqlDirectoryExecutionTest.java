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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Executes generated MySQL DDL recursively through JDBC and writes durable execution reports.
 *
 * <p>The test is inactive during ordinary builds. It runs only when mysql.sql.root,
 * mysql.jdbc.url and mysql.jdbc.user are supplied. HISTORICAL mode skips physical
 * cross-table foreign keys and GRANT statements so historical versions of independently
 * generated table scripts can be validated without requiring a dependency-complete schema.</p>
 *
 * <p>Use only a disposable validation server/database when dropBeforeCreate=true.</p>
 */
class MySqlDirectoryExecutionTest {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String IDENTIFIER =
            "(?:`(?:[^`]|``)+`|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + QUALIFIED_NAME + ")");
    private static final Pattern CREATE_DATABASE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:DATABASE|SCHEMA)\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + IDENTIFIER + ")");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(" + IDENTIFIER + ")");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void executesGeneratedMysqlDirectory() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "MySQL directory execution disabled; provide mysql.sql.root, mysql.jdbc.url and mysql.jdbc.user");
        config.validate();

        Instant runStarted = Instant.now();
        List<Path> discoveredFiles = findSqlFiles(config.root(), config.fileSuffix());
        List<SelectedFile> files = selectFiles(discoveredFiles, config);
        String runId = RUN_ID.format(LocalDateTime.now());
        Path reportDir = Path.of("target", "mysql-sql-execution-report", runId)
                .toAbsolutePath().normalize();
        RunReport report = new RunReport(config, reportDir, discoveredFiles.size(), files.size());
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                report.readDatabaseInfo(connection);
                report.readExpectedDatabaseInfo(connection);

                int selectedDone = 0;
                for (SelectedFile selected : files) {
                    selectedDone++;
                    executeFile(connection, config, report, selected.file(), selected.sequence());
                    if (selectedDone % config.progressEveryFiles() == 0 || selectedDone == files.size()) {
                        System.out.printf(Locale.ROOT,
                                "MySQL scripts: %,d / %,d, selected=%,d / %,d, statements=%,d, errors=%,d%n",
                                selected.sequence(), discoveredFiles.size(), selectedDone, files.size(),
                                report.executed(), report.failed());
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
        if (config.failOnErrors() && report.actionableFailed() > 0) {
            fail("MySQL execution completed with " + report.actionableFailed()
                    + " actionable failures. Report: " + reportDir);
        }
    }

    private void executeFile(Connection connection, Config config, RunReport report,
                             Path file, int fileSequence) throws Exception {
        Instant fileStarted = Instant.now();
        int succeeded = 0;
        int failed = 0;
        int actionable = 0;
        int ignored = 0;
        int skipped = 0;
        List<String> statements;
        Set<String> tables = new LinkedHashSet<>();

        try {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            statements = splitter.parse(script, DatabasePlatform.MYSQL);
            for (String sql : statements) {
                Matcher table = CREATE_TABLE.matcher(stripLeadingComments(sql));
                if (table.find()) tables.add(normalizeName(table.group(1)));
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
            for (String rawSql : statements) {
                statementIndex++;
                String sql = rawSql.trim();
                StatementType type = StatementType.of(sql);
                if (config.shouldSkip(type)) {
                    skipped++;
                    report.skipped++;
                    continue;
                }

                report.executed++;
                Instant started = Instant.now();
                try {
                    jdbc.execute(sql);
                    succeeded++;
                    report.succeeded++;
                } catch (SQLException exception) {
                    failed++;
                    report.failed++;
                    boolean isActionable = report.addSqlError(
                            file, fileSequence, statementIndex, type, objectName(sql), exception,
                            Duration.between(started, Instant.now()), sql);
                    if (isActionable) actionable++; else ignored++;
                    if (connectionFailure(exception)) throw exception;
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
                Duration.between(fileStarted, Instant.now()).toMillis(), String.join("|", tables)));
    }

    private void dropTable(Connection connection, Config config, RunReport report,
                           Path file, int fileSequence, String table) throws SQLException {
        verifyDestructiveTableOwner(table, config.expectedDatabase());
        String sql = dropTableSql(table);
        report.cleanupAttempted++;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                statement.execute(sql);
                report.cleanupSucceeded++;
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (SQLException exception) {
            // MySQL error 1049 = unknown database. The script's CREATE DATABASE statement
            // will bootstrap it immediately after this pre-create cleanup attempt.
            if (exception.getErrorCode() == 1049) {
                report.cleanupSucceeded++;
                return;
            }
            report.cleanupFailed++;
            report.addCleanupError(file, fileSequence, sql, table, exception);
            if (connectionFailure(exception)) throw exception;
        }
    }

    static String dropTableSql(String table) {
        return "DROP TABLE IF EXISTS " + table;
    }

    private static List<Path> findSqlFiles(Path root, String suffix) throws IOException {
        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(normalizedSuffix))
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        }
    }

    private static List<SelectedFile> selectFiles(List<Path> discovered, Config config) {
        List<SelectedFile> selected = new ArrayList<>();
        for (int index = 0; index < discovered.size(); index++) {
            int sequence = index + 1;
            if (sequence < config.startFileNumber()) continue;
            if (!config.fileNumbers().isEmpty() && !config.fileNumbers().contains(sequence)) continue;
            selected.add(new SelectedFile(discovered.get(index), sequence));
            if (config.maxFiles() > 0 && selected.size() >= config.maxFiles()) break;
        }
        return List.copyOf(selected);
    }

    private static void verifyDestructiveTableOwner(String table, String expectedDatabase) {
        if (expectedDatabase.isBlank()) {
            throw new IllegalStateException("Destructive mode requires mysql.sql.expectedDatabase");
        }
        int separator = unquotedDot(table);
        if (separator < 0) {
            throw new IllegalStateException("Refusing to drop unqualified MySQL table: " + table);
        }
        String owner = unquoteIdentifier(table.substring(0, separator).trim());
        if (!owner.equalsIgnoreCase(expectedDatabase)) {
            throw new IllegalStateException("Refusing to drop MySQL table outside expected database "
                    + expectedDatabase + ": " + table);
        }
    }

    private static int unquotedDot(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '`') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '`') {
                    i++;
                    continue;
                }
                quoted = !quoted;
            } else if (ch == '.' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static String unquoteIdentifier(String value) {
        String text = value.trim();
        if (text.length() >= 2 && text.startsWith("`") && text.endsWith("`")) {
            return text.substring(1, text.length() - 1).replace("``", "`");
        }
        return text;
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String objectName(String sql) {
        String executable = stripLeadingComments(sql);
        for (Pattern pattern : List.of(CREATE_TABLE, CREATE_DATABASE, ALTER_TABLE, CREATE_INDEX)) {
            Matcher matcher = pattern.matcher(executable);
            if (matcher.find()) return normalizeName(matcher.group(1));
        }
        return "";
    }

    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql.trim();
        boolean changed;
        do {
            changed = false;
            if (value.startsWith("--")) {
                int newline = value.indexOf('\n');
                value = newline < 0 ? "" : value.substring(newline + 1).trim();
                changed = true;
            } else if (value.startsWith("/*")) {
                int end = value.indexOf("*/", 2);
                value = end < 0 ? "" : value.substring(end + 2).trim();
                changed = true;
            }
        } while (changed);
        return value;
    }

    private static boolean connectionFailure(SQLException exception) {
        String state = safe(exception.getSQLState());
        return state.startsWith("08");
    }

    private static String relative(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String csv(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String v = safe(value).replace("\r", " ").replace("\n", " ");
            escaped.add("\"" + v.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private enum StatementType {
        CREATE_DATABASE,
        CREATE_TABLE,
        FOREIGN_KEY,
        GRANT,
        ALTER_TABLE,
        CREATE_INDEX,
        OTHER;

        static StatementType of(String sql) {
            String executable = stripLeadingComments(sql).trim().toUpperCase(Locale.ROOT);
            if (executable.startsWith("CREATE DATABASE") || executable.startsWith("CREATE SCHEMA")) {
                return CREATE_DATABASE;
            }
            if (executable.startsWith("CREATE TABLE") || executable.startsWith("CREATE TEMPORARY TABLE")) {
                return CREATE_TABLE;
            }
            if (executable.startsWith("GRANT ")) return GRANT;
            if (executable.startsWith("ALTER TABLE") && executable.contains("FOREIGN KEY")) return FOREIGN_KEY;
            if (executable.startsWith("ALTER TABLE")) return ALTER_TABLE;
            if (executable.startsWith("CREATE INDEX") || executable.startsWith("CREATE UNIQUE INDEX")) {
                return CREATE_INDEX;
            }
            return OTHER;
        }
    }

    private enum ExecutionMode {
        HISTORICAL,
        FULL;

        static ExecutionMode parse(String raw) {
            try {
                return valueOf(safe(raw).trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                throw new IllegalArgumentException("mysql.sql.executionMode must be HISTORICAL or FULL: " + raw);
            }
        }
    }

    private record SelectedFile(Path file, int sequence) {}

    private record FileRow(int sequence, String file, String status, int totalStatements,
                           int succeeded, int failed, int actionable, int ignored, int skipped,
                           long elapsedMillis, String tables) {}

    private record ErrorRow(String category, int fileSequence, String file, int statementIndex,
                            String statementType, String objectName, String sqlState, int errorCode,
                            String message, long elapsedMillis, String sql) {}

    private record Config(Path root, String fileSuffix, String url, String user, String password,
                          String expectedDatabase, ExecutionMode executionMode,
                          boolean dropBeforeCreate, boolean confirmDestructive, boolean failOnErrors,
                          boolean stopAfterCreateTableFailure, int statementTimeoutSeconds,
                          int loginTimeoutSeconds, int progressEveryFiles, int startFileNumber,
                          int maxFiles, Set<Integer> fileNumbers) {

        static Config fromSystemProperties() {
            String root = trimToNull(System.getProperty("mysql.sql.root"));
            String url = trimToNull(System.getProperty("mysql.jdbc.url"));
            String user = trimToNull(System.getProperty("mysql.jdbc.user"));
            String password = firstNonBlank(System.getProperty("mysql.jdbc.password"),
                    System.getenv("MYSQL_JDBC_PASSWORD"), "");
            String expected = firstNonBlank(System.getProperty("mysql.sql.expectedDatabase"), "TSTSHMA");
            return new Config(
                    root == null ? null : Path.of(root).toAbsolutePath().normalize(),
                    firstNonBlank(System.getProperty("mysql.sql.fileSuffix"), ".mysql.sql"),
                    url, user, password, expected,
                    ExecutionMode.parse(firstNonBlank(System.getProperty("mysql.sql.executionMode"), "HISTORICAL")),
                    Boolean.parseBoolean(System.getProperty("mysql.sql.dropBeforeCreate", "false")),
                    Boolean.parseBoolean(System.getProperty("mysql.sql.confirmDestructive", "false")),
                    Boolean.parseBoolean(System.getProperty("mysql.sql.failOnErrors", "true")),
                    Boolean.parseBoolean(System.getProperty("mysql.sql.stopAfterCreateTableFailure", "true")),
                    positiveInt("mysql.sql.statementTimeoutSeconds", 60),
                    positiveInt("mysql.sql.loginTimeoutSeconds", 15),
                    positiveInt("mysql.sql.progressEveryFiles", 100),
                    positiveInt("mysql.sql.startFileNumber", 1),
                    nonNegativeInt("mysql.sql.maxFiles", 0),
                    parseFileNumbers(System.getProperty("mysql.sql.fileNumbers")));
        }

        boolean enabled() {
            return root != null && url != null && user != null;
        }

        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("mysql.sql.root is not a directory: " + root);
            if (fileSuffix.isBlank()) throw new IllegalArgumentException("mysql.sql.fileSuffix must not be blank");
            if (dropBeforeCreate && !confirmDestructive) {
                throw new IllegalArgumentException("dropBeforeCreate=true requires mysql.sql.confirmDestructive=true");
            }
            if (dropBeforeCreate && expectedDatabase.isBlank()) {
                throw new IllegalArgumentException("dropBeforeCreate=true requires mysql.sql.expectedDatabase");
            }
        }

        boolean shouldSkip(StatementType type) {
            return executionMode == ExecutionMode.HISTORICAL
                    && (type == StatementType.FOREIGN_KEY || type == StatementType.GRANT);
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
        private final Map<String, Integer> sqlStates = new TreeMap<>();
        private final Map<Integer, Integer> errorCodes = new TreeMap<>();
        private final Set<Integer> createTableErrorFiles = new LinkedHashSet<>();
        private long executed;
        private long succeeded;
        private long failed;
        private long skipped;
        private long cleanupAttempted;
        private long cleanupSucceeded;
        private long cleanupFailed;
        private Duration elapsed = Duration.ZERO;
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String currentDatabase = "";
        private String currentUser = "";
        private String expectedDatabaseStatus = "not checked";

        RunReport(Config config, Path reportDir, int discoveredFiles, int selectedFiles) {
            this.config = config;
            this.reportDir = reportDir;
            this.discoveredFiles = discoveredFiles;
            this.selectedFiles = selectedFiles;
        }

        long executed() { return executed; }
        long failed() { return failed; }
        boolean hasCreateTableError(int sequence) { return createTableErrorFiles.contains(sequence); }

        long actionableFailed() {
            return errors.stream().filter(row -> row.category.equals("SQL") || row.category.equals("FILE"))
                    .count() + cleanupFailed;
        }

        void readDatabaseInfo(Connection connection) throws SQLException {
            DatabaseMetaData meta = connection.getMetaData();
            databaseProduct = safe(meta.getDatabaseProductName());
            databaseVersion = safe(meta.getDatabaseProductVersion());
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
                if (rs.next()) {
                    currentDatabase = safe(rs.getString(1));
                    currentUser = safe(rs.getString(2));
                }
            }
        }

        void readExpectedDatabaseInfo(Connection connection) {
            String sql = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, config.expectedDatabase());
                try (ResultSet rs = ps.executeQuery()) {
                    expectedDatabaseStatus = rs.next() ? "exists" : "absent at start";
                }
            } catch (SQLException exception) {
                expectedDatabaseStatus = "lookup failed: " + safe(exception.getMessage());
            }
        }

        boolean addSqlError(Path file, int fileSequence, int statementIndex, StatementType type,
                            String objectName, SQLException exception, Duration statementElapsed, String sql) {
            String state = safe(exception.getSQLState());
            sqlStates.merge(state.isBlank() ? "<blank>" : state, 1, Integer::sum);
            errorCodes.merge(exception.getErrorCode(), 1, Integer::sum);
            if (type == StatementType.CREATE_TABLE) createTableErrorFiles.add(fileSequence);
            errors.add(new ErrorRow("SQL", fileSequence, relative(config.root(), file), statementIndex,
                    type.name(), objectName, state, exception.getErrorCode(), safe(exception.getMessage()),
                    statementElapsed.toMillis(), sql));
            return true;
        }

        void addCleanupError(Path file, int fileSequence, String sql, String objectName, SQLException exception) {
            String state = safe(exception.getSQLState());
            sqlStates.merge(state.isBlank() ? "<blank>" : state, 1, Integer::sum);
            errorCodes.merge(exception.getErrorCode(), 1, Integer::sum);
            errors.add(new ErrorRow("CLEANUP", fileSequence, relative(config.root(), file), 0,
                    "DROP_TABLE", objectName, state, exception.getErrorCode(), safe(exception.getMessage()), 0L, sql));
        }

        void addFileError(Path file, int fileSequence, Exception exception) {
            errors.add(new ErrorRow("FILE", fileSequence, relative(config.root(), file), 0,
                    "READ_OR_PARSE", "", "", 0,
                    exception.getClass().getSimpleName() + ": " + safe(exception.getMessage()), 0L, ""));
            files.add(new FileRow(fileSequence, relative(config.root(), file), "FILE_ERROR",
                    0, 0, 0, 1, 0, 0, 0L, ""));
        }

        void write() throws IOException {
            Files.createDirectories(reportDir);
            List<String> fileLines = new ArrayList<>();
            fileLines.add("sequence,file,status,statements_total,succeeded,failed,actionable,ignored,skipped,elapsed_ms,tables");
            for (FileRow row : files) {
                fileLines.add(csv(Integer.toString(row.sequence), row.file, row.status,
                        Integer.toString(row.totalStatements), Integer.toString(row.succeeded),
                        Integer.toString(row.failed), Integer.toString(row.actionable), Integer.toString(row.ignored),
                        Integer.toString(row.skipped), Long.toString(row.elapsedMillis), row.tables));
            }
            Files.writeString(reportDir.resolve("mysql-sql-execution-files.csv"),
                    String.join(System.lineSeparator(), fileLines) + System.lineSeparator(), StandardCharsets.UTF_8);

            List<String> errorLines = new ArrayList<>();
            errorLines.add("category,file_sequence,file,statement_index,statement_type,object_name,sql_state,error_code,message,elapsed_ms,sql");
            for (ErrorRow row : errors) {
                errorLines.add(csv(row.category, Integer.toString(row.fileSequence), row.file,
                        Integer.toString(row.statementIndex), row.statementType, row.objectName, row.sqlState,
                        Integer.toString(row.errorCode), row.message, Long.toString(row.elapsedMillis), row.sql));
            }
            Files.writeString(reportDir.resolve("mysql-sql-execution-errors.csv"),
                    String.join(System.lineSeparator(), errorLines) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.writeString(reportDir.resolve("mysql-sql-execution-summary.txt"), summaryText(), StandardCharsets.UTF_8);
        }

        void printSummary() {
            System.out.println("============================================================");
            System.out.printf(Locale.ROOT, "Files discovered     : %d%n", discoveredFiles);
            System.out.printf(Locale.ROOT, "Start file number    : %d%n", config.startFileNumber());
            if (!config.fileNumbers().isEmpty()) {
                System.out.println("File number filter   : " + joinNumbers(config.fileNumbers()));
            }
            System.out.printf(Locale.ROOT, "Files selected       : %d%n", selectedFiles);
            System.out.printf(Locale.ROOT, "Statements executed  : %d%n", executed);
            System.out.printf(Locale.ROOT, "Statements succeeded : %d%n", succeeded);
            System.out.printf(Locale.ROOT, "Statements failed    : %d%n", failed);
            System.out.printf(Locale.ROOT, "Actionable failures  : %d%n", actionableFailed());
            System.out.printf(Locale.ROOT, "Statements skipped   : %d%n", skipped);
            System.out.printf(Locale.ROOT, "Cleanup attempted    : %d%n", cleanupAttempted);
            System.out.printf(Locale.ROOT, "Cleanup succeeded    : %d%n", cleanupSucceeded);
            System.out.printf(Locale.ROOT, "Cleanup failed       : %d%n", cleanupFailed);
            System.out.println("Execution mode       : " + config.executionMode());
            System.out.println("Elapsed              : " + elapsed);
            System.out.println("Reports              : " + reportDir);
            System.out.println("============================================================");
        }

        private String summaryText() {
            StringBuilder s = new StringBuilder();
            s.append("MySQL SQL execution summary\n")
                    .append("===========================\n")
                    .append("Database product      : ").append(databaseProduct).append('\n')
                    .append("Database version      : ").append(databaseVersion).append('\n')
                    .append("Current database      : ").append(currentDatabase).append('\n')
                    .append("Current user          : ").append(currentUser).append('\n')
                    .append("Expected database     : ").append(config.expectedDatabase()).append('\n')
                    .append("Expected db status    : ").append(expectedDatabaseStatus).append('\n')
                    .append("Root directory        : ").append(config.root()).append('\n')
                    .append("File suffix           : ").append(config.fileSuffix()).append('\n')
                    .append("Files discovered      : ").append(discoveredFiles).append('\n')
                    .append("Start file number     : ").append(config.startFileNumber()).append('\n')
                    .append("File number filter    : ").append(joinNumbers(config.fileNumbers())).append('\n')
                    .append("Files selected        : ").append(selectedFiles).append('\n')
                    .append("Statements executed   : ").append(executed).append('\n')
                    .append("Statements succeeded  : ").append(succeeded).append('\n')
                    .append("Statements failed     : ").append(failed).append('\n')
                    .append("Actionable failures   : ").append(actionableFailed()).append('\n')
                    .append("Statements skipped    : ").append(skipped).append('\n')
                    .append("Cleanup attempted     : ").append(cleanupAttempted).append('\n')
                    .append("Cleanup succeeded     : ").append(cleanupSucceeded).append('\n')
                    .append("Cleanup failed        : ").append(cleanupFailed).append('\n')
                    .append("Execution mode        : ").append(config.executionMode()).append('\n')
                    .append("Stop after CREATE err : ").append(config.stopAfterCreateTableFailure()).append('\n')
                    .append("Drop before CREATE    : ").append(config.dropBeforeCreate()).append('\n')
                    .append("Elapsed               : ").append(elapsed).append("\n\n")
                    .append("Errors by SQLSTATE\n------------------\n");
            if (sqlStates.isEmpty()) s.append("No MySQL errors.\n");
            else sqlStates.forEach((key, value) -> s.append(key).append(" : ").append(value).append('\n'));
            s.append("\nErrors by MySQL code\n--------------------\n");
            if (errorCodes.isEmpty()) s.append("No MySQL errors.\n");
            else errorCodes.forEach((key, value) -> s.append(key).append(" : ").append(value).append('\n'));
            if (!fatalMessages.isEmpty()) {
                s.append("\nFatal errors\n------------\n");
                fatalMessages.forEach(value -> s.append(value).append('\n'));
            }
            return s.toString();
        }
    }

    private static int positiveInt(String property, int defaultValue) {
        String raw = trimToNull(System.getProperty(property));
        if (raw == null) return defaultValue;
        int value = Integer.parseInt(raw);
        if (value <= 0) throw new IllegalArgumentException(property + " must be > 0");
        return value;
    }

    private static int nonNegativeInt(String property, int defaultValue) {
        String raw = trimToNull(System.getProperty(property));
        if (raw == null) return defaultValue;
        int value = Integer.parseInt(raw);
        if (value < 0) throw new IllegalArgumentException(property + " must be >= 0");
        return value;
    }

    private static Set<Integer> parseFileNumbers(String raw) {
        String value = trimToNull(raw);
        if (value == null) return Set.of();
        Set<Integer> result = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            int number = Integer.parseInt(token.trim());
            if (number <= 0) throw new IllegalArgumentException("mysql.sql.fileNumbers must be positive: " + token);
            result.add(number);
        }
        return Set.copyOf(result);
    }

    private static String joinNumbers(Set<Integer> values) {
        return values.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) return trimmed;
        }
        return "";
    }
}
