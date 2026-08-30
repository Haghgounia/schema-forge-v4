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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DB2 LUW P8 live final-state validation using only the evidence-confirmed P7 resolutions.
 *
 * <p>This test never rewrites the generated SQL corpus. Confirmed P7.1 table renames and P7.3
 * referenced-column renames are applied only in memory to the FK statement being validated.
 * Ambiguous aliases, missing parents, never-observed columns, and P7.4 rows without independent
 * unique-key evidence remain deferred. P7.5 rows are reported separately as final-version selection
 * defects because canonical key evidence exists but the historically selected final table version
 * does not expose the key in the live final state.</p>
 */
class Db2LuwFinalStateResolutionP8IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String P6_RESOURCE =
            "evidence/db2luw-p6/20260829_182000_267/db2luw-fk-structural-audit.csv";
    private static final String P71_RESOURCE =
            "evidence/db2luw-p7/20260829_184443_532/db2luw-fk-p7.1-name-alias-resolution.csv";
    private static final String P72_RESOURCE =
            "evidence/db2luw-p7/20260829_184443_532/db2luw-fk-p7.2-missing-parent-resolution.csv";
    private static final String P73_RESOURCE =
            "evidence/db2luw-p7/20260829_185906_952/db2luw-fk-p7.3-column-reference-audit.csv";

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
    void retainedP7EvidenceCoversTheEntireP6BlockerSet() throws Exception {
        ResolutionEvidence evidence = ResolutionEvidence.load();
        assertEquals(313, evidence.p6Rows().size(), "retained P6 row count");
        assertEquals(104, evidence.countP6("NAME_OR_ALIAS_DRIFT"));
        assertEquals(68, evidence.countP6("MISSING_FROM_GENERATED_CORPUS"));
        assertEquals(75, evidence.countP6("COLUMN_REFERENCE_OR_EXTRACTION_GAP"));
        assertEquals(50, evidence.countP6("KEY_MODEL_OR_EXTRACTION_GAP"));
        assertEquals(16, evidence.countP6("KEY_VERSION_DRIFT"));

        assertEquals(14, evidence.countP71("CONFIRMED_RENAME"));
        assertEquals(90, evidence.countP71("POSSIBLE_ALIAS"));
        assertEquals(47, evidence.countP72("CANONICAL_ABSENT"));
        assertEquals(21, evidence.countP72("EXTERNAL_OR_SHARED_DEPENDENCY"));
        assertEquals(64, evidence.countP73("COLUMN_RENAMED"));
        assertEquals(11, evidence.countP73("COLUMN_NEVER_EXISTED"));

        Map<Action, Long> actions = evidence.actionCountsForP6Rows();
        assertEquals(14L, actions.getOrDefault(Action.TABLE_RENAME_CONFIRMED, 0L));
        assertEquals(64L, actions.getOrDefault(Action.COLUMN_RENAME_CONFIRMED, 0L));
        assertEquals(16L, actions.getOrDefault(Action.KEY_VERSION_SELECTION_FIX_REQUIRED, 0L));
        assertEquals(90L, actions.getOrDefault(Action.BLOCK_POSSIBLE_ALIAS, 0L));
        assertEquals(47L, actions.getOrDefault(Action.BLOCK_CANONICAL_PARENT_ABSENT, 0L));
        assertEquals(21L, actions.getOrDefault(Action.EXTERNAL_SHARED_DEPENDENCY, 0L));
        assertEquals(11L, actions.getOrDefault(Action.BLOCK_COLUMN_NEVER_EXISTED, 0L));
        assertEquals(50L, actions.getOrDefault(Action.BLOCK_NO_INDEPENDENT_UNIQUE_EVIDENCE, 0L));
    }

    @Test
    void validatesEvidenceConfirmedP7ResolutionsAgainstLiveFinalState() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set schemaforge.db2luw.p8.sqlRoot to run P8 live final-state validation.");
        config.validate();

        ResolutionEvidence evidence = ResolutionEvidence.load();
        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) {
            fail("No DB2 LUW SQL files found below " + config.root() + " with suffix " + config.fileSuffix());
        }

        Map<String, TablePlan> finalPlans = loadFinalPlans(files);
        List<ForeignKeyUnit> foreignKeys = finalPlans.values().stream()
                .flatMap(plan -> plan.foreignKeys().stream())
                .toList();
        if (config.strictBaseline()) {
            assertEquals(2310, finalPlans.size(), "P8 final table baseline changed");
            assertEquals(557, foreignKeys.size(), "P8 final FK baseline changed");
        }

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Report report = new Report(config, reportDir, files.size(), finalPlans.size(), foreignKeys.size());
        Instant started = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName(config.driver());
            try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                verifyExpectedDatabase(connection, config.expectedDatabase());
                report.readDatabaseInfo(connection);

                int sequence = 0;
                for (ForeignKeyUnit original : foreignKeys) {
                    sequence++;
                    Decision decision = evidence.resolve(original);
                    report.plan(decision.action());
                    validateOne(connection, config, report, original, decision);
                    if (sequence % config.progressEveryFks() == 0 || sequence == foreignKeys.size()) {
                        System.out.printf(Locale.ROOT,
                                "DB2 LUW P8 FK: %,d / %,d, attempted=%,d, succeeded=%,d, errors=%,d, "
                                        + "live-blocked=%,d, deferred=%,d%n",
                                sequence, foreignKeys.size(), report.attempted, report.succeeded,
                                report.failed, report.liveBlocked, report.deferred);
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
            fail("DB2 LUW P8 completed with " + report.failed + " live FK execution errors. Report: " + reportDir);
        }
        if (config.failOnLiveBlockers() && report.liveBlocked > 0) {
            fail("DB2 LUW P8 completed with " + report.liveBlocked
                    + " post-resolution live blockers. Report: " + reportDir);
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
                if (create.find()) createdTable = normalizeName(create.group(1));
                Matcher fk = FOREIGN_KEY.matcher(sql);
                if (fk.find()) {
                    foreignKeys.add(new ForeignKeyUnit(
                            normalizeName(fk.group(1)), normalizeName(fk.group(2)),
                            parseIdentifierList(fk.group(3)), normalizeName(fk.group(4)),
                            parseIdentifierList(fk.group(5)), raw, statementIndex, file));
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

    private static void validateOne(
            Connection connection, Config config, Report report,
            ForeignKeyUnit original, Decision decision) throws SQLException {
        if (!decision.action().liveEligible()) {
            report.deferred++;
            report.deferredRows.add(new DeferredRow(
                    relative(config.root(), original.file()), original.statement(), original.sourceTable(),
                    original.constraintName(), joinIdentifiers(original.sourceColumns()),
                    original.referencedTable(), joinIdentifiers(original.referencedColumns()),
                    decision.action().name(), decision.evidence()));
            return;
        }

        ForeignKeyUnit fk = applyDecision(original, decision);
        if (!ownedByExpectedSchema(fk.sourceTable(), config.expectedSchema())
                || !ownedByExpectedSchema(fk.referencedTable(), config.expectedSchema())) {
            liveBlock(report, config, original, decision, fk, "OUTSIDE_EXPECTED_SCHEMA", "");
            return;
        }
        if (!tableExists(connection, fk.sourceTable(), config.expectedSchema())) {
            liveBlock(report, config, original, decision, fk, "SOURCE_TABLE_NOT_FOUND", "");
            return;
        }
        if (!tableExists(connection, fk.referencedTable(), config.expectedSchema())) {
            liveBlock(report, config, original, decision, fk, "REFERENCED_TABLE_NOT_FOUND", "");
            return;
        }
        List<String> missingSource = missingColumns(
                connection, fk.sourceTable(), fk.sourceColumns(), config.expectedSchema());
        if (!missingSource.isEmpty()) {
            liveBlock(report, config, original, decision, fk,
                    "SOURCE_COLUMN_NOT_FOUND", String.join("|", missingSource));
            return;
        }
        List<String> missingReferenced = missingColumns(
                connection, fk.referencedTable(), fk.referencedColumns(), config.expectedSchema());
        if (!missingReferenced.isEmpty()) {
            liveBlock(report, config, original, decision, fk,
                    "REFERENCED_COLUMN_NOT_FOUND", String.join("|", missingReferenced));
            return;
        }
        if (!referencedColumnsAreUniqueKey(
                connection, fk.referencedTable(), fk.referencedColumns(), config.expectedSchema())) {
            liveBlock(report, config, original, decision, fk,
                    "REFERENCED_COLUMNS_NOT_PK_OR_UNIQUE", "");
            return;
        }

        dropForeignKeyIfPresent(connection, fk.sourceTable(), fk.constraintName(), config.expectedSchema());
        report.attempted++;
        if (decision.action() != Action.BASELINE) report.resolutionAttempted++;
        Instant started = Instant.now();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute(fk.sql());
            report.succeeded++;
            if (decision.action() != Action.BASELINE) report.resolutionSucceeded++;
            report.successRows.add(new SuccessRow(
                    relative(config.root(), original.file()), original.statement(), original.sourceTable(),
                    original.constraintName(), decision.action().name(),
                    original.referencedTable(), joinIdentifiers(original.referencedColumns()),
                    fk.referencedTable(), joinIdentifiers(fk.referencedColumns()), decision.evidence()));
        } catch (SQLException exception) {
            report.failed++;
            report.errors.add(new ErrorRow(
                    relative(config.root(), original.file()), original.statement(), original.sourceTable(),
                    original.constraintName(), decision.action().name(), fk.referencedTable(),
                    joinIdentifiers(fk.referencedColumns()), exception.getErrorCode(), exception.getSQLState(),
                    oneLine(exception.getMessage()), Duration.between(started, Instant.now()).toMillis(),
                    oneLine(fk.sql()), decision.evidence()));
            if (connectionFailure(exception)) {
                throw new SQLRecoverableException(
                        "DB2 LUW connection failed while validating P8 FK from " + original.file(),
                        exception.getSQLState(), exception.getErrorCode(), exception);
            }
        } finally {
            try {
                dropForeignKeyIfPresent(connection, fk.sourceTable(), fk.constraintName(), config.expectedSchema());
            } catch (SQLException cleanup) {
                report.cleanupFailed++;
                report.cleanupErrors.add(new CleanupRow(
                        relative(config.root(), original.file()), original.statement(), original.sourceTable(),
                        original.constraintName(), cleanup.getErrorCode(), cleanup.getSQLState(),
                        oneLine(cleanup.getMessage())));
                if (connectionFailure(cleanup)) throw cleanup;
            }
        }
    }

    private static void liveBlock(
            Report report, Config config, ForeignKeyUnit original, Decision decision,
            ForeignKeyUnit resolved, String reason, String detail) {
        report.liveBlocked++;
        report.liveBlockers.add(new LiveBlockerRow(
                relative(config.root(), original.file()), original.statement(), original.sourceTable(),
                original.constraintName(), decision.action().name(),
                original.referencedTable(), joinIdentifiers(original.referencedColumns()),
                resolved.referencedTable(), joinIdentifiers(resolved.referencedColumns()),
                reason, detail, decision.evidence()));
    }

    private static ForeignKeyUnit applyDecision(ForeignKeyUnit fk, Decision decision) {
        if (decision.action() == Action.BASELINE) return fk;
        String table = decision.resolvedReferencedTable() == null
                ? fk.referencedTable() : decision.resolvedReferencedTable();
        List<String> columns = decision.resolvedReferencedColumns() == null
                ? fk.referencedColumns() : decision.resolvedReferencedColumns();
        String sql = rewriteReferencedTarget(fk.sql(), table, columns);
        return new ForeignKeyUnit(fk.sourceTable(), fk.constraintName(), fk.sourceColumns(),
                table, columns, sql, fk.statement(), fk.file());
    }

    private static String rewriteReferencedTarget(String rawSql, String table, List<String> columns) {
        String body = stripLeadingComments(rawSql);
        Matcher matcher = FOREIGN_KEY.matcher(body);
        if (!matcher.find()) throw new IllegalArgumentException("Cannot rewrite FK SQL: " + oneLine(rawSql));
        return body.substring(0, matcher.start(4))
                + table
                + body.substring(matcher.end(4), matcher.start(5))
                + String.join(", ", columns)
                + body.substring(matcher.end(5));
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
                .map(Db2LuwFinalStateResolutionP8IT::unquote)
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
                .map(Db2LuwFinalStateResolutionP8IT::unquote)
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

    private static void verifyExpectedDatabase(Connection connection, String expected) throws SQLException {
        if (expected == null || expected.isBlank()) return;
        String actual;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("VALUES CURRENT SERVER")) {
            actual = result.next() ? result.getString(1) : "";
        }
        if (!expected.equalsIgnoreCase(actual.trim())) {
            throw new IllegalStateException("Refusing DB2 LUW P8 validation: expected database "
                    + expected + " but connected to " + actual);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Query returned no rows: " + sql);
            return result.getString(1);
        }
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
        return values;
    }

    private static List<Map<String, String>> readResourceCsv(String resource) throws IOException {
        InputStream input = Db2LuwFinalStateResolutionP8IT.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(input, "retained evidence resource not found: " + resource);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String first = in.readLine();
            assertNotNull(first, "retained evidence resource is empty: " + resource);
            List<String> headers = parseCsvLine(first);
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < values.size() ? values.get(i) : "");
                }
                rows.add(Map.copyOf(row));
            }
            return List.copyOf(rows);
        }
    }

    private record ObjectName(String owner, String name) {
        static ObjectName parse(String qualified, String defaultOwner) {
            String normalized = normalizeName(qualified);
            int separator = normalized.indexOf('.');
            if (separator < 0) {
                if (defaultOwner == null || defaultOwner.isBlank()) {
                    throw new IllegalArgumentException("Unqualified DB2 object requires expected schema: " + qualified);
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

    private record FkKey(String sourceTable, String constraintName) {
        static FkKey of(String sourceTable, String constraintName) {
            return new FkKey(canonicalObjectKey(sourceTable), canonicalObjectKey(constraintName));
        }
    }

    private enum Action {
        BASELINE(true),
        TABLE_RENAME_CONFIRMED(true),
        COLUMN_RENAME_CONFIRMED(true),
        KEY_VERSION_SELECTION_FIX_REQUIRED(false),
        BLOCK_POSSIBLE_ALIAS(false),
        BLOCK_CANONICAL_PARENT_ABSENT(false),
        EXTERNAL_SHARED_DEPENDENCY(false),
        BLOCK_COLUMN_NEVER_EXISTED(false),
        BLOCK_NO_INDEPENDENT_UNIQUE_EVIDENCE(false);

        private final boolean liveEligible;

        Action(boolean liveEligible) {
            this.liveEligible = liveEligible;
        }

        boolean liveEligible() {
            return liveEligible;
        }
    }

    private record Decision(
            Action action, String resolvedReferencedTable, List<String> resolvedReferencedColumns,
            String evidence) {
        static Decision baseline() {
            return new Decision(Action.BASELINE, null, null, "not present in P6 blocker set");
        }
    }

    private record P6Row(FkKey key, String classification, String referencedTable, String referencedColumns) {
    }

    private static final class ResolutionEvidence {
        private final Map<FkKey, P6Row> p6;
        private final Map<FkKey, Map<String, String>> p71;
        private final Map<FkKey, Map<String, String>> p72;
        private final Map<FkKey, Map<String, String>> p73;

        private ResolutionEvidence(
                Map<FkKey, P6Row> p6,
                Map<FkKey, Map<String, String>> p71,
                Map<FkKey, Map<String, String>> p72,
                Map<FkKey, Map<String, String>> p73) {
            this.p6 = Map.copyOf(p6);
            this.p71 = Map.copyOf(p71);
            this.p72 = Map.copyOf(p72);
            this.p73 = Map.copyOf(p73);
        }

        static ResolutionEvidence load() throws IOException {
            Map<FkKey, P6Row> p6 = new LinkedHashMap<>();
            for (Map<String, String> row : readResourceCsv(P6_RESOURCE)) {
                FkKey key = FkKey.of(row.get("source_table"), row.get("constraint_name"));
                P6Row previous = p6.put(key, new P6Row(key, row.get("p6_classification"),
                        row.get("referenced_table"), row.get("referenced_columns")));
                assertFalse(previous != null, "duplicate retained P6 FK key: " + key);
            }
            Map<FkKey, Map<String, String>> p71 = index(P71_RESOURCE);
            Map<FkKey, Map<String, String>> p72 = index(P72_RESOURCE);
            Map<FkKey, Map<String, String>> p73 = index(P73_RESOURCE);

            ResolutionEvidence evidence = new ResolutionEvidence(p6, p71, p72, p73);
            evidence.verifyCrossReferences();
            return evidence;
        }

        private static Map<FkKey, Map<String, String>> index(String resource) throws IOException {
            Map<FkKey, Map<String, String>> result = new LinkedHashMap<>();
            for (Map<String, String> row : readResourceCsv(resource)) {
                FkKey key = FkKey.of(row.get("source_table"), row.get("constraint_name"));
                Map<String, String> previous = result.put(key, row);
                assertFalse(previous != null, "duplicate retained P7 FK key in " + resource + ": " + key);
            }
            return result;
        }

        private void verifyCrossReferences() {
            for (P6Row row : p6.values()) {
                switch (row.classification()) {
                    case "NAME_OR_ALIAS_DRIFT" -> assertNotNull(p71.get(row.key()), "P7.1 evidence missing: " + row.key());
                    case "MISSING_FROM_GENERATED_CORPUS" -> assertNotNull(p72.get(row.key()), "P7.2 evidence missing: " + row.key());
                    case "COLUMN_REFERENCE_OR_EXTRACTION_GAP" -> assertNotNull(p73.get(row.key()), "P7.3 evidence missing: " + row.key());
                    case "KEY_MODEL_OR_EXTRACTION_GAP", "KEY_VERSION_DRIFT" -> { }
                    default -> fail("Unknown retained P6 classification: " + row.classification());
                }
            }
            assertEquals(countP6("NAME_OR_ALIAS_DRIFT"), p71.size(), "P7.1 coverage");
            assertEquals(countP6("MISSING_FROM_GENERATED_CORPUS"), p72.size(), "P7.2 coverage");
            assertEquals(countP6("COLUMN_REFERENCE_OR_EXTRACTION_GAP"), p73.size(), "P7.3 coverage");
        }

        List<P6Row> p6Rows() {
            return List.copyOf(p6.values());
        }

        long countP6(String classification) {
            return p6.values().stream().filter(row -> classification.equals(row.classification())).count();
        }

        long countP71(String classification) {
            return countRows(p71, "p7_classification", classification);
        }

        long countP72(String classification) {
            return countRows(p72, "p7_classification", classification);
        }

        long countP73(String classification) {
            return countRows(p73, "p7_classification", classification);
        }

        private static long countRows(
                Map<FkKey, Map<String, String>> rows, String field, String value) {
            return rows.values().stream().filter(row -> value.equals(row.get(field))).count();
        }

        Map<Action, Long> actionCountsForP6Rows() {
            Map<Action, Long> result = new LinkedHashMap<>();
            for (P6Row row : p6.values()) {
                Decision decision = resolve(row.key());
                result.merge(decision.action(), 1L, Long::sum);
            }
            return result;
        }

        Decision resolve(ForeignKeyUnit fk) {
            return resolve(FkKey.of(fk.sourceTable(), fk.constraintName()));
        }

        private Decision resolve(FkKey key) {
            P6Row row = p6.get(key);
            if (row == null) return Decision.baseline();
            return switch (row.classification()) {
                case "NAME_OR_ALIAS_DRIFT" -> resolveP71(row);
                case "MISSING_FROM_GENERATED_CORPUS" -> resolveP72(row);
                case "COLUMN_REFERENCE_OR_EXTRACTION_GAP" -> resolveP73(row);
                case "KEY_MODEL_OR_EXTRACTION_GAP" -> new Decision(
                        Action.BLOCK_NO_INDEPENDENT_UNIQUE_EVIDENCE, null, null,
                        "P7.4 verified 20260830: NO_INDEPENDENT_UNIQUE_EVIDENCE");
                case "KEY_VERSION_DRIFT" -> new Decision(
                        Action.KEY_VERSION_SELECTION_FIX_REQUIRED, null, null,
                        "P7.5 verified 20260830: CANONICAL_KEY_EVIDENCE_PRESENT; final historical table version lacks the key");
                default -> throw new IllegalStateException("Unknown P6 classification: " + row.classification());
            };
        }

        private Decision resolveP71(P6Row p6Row) {
            Map<String, String> row = p71.get(p6Row.key());
            String classification = row.get("p7_classification");
            if ("CONFIRMED_RENAME".equals(classification)) {
                String resolved = row.get("resolved_candidate");
                return new Decision(Action.TABLE_RENAME_CONFIRMED, resolved, null,
                        "P7.1 CONFIRMED_RENAME; relation=" + row.get("name_relation")
                                + "; candidate=" + resolved);
            }
            return new Decision(Action.BLOCK_POSSIBLE_ALIAS, null, null,
                    "P7.1 POSSIBLE_ALIAS; candidate=" + row.get("resolved_candidate")
                            + "; relation=" + row.get("name_relation"));
        }

        private Decision resolveP72(P6Row p6Row) {
            Map<String, String> row = p72.get(p6Row.key());
            String classification = row.get("p7_classification");
            if ("EXTERNAL_OR_SHARED_DEPENDENCY".equals(classification)) {
                return new Decision(Action.EXTERNAL_SHARED_DEPENDENCY, null, null,
                        "P7.2 EXTERNAL_OR_SHARED_DEPENDENCY; " + row.get("evidence"));
            }
            return new Decision(Action.BLOCK_CANONICAL_PARENT_ABSENT, null, null,
                    "P7.2 CANONICAL_ABSENT; " + row.get("evidence"));
        }

        private Decision resolveP73(P6Row p6Row) {
            Map<String, String> row = p73.get(p6Row.key());
            String classification = row.get("p7_classification");
            if ("COLUMN_RENAMED".equals(classification)) {
                String resolved = row.get("resolved_column");
                return new Decision(Action.COLUMN_RENAME_CONFIRMED, null, List.of(resolved),
                        "P7.3 COLUMN_RENAMED; relation=" + row.get("column_relation")
                                + "; candidate=" + resolved);
            }
            return new Decision(Action.BLOCK_COLUMN_NEVER_EXISTED, null, null,
                    "P7.3 COLUMN_NEVER_EXISTED; requested=" + p6Row.referencedColumns());
        }
    }

    private record SuccessRow(
            String file, int statement, String sourceTable, String constraintName, String action,
            String originalReferencedTable, String originalReferencedColumns,
            String resolvedReferencedTable, String resolvedReferencedColumns, String evidence) {
    }

    private record LiveBlockerRow(
            String file, int statement, String sourceTable, String constraintName, String action,
            String originalReferencedTable, String originalReferencedColumns,
            String resolvedReferencedTable, String resolvedReferencedColumns,
            String reason, String detail, String evidence) {
    }

    private record DeferredRow(
            String file, int statement, String sourceTable, String constraintName, String sourceColumns,
            String referencedTable, String referencedColumns, String action, String evidence) {
    }

    private record ErrorRow(
            String file, int statement, String sourceTable, String constraintName, String action,
            String referencedTable, String referencedColumns, int sqlCode, String sqlState,
            String message, long elapsedMs, String sql, String evidence) {
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
        private final Map<Action, Integer> planned = new LinkedHashMap<>();
        private final List<SuccessRow> successRows = new ArrayList<>();
        private final List<LiveBlockerRow> liveBlockers = new ArrayList<>();
        private final List<DeferredRow> deferredRows = new ArrayList<>();
        private final List<ErrorRow> errors = new ArrayList<>();
        private final List<CleanupRow> cleanupErrors = new ArrayList<>();
        private final List<String> fatalMessages = new ArrayList<>();
        private int attempted;
        private int succeeded;
        private int failed;
        private int liveBlocked;
        private int deferred;
        private int resolutionAttempted;
        private int resolutionSucceeded;
        private int cleanupFailed;
        private Duration elapsed = Duration.ZERO;
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String databaseName = "";
        private String authorizationId = "";

        private Report(Config config, Path reportDir, int filesDiscovered, int finalTables, int finalForeignKeys) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.finalTables = finalTables;
            this.finalForeignKeys = finalForeignKeys;
        }

        void plan(Action action) {
            planned.merge(action, 1, Integer::sum);
        }

        void readDatabaseInfo(Connection connection) throws SQLException {
            databaseProduct = connection.getMetaData().getDatabaseProductName();
            databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            databaseName = scalar(connection, "VALUES CURRENT SERVER");
            authorizationId = scalar(connection, "VALUES CURRENT USER");
        }

        void write() throws IOException {
            writeSuccess();
            writeLiveBlockers();
            writeDeferred();
            writeErrors();
            writeCleanupErrors();
            Files.writeString(reportDir.resolve("db2luw-p8-final-state-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        private void writeSuccess() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p8-success.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,action,original_referenced_table,original_referenced_columns,resolved_referenced_table,resolved_referenced_columns,evidence\n");
                for (SuccessRow row : successRows) {
                    out.write(String.join(",", csv(row.file()), Integer.toString(row.statement()),
                            csv(row.sourceTable()), csv(row.constraintName()), csv(row.action()),
                            csv(row.originalReferencedTable()), csv(row.originalReferencedColumns()),
                            csv(row.resolvedReferencedTable()), csv(row.resolvedReferencedColumns()),
                            csv(row.evidence())));
                    out.newLine();
                }
            }
        }

        private void writeLiveBlockers() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p8-live-blockers.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,action,original_referenced_table,original_referenced_columns,resolved_referenced_table,resolved_referenced_columns,reason,detail,evidence\n");
                for (LiveBlockerRow row : liveBlockers) {
                    out.write(String.join(",", csv(row.file()), Integer.toString(row.statement()),
                            csv(row.sourceTable()), csv(row.constraintName()), csv(row.action()),
                            csv(row.originalReferencedTable()), csv(row.originalReferencedColumns()),
                            csv(row.resolvedReferencedTable()), csv(row.resolvedReferencedColumns()),
                            csv(row.reason()), csv(row.detail()), csv(row.evidence())));
                    out.newLine();
                }
            }
        }

        private void writeDeferred() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p8-deferred.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,source_columns,referenced_table,referenced_columns,action,evidence\n");
                for (DeferredRow row : deferredRows) {
                    out.write(String.join(",", csv(row.file()), Integer.toString(row.statement()),
                            csv(row.sourceTable()), csv(row.constraintName()), csv(row.sourceColumns()),
                            csv(row.referencedTable()), csv(row.referencedColumns()), csv(row.action()),
                            csv(row.evidence())));
                    out.newLine();
                }
            }
        }

        private void writeErrors() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p8-errors.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,action,referenced_table,referenced_columns,sqlcode,sqlstate,message,elapsed_ms,sql,evidence\n");
                for (ErrorRow row : errors) {
                    out.write(String.join(",", csv(row.file()), Integer.toString(row.statement()),
                            csv(row.sourceTable()), csv(row.constraintName()), csv(row.action()),
                            csv(row.referencedTable()), csv(row.referencedColumns()),
                            Integer.toString(row.sqlCode()), csv(row.sqlState()), csv(row.message()),
                            Long.toString(row.elapsedMs()), csv(row.sql()), csv(row.evidence())));
                    out.newLine();
                }
            }
        }

        private void writeCleanupErrors() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("db2luw-p8-cleanup-errors.csv"), StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,sqlcode,sqlstate,message\n");
                for (CleanupRow row : cleanupErrors) {
                    out.write(String.join(",", csv(row.file()), Integer.toString(row.statement()),
                            csv(row.sourceTable()), csv(row.constraintName()), Integer.toString(row.sqlCode()),
                            csv(row.sqlState()), csv(row.message())));
                    out.newLine();
                }
            }
        }

        private String summary() {
            int baseline = planned.getOrDefault(Action.BASELINE, 0);
            int tableRename = planned.getOrDefault(Action.TABLE_RENAME_CONFIRMED, 0);
            int columnRename = planned.getOrDefault(Action.COLUMN_RENAME_CONFIRMED, 0);
            int keyVersion = planned.getOrDefault(Action.KEY_VERSION_SELECTION_FIX_REQUIRED, 0);
            int keyModelBlocked = planned.getOrDefault(Action.BLOCK_NO_INDEPENDENT_UNIQUE_EVIDENCE, 0);
            int aliasBlocked = planned.getOrDefault(Action.BLOCK_POSSIBLE_ALIAS, 0);
            int parentAbsentBlocked = planned.getOrDefault(Action.BLOCK_CANONICAL_PARENT_ABSENT, 0);
            int externalDependency = planned.getOrDefault(Action.EXTERNAL_SHARED_DEPENDENCY, 0);
            int columnNeverExisted = planned.getOrDefault(Action.BLOCK_COLUMN_NEVER_EXISTED, 0);
            int evidenceBlocked = keyModelBlocked + aliasBlocked + parentAbsentBlocked + externalDependency + columnNeverExisted;
            int totalDeferred = evidenceBlocked + keyVersion;
            return "DB2 LUW P8 Final-State Resolution Validation\n"
                    + "============================================\n"
                    + "Database product          : " + databaseProduct + "\n"
                    + "Database version          : " + databaseVersion + "\n"
                    + "Database                  : " + databaseName + "\n"
                    + "Authorization ID          : " + authorizationId + "\n"
                    + "SQL root                  : " + config.root() + "\n"
                    + "Files discovered          : " + filesDiscovered + "\n"
                    + "Final table defs          : " + finalTables + "\n"
                    + "Final FK candidates       : " + finalForeignKeys + "\n"
                    + "\n"
                    + "P8 baseline eligible      : " + baseline + "\n"
                    + "P7.1 table rename eligible: " + tableRename + "\n"
                    + "P7.3 column rename eligible: " + columnRename + "\n"
                    + "P7.5 key-version fix req. : " + keyVersion + "\n"
                    + "P7.1 possible-alias block: " + aliasBlocked + "\n"
                    + "P7.2 canonical-absent block: " + parentAbsentBlocked + "\n"
                    + "P7.2 external/shared dep. : " + externalDependency + "\n"
                    + "P7.3 never-existed block : " + columnNeverExisted + "\n"
                    + "P7.4 no-unique-evidence   : " + keyModelBlocked + "\n"
                    + "Evidence/policy blocked  : " + evidenceBlocked + "\n"
                    + "Total deferred            : " + totalDeferred + "\n"
                    + "\n"
                    + "Live FK attempted         : " + attempted + "\n"
                    + "Live FK succeeded         : " + succeeded + "\n"
                    + "Live FK failed            : " + failed + "\n"
                    + "Resolution attempted      : " + resolutionAttempted + "\n"
                    + "Resolution succeeded      : " + resolutionSucceeded + "\n"
                    + "Post-resolution blockers  : " + liveBlocked + "\n"
                    + "Deferred without mutation : " + deferred + "\n"
                    + "Cleanup failed            : " + cleanupFailed + "\n"
                    + "Mutation policy           : IN-MEMORY VALIDATION ONLY; GENERATED CORPUS UNCHANGED\n"
                    + "Elapsed                   : " + elapsed + "\n"
                    + "Report directory          : " + reportDir + "\n"
                    + (fatalMessages.isEmpty() ? "" : "Fatal                     : " + String.join(" | ", fatalMessages) + "\n");
        }

        void printSummary() {
            System.out.println(summary());
        }
    }

    private record Config(
            Path root, String fileSuffix, String url, String user, String password, String driver,
            String expectedDatabase, String expectedSchema, Path reportBase, int maxFiles,
            int progressEveryFks, int loginTimeoutSeconds, int statementTimeoutSeconds,
            boolean failOnErrors, boolean failOnLiveBlockers, boolean strictBaseline) {

        static Config load() {
            Path root = path(first("schemaforge.db2luw.p8.sqlRoot", "db2luw.fk.sql.root", "db2luw.sql.root"));
            String fileSuffix = firstOrDefault(".db2luw.sql",
                    "schemaforge.db2luw.p8.fileSuffix", "db2luw.fk.sql.fileSuffix", "db2luw.sql.fileSuffix");
            String url = firstOrDefault("jdbc:db2://127.0.0.1:50000/SFORGE",
                    "schemaforge.db2luw.p8.jdbc.url", "db2luw.fk.jdbc.url", "db2luw.jdbc.url");
            String user = firstOrDefault("db2inst1",
                    "schemaforge.db2luw.p8.jdbc.user", "db2luw.fk.jdbc.user", "db2luw.jdbc.user");
            String password = firstOrDefault("Schemaforge123",
                    "schemaforge.db2luw.p8.jdbc.password", "db2luw.fk.jdbc.password", "db2luw.jdbc.password");
            String driver = firstOrDefault("com.ibm.db2.jcc.DB2Driver",
                    "schemaforge.db2luw.p8.jdbc.driver", "db2luw.fk.jdbc.driver", "db2luw.jdbc.driver");
            String expectedDatabase = firstOrDefault("SFORGE",
                    "schemaforge.db2luw.p8.expectedDatabase", "db2luw.fk.expectedDatabase", "db2luw.sql.expectedDatabase");
            String expectedSchema = firstOrDefault("TSTSHMA",
                    "schemaforge.db2luw.p8.expectedSchema", "db2luw.fk.expectedSchema");
            Path reportBase = path(firstOrDefault("target/db2luw-p8-final-state",
                    "schemaforge.db2luw.p8.reportDir"));
            return new Config(root, fileSuffix, url, user, password, driver,
                    expectedDatabase, expectedSchema, reportBase,
                    integer("schemaforge.db2luw.p8.maxFiles", 0),
                    integer("schemaforge.db2luw.p8.progressEveryFks", 100),
                    integer("schemaforge.db2luw.p8.loginTimeoutSeconds", 15),
                    integer("schemaforge.db2luw.p8.statementTimeoutSeconds", 60),
                    bool("schemaforge.db2luw.p8.failOnErrors", true),
                    bool("schemaforge.db2luw.p8.failOnLiveBlockers", false),
                    bool("schemaforge.db2luw.p8.strictBaseline", true));
        }

        boolean enabled() {
            return root != null;
        }

        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("P8 SQL root not found: " + root);
            if (fileSuffix == null || fileSuffix.isBlank()) throw new IllegalArgumentException("P8 file suffix is blank");
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("P8 expected schema is blank");
            if (maxFiles < 0 || progressEveryFks < 1 || loginTimeoutSeconds < 1 || statementTimeoutSeconds < 1) {
                throw new IllegalArgumentException("Invalid DB2 LUW P8 numeric configuration");
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
