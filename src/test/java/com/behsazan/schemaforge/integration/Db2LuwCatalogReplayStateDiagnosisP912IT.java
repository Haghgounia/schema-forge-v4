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
 * P9.1.2 read-only diagnosis that simulates the DB2 LUW HISTORICAL replay column state
 * statement-by-statement. Unlike P9.1/P9.1.1 it includes column-shape ALTER TABLE statements,
 * so catalog-only columns can be attributed to generated replay mutations instead of being
 * misclassified as unexplained catalog drift.
 */
class Db2LuwCatalogReplayStateDiagnosisP912IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")\\s+(.*)$");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?is)^ADD\\s+(?:COLUMN\\s+)?(" + IDENTIFIER + ")\\b");
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "(?is)^DROP\\s+COLUMN\\s+(" + IDENTIFIER + ")\\b");
    private static final Pattern RENAME_COLUMN = Pattern.compile(
            "(?is)^RENAME\\s+COLUMN\\s+(" + IDENTIFIER + ")\\s+TO\\s+(" + IDENTIFIER + ")\\b");
    private static final Set<String> NON_COLUMN_ADD_TOKENS = Set.of(
            "CONSTRAINT", "PRIMARY", "FOREIGN", "CHECK", "UNIQUE", "PARTITION", "SECURITY", "MATERIALIZED");
    private static final Set<String> NON_COLUMN_PREFIXES = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void simulatesHistoricalReplayColumnStateAndReconcilesCatalog() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.db2luw.p9.sqlRoot=<generated DB2 LUW root> to run P9.1.2.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) fail("No DB2 LUW SQL files found below " + config.root());

        Replay replay = simulateReplay(files, config.expectedSchema(), config.root());
        if (config.strictBaseline()) assertEquals(2310, replay.createDefinedTables,
                "P9.1.2 final table baseline changed");

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Instant started = Instant.now();
        Report report = new Report(config, reportDir, files.size(), replay);

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
            for (Map.Entry<String, TableState> entry : replay.states.entrySet()) {
                String table = entry.getKey();
                TableState expected = entry.getValue();
                List<String> actual = catalogColumns.get(table);
                if (actual == null) {
                    report.missingTables++;
                    report.rows.add(new ReconciliationRow(table, "MISSING_TABLE", expected.columns.size(), 0,
                            "", "", expected.lastCreateFile, expected.lastMutationFile));
                    continue;
                }
                if (expected.columns.equals(actual)) {
                    report.exactTables++;
                    continue;
                }
                report.mismatchTables++;
                Set<String> expectedSet = new LinkedHashSet<>(expected.columns);
                Set<String> actualSet = new LinkedHashSet<>(actual);
                List<String> missing = expected.columns.stream().filter(c -> !actualSet.contains(c)).toList();
                List<String> extra = actual.stream().filter(c -> !expectedSet.contains(c)).toList();
                String classification;
                if (missing.isEmpty() && !extra.isEmpty()) classification = "CATALOG_EXTRA_AFTER_REPLAY_SIMULATION";
                else if (!missing.isEmpty() && extra.isEmpty()) classification = "CATALOG_MISSING_AFTER_REPLAY_SIMULATION";
                else if (!missing.isEmpty()) classification = "MIXED_DRIFT_AFTER_REPLAY_SIMULATION";
                else classification = "ORDER_DRIFT_AFTER_REPLAY_SIMULATION";
                report.classifications.merge(classification, 1, Integer::sum);
                report.extraColumns += extra.size();
                report.missingColumns += missing.size();
                report.rows.add(new ReconciliationRow(table, classification, expected.columns.size(), actual.size(),
                        String.join("|", missing), String.join("|", extra),
                        expected.lastCreateFile, expected.lastMutationFile));
            }
        } finally {
            report.elapsed = Duration.between(started, Instant.now());
            report.write();
            report.printSummary();
        }

        if (report.missingTables > 0) {
            fail("DB2 LUW P9.1.2 found missing replay-defined tables. Report: " + reportDir);
        }
    }

    private Replay simulateReplay(List<Path> files, String expectedSchema, Path root) throws IOException {
        Map<String, TableState> states = new LinkedHashMap<>();
        List<MutationRow> mutations = new ArrayList<>();
        int createEvents = 0;
        int addEvents = 0;
        int dropEvents = 0;
        int renameEvents = 0;
        int relevantMutationEvents = 0;

        for (Path file : files) {
            String relative = normalize(root.relativize(file));
            String script = Files.readString(file, StandardCharsets.UTF_8);
            List<String> statements = splitter.parse(script, DatabasePlatform.DB2_LUW);
            int statementIndex = 0;
            for (String raw : statements) {
                statementIndex++;
                String sql = stripLeadingComments(raw).trim();
                Matcher create = CREATE_TABLE.matcher(sql);
                if (create.find()) {
                    ObjectName name = ObjectName.parse(normalizeName(create.group(1)), expectedSchema);
                    if (!name.owner.equalsIgnoreCase(expectedSchema)) continue;
                    List<String> columns = extractColumns(sql, create.end());
                    TableState state = new TableState(new ArrayList<>(columns), relative, relative);
                    states.put(name.name, state);
                    createEvents++;
                    continue;
                }

                Matcher alter = ALTER_TABLE.matcher(sql);
                if (!alter.find()) continue;
                ObjectName name = ObjectName.parse(normalizeName(alter.group(1)), expectedSchema);
                if (!name.owner.equalsIgnoreCase(expectedSchema)) continue;
                TableState state = states.get(name.name);
                if (state == null) continue;
                String action = alter.group(2).trim();

                Matcher rename = RENAME_COLUMN.matcher(action);
                if (rename.find()) {
                    String oldName = unquote(rename.group(1)).toUpperCase(Locale.ROOT);
                    String newName = unquote(rename.group(2)).toUpperCase(Locale.ROOT);
                    int index = state.columns.indexOf(oldName);
                    boolean applied = index >= 0;
                    if (applied) {
                        state.columns.set(index, newName);
                        state.lastMutationFile = relative;
                        relevantMutationEvents++;
                    }
                    renameEvents++;
                    mutations.add(new MutationRow(relative, statementIndex, name.name, "RENAME_COLUMN",
                            oldName, newName, applied, compactSql(sql)));
                    continue;
                }

                Matcher drop = DROP_COLUMN.matcher(action);
                if (drop.find()) {
                    String column = unquote(drop.group(1)).toUpperCase(Locale.ROOT);
                    boolean applied = state.columns.remove(column);
                    if (applied) {
                        state.lastMutationFile = relative;
                        relevantMutationEvents++;
                    }
                    dropEvents++;
                    mutations.add(new MutationRow(relative, statementIndex, name.name, "DROP_COLUMN",
                            column, "", applied, compactSql(sql)));
                    continue;
                }

                Matcher add = ADD_COLUMN.matcher(action);
                if (add.find()) {
                    String column = unquote(add.group(1)).toUpperCase(Locale.ROOT);
                    if (NON_COLUMN_ADD_TOKENS.contains(column)) continue;
                    boolean applied = !state.columns.contains(column);
                    if (applied) {
                        state.columns.add(column);
                        state.lastMutationFile = relative;
                        relevantMutationEvents++;
                    }
                    addEvents++;
                    mutations.add(new MutationRow(relative, statementIndex, name.name, "ADD_COLUMN",
                            column, "", applied, compactSql(sql)));
                }
            }
        }
        return new Replay(states, mutations, states.size(), createEvents, addEvents, dropEvents,
                renameEvents, relevantMutationEvents);
    }

    private static List<String> extractColumns(String createSql, int searchFrom) {
        int open = createSql.indexOf('(', searchFrom);
        if (open < 0) throw new IllegalArgumentException("CREATE TABLE has no body: " + compactSql(createSql));
        int close = matchingParen(createSql, open);
        if (close < 0) throw new IllegalArgumentException("CREATE TABLE has unclosed body: " + compactSql(createSql));
        String body = createSql.substring(open + 1, close);
        List<String> columns = new ArrayList<>();
        for (String element : splitTopLevel(body)) {
            String trimmed = element.trim();
            if (trimmed.isEmpty()) continue;
            String first = firstIdentifier(trimmed);
            if (first == null) continue;
            String normalized = unquote(first).toUpperCase(Locale.ROOT);
            if (NON_COLUMN_PREFIXES.contains(normalized)) continue;
            columns.add(normalized);
        }
        return List.copyOf(columns);
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
            throw new IllegalStateException("Refusing P9.1.2: expected DB " + expected + " but connected to " + actual);
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

    private static String normalizeName(String value) { return value.replaceAll("\\s*\\.\\s*", ".").trim(); }
    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }
    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String compactSql(String value) {
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

    private static final class TableState {
        private final List<String> columns;
        private final String lastCreateFile;
        private String lastMutationFile;
        private TableState(List<String> columns, String lastCreateFile, String lastMutationFile) {
            this.columns = columns;
            this.lastCreateFile = lastCreateFile;
            this.lastMutationFile = lastMutationFile;
        }
    }

    private record Replay(Map<String, TableState> states, List<MutationRow> mutations,
                          int createDefinedTables, int createEvents, int addEvents, int dropEvents,
                          int renameEvents, int relevantMutationEvents) { }
    private record MutationRow(String file, int statementIndex, String table, String mutation,
                               String column, String newColumn, boolean applied, String sql) { }
    private record ReconciliationRow(String table, String classification, int expectedColumns, int catalogColumns,
                                     String missingColumns, String extraColumns, String lastCreateFile,
                                     String lastMutationFile) { }

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
                    Path.of(System.getProperty("schemaforge.db2luw.p9.p912.reportBase",
                            "target/db2luw-p9.1.2-catalog-replay-state-diagnosis")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("P9.1.2 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("P9.1.2 expected schema required");
        }
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int filesDiscovered;
        private final Replay replay;
        private final List<ReconciliationRow> rows = new ArrayList<>();
        private final Map<String, Integer> classifications = new LinkedHashMap<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String database = "";
        private String authorizationId = "";
        private int exactTables;
        private int mismatchTables;
        private int missingTables;
        private int extraColumns;
        private int missingColumns;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, Replay replay) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.replay = replay;
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.1.2-replay-state-reconciliation.csv"), StandardCharsets.UTF_8)) {
                out.write("table,classification,expected_columns,catalog_columns,missing_columns,extra_columns,last_create_file,last_mutation_file\n");
                for (ReconciliationRow row : rows) {
                    out.write(csv(row.table()) + "," + csv(row.classification()) + "," + row.expectedColumns() + ","
                            + row.catalogColumns() + "," + csv(row.missingColumns()) + "," + csv(row.extraColumns()) + ","
                            + csv(row.lastCreateFile()) + "," + csv(row.lastMutationFile()) + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p9.1.2-column-mutations.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement_index,table,mutation,column,new_column,applied,sql\n");
                for (MutationRow row : replay.mutations()) {
                    out.write(csv(row.file()) + "," + row.statementIndex() + "," + csv(row.table()) + ","
                            + csv(row.mutation()) + "," + csv(row.column()) + "," + csv(row.newColumn()) + ","
                            + row.applied() + "," + csv(row.sql()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("db2luw-p9.1.2-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            StringBuilder out = new StringBuilder();
            out.append("DB2 LUW P9.1.2 Catalog Replay-State Diagnosis\n")
                    .append("============================================\n")
                    .append("Database product          : ").append(databaseProduct).append('\n')
                    .append("Database version          : ").append(databaseVersion).append('\n')
                    .append("Database                  : ").append(database.trim()).append('\n')
                    .append("Authorization ID          : ").append(authorizationId.trim()).append('\n')
                    .append("SQL root                  : ").append(config.root()).append('\n')
                    .append("Files discovered          : ").append(filesDiscovered).append('\n')
                    .append("Replay-defined tables     : ").append(replay.createDefinedTables()).append('\n')
                    .append("CREATE TABLE events       : ").append(replay.createEvents()).append('\n')
                    .append("ADD COLUMN events         : ").append(replay.addEvents()).append('\n')
                    .append("DROP COLUMN events        : ").append(replay.dropEvents()).append('\n')
                    .append("RENAME COLUMN events      : ").append(replay.renameEvents()).append('\n')
                    .append("Applied shape mutations   : ").append(replay.relevantMutationEvents()).append('\n')
                    .append("Exact replay/catalog shape: ").append(exactTables).append('\n')
                    .append("Mismatch tables           : ").append(mismatchTables).append('\n')
                    .append("Missing tables            : ").append(missingTables).append('\n')
                    .append("Extra catalog columns     : ").append(extraColumns).append('\n')
                    .append("Missing replay columns    : ").append(missingColumns).append("\n\n")
                    .append("Classifications:\n");
            classifications.forEach((key, value) ->
                    out.append("  ").append(String.format(Locale.ROOT, "%-45s", key)).append(value).append('\n'));
            out.append("\nMutation policy           : READ ONLY; GENERATED REPLAY SIMULATION ONLY\n")
                    .append("Replay policy             : FILE ORDER MATCHES Db2LuwDirectoryExecutionTest HISTORICAL ORDER\n")
                    .append("Elapsed                   : ").append(elapsed).append('\n')
                    .append("Report directory          : ").append(reportDir.toAbsolutePath()).append('\n');
            return out.toString();
        }

        void printSummary() { System.out.println(summary()); }
    }
}
