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
import java.sql.Array;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PostgreSQL PG-P5 final-state foreign-key live validation and catalog reconciliation.
 *
 * <p>The historical runner intentionally skips every FK. PG-P5 therefore selects the final
 * generated revision of every logical table, extracts only FKs from those selected-final files,
 * preflights their structural prerequisites against the live PostgreSQL catalog, and validates
 * eligible constraints with a CREATE -> pg_constraint verify -> DROP lifecycle. No successful
 * validation FK is intentionally left behind.</p>
 */
class PostgreSqlForeignKeyLiveValidationP5IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:(?:UNLOGGED|TEMP|TEMPORARY)\\s+)?TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + QUALIFIED_NAME + ")");

    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(" + QUALIFIED_NAME + ")"
                    + "\\s+ADD\\s+CONSTRAINT\\s+(" + IDENTIFIER + ")"
                    + "\\s+FOREIGN\\s+KEY\\s*\\(([^)]*)\\)"
                    + "\\s+REFERENCES\\s+(" + QUALIFIED_NAME + ")\\s*\\(([^)]*)\\)(.*)$");

    private static final Pattern ON_DELETE = Pattern.compile(
            "(?is)\\bON\\s+DELETE\\s+(NO\\s+ACTION|RESTRICT|CASCADE|SET\\s+NULL|SET\\s+DEFAULT)\\b");
    private static final Pattern ON_UPDATE = Pattern.compile(
            "(?is)\\bON\\s+UPDATE\\s+(NO\\s+ACTION|RESTRICT|CASCADE|SET\\s+NULL|SET\\s+DEFAULT)\\b");
    private static final Pattern MATCH = Pattern.compile(
            "(?is)\\bMATCH\\s+(SIMPLE|FULL|PARTIAL)\\b");
    private static final Pattern INITIALLY = Pattern.compile(
            "(?is)\\bINITIALLY\\s+(DEFERRED|IMMEDIATE)\\b");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void parserRecognizesGeneratedForeignKeyAndIgnoresCommentText() {
        String sql = "/* FOREIGN KEY fake REFERENCES fake */ ALTER TABLE TSTSHMA.Child "
                + "ADD CONSTRAINT FK_CHILD_PARENT FOREIGN KEY (ParentId) "
                + "REFERENCES TSTSHMA.Parent (Id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED";
        ExpectedFk fk = parseForeignKey(stripLeadingComments(sql), "sample.sql", 1, "TSTSHMA");
        assertNotNull(fk);
        assertEquals("child", fk.source().name());
        assertEquals("fk_child_parent", fk.constraintName());
        assertEquals(List.of("parentid"), fk.sourceColumns());
        assertEquals("parent", fk.referenced().name());
        assertEquals(List.of("id"), fk.referencedColumns());
        assertEquals("c", fk.deleteRule());
        assertEquals("a", fk.updateRule());
        assertEquals("s", fk.matchType());
        assertTrue(fk.deferrable());
        assertTrue(fk.initiallyDeferred());
    }

    @Test
    void validatesSelectedFinalForeignKeysThroughPostgreSqlCatalog() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.postgresql.p5.sqlRoot=<generated PostgreSQL root> to run PG-P5.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix());
        if (files.isEmpty()) fail("No PostgreSQL SQL files found below " + config.root());

        FinalModel model = loadSelectedFinalForeignKeys(files, config.root(), config.expectedSchema());
        if (config.strictBaseline()) {
            assertEquals(5321, files.size(), "PG-P5 accepted file baseline changed");
            assertEquals(2670, model.selectedTables().size(), "PG-P5 final table baseline changed");
        }

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Report report = new Report(config, reportDir, files.size(), model.selectedTables().size(), model.foreignKeys().size());
        Instant started = Instant.now();
        Throwable fatal = null;

        try {
            DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
            Class.forName(config.driver());
            try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                connection.setAutoCommit(true);
                report.databaseProduct = connection.getMetaData().getDatabaseProductName();
                report.databaseVersion = connection.getMetaData().getDatabaseProductVersion();
                report.database = scalar(connection, "SELECT current_database()");
                report.authorizationId = scalar(connection, "SELECT current_user");
                report.schema = pgIdentifier(config.expectedSchema());
                verifyExpectedDatabase(report.database, config.expectedDatabase());

                CatalogSnapshot snapshot = loadCatalogSnapshot(connection, report.schema);
                report.catalogFkBefore = countForeignKeys(connection, report.schema);

                int sequence = 0;
                for (ExpectedFk fk : model.foreignKeys()) {
                    sequence++;
                    validateOne(connection, snapshot, config, report, fk);
                    if (sequence % config.progressEvery() == 0 || sequence == model.foreignKeys().size()) {
                        System.out.printf(Locale.ROOT,
                                "PostgreSQL PG-P5 FK: %,d / %,d, eligible=%,d, exact=%,d, blocked=%,d, "
                                        + "errors=%,d, cleanup-errors=%,d%n",
                                sequence, model.foreignKeys().size(), report.eligible, report.catalogExact,
                                report.blocked, report.executionErrors, report.cleanupErrors);
                    }
                }
                report.catalogFkAfter = countForeignKeys(connection, report.schema);
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
            fail("PG-P5 changed persistent FK catalog state (before=" + report.catalogFkBefore
                    + ", after=" + report.catalogFkAfter + "). Report: " + reportDir);
        }
        if (report.executionErrors > 0 || report.catalogMismatch > 0 || report.cleanupErrors > 0) {
            fail("PG-P5 found execution/catalog/cleanup errors. Report: " + reportDir);
        }
        if (config.failOnBlockers() && report.blocked > 0) {
            fail("PG-P5 found " + report.blocked + " structural blockers under strict blocker policy. Report: " + reportDir);
        }
        assertEquals(report.eligible, report.catalogExact,
                "Every structurally eligible final FK must reconcile exactly in pg_catalog");
    }

    private FinalModel loadSelectedFinalForeignKeys(List<Path> files, Path root, String defaultSchema) throws IOException {
        Map<String, SelectedTable> selected = new LinkedHashMap<>();
        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            String relative = normalize(root.relativize(file));
            for (String raw : splitter.parse(script, DatabasePlatform.POSTGRESQL)) {
                String sql = stripLeadingComments(raw);
                Matcher create = CREATE_TABLE.matcher(sql);
                if (!create.find()) continue;
                ObjectName name = ObjectName.parse(create.group(1), defaultSchema);
                if (!name.schema().equals(pgIdentifier(defaultSchema))) continue;
                selected.put(name.name(), new SelectedTable(name, file, relative));
            }
        }

        Map<String, ExpectedFk> fks = new LinkedHashMap<>();
        for (SelectedTable table : selected.values()) {
            String script = Files.readString(table.file(), StandardCharsets.UTF_8);
            int statementIndex = 0;
            for (String raw : splitter.parse(script, DatabasePlatform.POSTGRESQL)) {
                statementIndex++;
                ExpectedFk fk = parseForeignKey(stripLeadingComments(raw), table.relativeFile(), statementIndex, defaultSchema);
                if (fk == null) continue;
                if (!fk.source().schema().equals(pgIdentifier(defaultSchema)) || !fk.source().name().equals(table.name().name())) {
                    continue;
                }
                String identity = fk.source().schema() + "." + fk.source().name() + "|" + fk.constraintName();
                ExpectedFk previous = fks.put(identity, fk);
                if (previous != null && !oneLine(previous.sql()).equals(oneLine(fk.sql()))) {
                    throw new IllegalStateException("Duplicate final FK identity with different SQL: " + identity
                            + " in " + table.relativeFile());
                }
            }
        }
        return new FinalModel(selected, List.copyOf(fks.values()));
    }

    private static ExpectedFk parseForeignKey(String sql, String file, int statementIndex, String defaultSchema) {
        String syntaxOnly = stripSqlCommentsPreservingQuotedText(sql).trim();
        Matcher matcher = FOREIGN_KEY.matcher(syntaxOnly);
        if (!matcher.find()) return null;
        ObjectName source = ObjectName.parse(matcher.group(1), defaultSchema);
        String constraint = pgIdentifier(matcher.group(2));
        List<String> sourceColumns = parseIdentifierList(matcher.group(3));
        ObjectName referenced = ObjectName.parse(matcher.group(4), defaultSchema);
        List<String> referencedColumns = parseIdentifierList(matcher.group(5));
        String tail = matcher.group(6);
        return new ExpectedFk(source, constraint, sourceColumns, referenced, referencedColumns,
                expectedRule(tail, ON_DELETE), expectedRule(tail, ON_UPDATE), expectedMatch(tail),
                expectedDeferrable(tail), expectedInitiallyDeferred(tail), sql.trim(), file, statementIndex);
    }

    private void validateOne(
            Connection connection, CatalogSnapshot snapshot, Config config, Report report, ExpectedFk fk) throws Exception {
        String blocker = preflight(snapshot, fk);
        if (!blocker.isBlank()) {
            report.blocked++;
            report.blockerCounts.merge(blocker, 1, Integer::sum);
            report.rows.add(ResultRow.blocked(fk, blocker));
            return;
        }
        report.eligible++;

        CatalogFk existing = loadCatalogFk(connection, fk);
        if (existing != null) {
            String mismatch = compareCatalog(fk, existing);
            if (mismatch.isBlank()) {
                report.catalogExact++;
                report.preexistingExact++;
                report.rows.add(ResultRow.exact(fk, "PREEXISTING_EXACT", existing));
            } else {
                report.catalogMismatch++;
                report.rows.add(ResultRow.mismatch(fk, "PREEXISTING_MISMATCH", mismatch, existing));
            }
            return;
        }

        report.createAttempts++;
        boolean created = false;
        Instant started = Instant.now();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(config.statementTimeoutSeconds());
            statement.execute(fk.sql());
            created = true;
            report.createdForValidation++;
            CatalogFk actual = loadCatalogFk(connection, fk);
            if (actual == null) {
                report.catalogMismatch++;
                report.rows.add(ResultRow.error(fk, "CATALOG_ROW_NOT_FOUND_AFTER_CREATE", ""));
                return;
            }
            String mismatch = compareCatalog(fk, actual);
            if (mismatch.isBlank()) {
                report.catalogExact++;
                report.rows.add(ResultRow.exact(fk, "CREATED_AND_EXACT", actual));
            } else {
                report.catalogMismatch++;
                report.rows.add(ResultRow.mismatch(fk, "CREATED_BUT_MISMATCHED", mismatch, actual));
            }
        } catch (SQLException exception) {
            report.executionErrors++;
            report.rows.add(ResultRow.sqlError(fk, exception,
                    Duration.between(started, Instant.now()).toMillis()));
        } finally {
            if (created) {
                try {
                    dropForeignKey(connection, fk);
                } catch (SQLException cleanup) {
                    report.cleanupErrors++;
                    report.cleanupRows.add(new CleanupRow(fk.file(), fk.source().qualified(), fk.constraintName(),
                            cleanup.getSQLState(), cleanup.getErrorCode(), oneLine(cleanup.getMessage())));
                }
            }
        }
    }

    private static String preflight(CatalogSnapshot snapshot, ExpectedFk fk) {
        Set<String> sourceColumns = snapshot.columnsByTable().get(fk.source().name());
        if (sourceColumns == null) return "SOURCE_TABLE_MISSING";
        if (!sourceColumns.containsAll(fk.sourceColumns())) return "SOURCE_COLUMN_MISSING";
        if (!fk.referenced().schema().equals(snapshot.schema())) return "REFERENCED_SCHEMA_EXTERNAL";
        Set<String> referencedColumns = snapshot.columnsByTable().get(fk.referenced().name());
        if (referencedColumns == null) return "REFERENCED_TABLE_MISSING";
        if (!referencedColumns.containsAll(fk.referencedColumns())) return "REFERENCED_COLUMN_MISSING";
        Set<String> uniqueSignatures = snapshot.uniqueKeySignaturesByTable()
                .getOrDefault(fk.referenced().name(), Set.of());
        if (!uniqueSignatures.contains(join(fk.referencedColumns()))) return "REFERENCED_COLUMNS_NOT_UNIQUE";
        return "";
    }

    private static CatalogSnapshot loadCatalogSnapshot(Connection connection, String schema) throws SQLException {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        String columnSql = """
                SELECT c.relname, a.attname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
                WHERE n.nspname = ?
                  AND c.relkind IN ('r','p')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY c.relname, a.attnum
                """;
        try (var ps = connection.prepareStatement(columnSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashSet<>()).add(rs.getString(2));
                }
            }
        }

        Map<String, Set<String>> unique = new LinkedHashMap<>();
        String uniqueSql = """
                SELECT t.relname,
                       ARRAY(
                           SELECT a.attname
                           FROM unnest(i.indkey::smallint[]) WITH ORDINALITY AS k(attnum, ord)
                           JOIN pg_catalog.pg_attribute a
                             ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                           WHERE k.ord <= i.indnkeyatts
                           ORDER BY k.ord
                       ) AS key_columns
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ?
                  AND t.relkind IN ('r','p')
                  AND i.indisunique
                  AND i.indisvalid
                  AND i.indisready
                  AND i.indpred IS NULL
                  AND i.indexprs IS NULL
                """;
        try (var ps = connection.prepareStatement(uniqueSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> keyColumns = sqlArrayToStrings(rs.getArray(2));
                    if (!keyColumns.isEmpty()) {
                        unique.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashSet<>()).add(join(keyColumns));
                    }
                }
            }
        }
        return new CatalogSnapshot(schema, freezeSetMap(columns), freezeSetMap(unique));
    }

    private static CatalogFk loadCatalogFk(Connection connection, ExpectedFk fk) throws SQLException {
        String sql = """
                SELECT rn.nspname,
                       rt.relname,
                       ARRAY(
                           SELECT a.attname
                           FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                           JOIN pg_catalog.pg_attribute a
                             ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                           ORDER BY k.ord
                       ) AS source_columns,
                       ARRAY(
                           SELECT a.attname
                           FROM unnest(c.confkey) WITH ORDINALITY AS k(attnum, ord)
                           JOIN pg_catalog.pg_attribute a
                             ON a.attrelid = c.confrelid AND a.attnum = k.attnum
                           ORDER BY k.ord
                       ) AS referenced_columns,
                       c.confdeltype::text,
                       c.confupdtype::text,
                       c.confmatchtype::text,
                       c.condeferrable,
                       c.condeferred
                FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_class st ON st.oid = c.conrelid
                JOIN pg_catalog.pg_namespace sn ON sn.oid = st.relnamespace
                JOIN pg_catalog.pg_class rt ON rt.oid = c.confrelid
                JOIN pg_catalog.pg_namespace rn ON rn.oid = rt.relnamespace
                WHERE c.contype = 'f'
                  AND sn.nspname = ?
                  AND st.relname = ?
                  AND c.conname = ?
                """;
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, fk.source().schema());
            ps.setString(2, fk.source().name());
            ps.setString(3, fk.constraintName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                CatalogFk result = new CatalogFk(
                        new ObjectName(rs.getString(1), rs.getString(2)),
                        sqlArrayToStrings(rs.getArray(3)), sqlArrayToStrings(rs.getArray(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getBoolean(8), rs.getBoolean(9));
                if (rs.next()) throw new SQLException("Duplicate PostgreSQL FK catalog row for "
                        + fk.source().qualified() + "." + fk.constraintName());
                return result;
            }
        }
    }

    private static String compareCatalog(ExpectedFk expected, CatalogFk actual) {
        List<String> differences = new ArrayList<>();
        if (!expected.referenced().equals(actual.referenced())) {
            differences.add("REFERENCED_TABLE expected=" + expected.referenced().qualified()
                    + " actual=" + actual.referenced().qualified());
        }
        if (!expected.sourceColumns().equals(actual.sourceColumns())) {
            differences.add("SOURCE_COLUMNS expected=" + join(expected.sourceColumns())
                    + " actual=" + join(actual.sourceColumns()));
        }
        if (!expected.referencedColumns().equals(actual.referencedColumns())) {
            differences.add("REFERENCED_COLUMNS expected=" + join(expected.referencedColumns())
                    + " actual=" + join(actual.referencedColumns()));
        }
        if (!expected.deleteRule().equals(actual.deleteRule())) {
            differences.add("DELETE_RULE expected=" + expected.deleteRule() + " actual=" + actual.deleteRule());
        }
        if (!expected.updateRule().equals(actual.updateRule())) {
            differences.add("UPDATE_RULE expected=" + expected.updateRule() + " actual=" + actual.updateRule());
        }
        if (!expected.matchType().equals(actual.matchType())) {
            differences.add("MATCH expected=" + expected.matchType() + " actual=" + actual.matchType());
        }
        if (expected.deferrable() != actual.deferrable()) {
            differences.add("DEFERRABLE expected=" + expected.deferrable() + " actual=" + actual.deferrable());
        }
        if (expected.initiallyDeferred() != actual.initiallyDeferred()) {
            differences.add("INITIALLY_DEFERRED expected=" + expected.initiallyDeferred()
                    + " actual=" + actual.initiallyDeferred());
        }
        return String.join("; ", differences);
    }

    private static void dropForeignKey(Connection connection, ExpectedFk fk) throws SQLException {
        String sql = "ALTER TABLE " + quoteQualified(fk.source()) + " DROP CONSTRAINT "
                + quoteIdentifier(fk.constraintName());
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int countForeignKeys(Connection connection, String schema) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                WHERE c.contype = 'f' AND n.nspname = ?
                """;
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<String> sqlArrayToStrings(Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            Object raw = array.getArray();
            if (raw instanceof String[] strings) return List.of(strings);
            if (raw instanceof Object[] objects) {
                List<String> values = new ArrayList<>(objects.length);
                for (Object object : objects) values.add(String.valueOf(object));
                return List.copyOf(values);
            }
            return List.of();
        } finally {
            array.free();
        }
    }

    private static Map<String, Set<String>> freezeSetMap(Map<String, Set<String>> source) {
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> frozen.put(key, Set.copyOf(value)));
        return Map.copyOf(frozen);
    }

    private static String expectedRule(String tail, Pattern pattern) {
        Matcher matcher = pattern.matcher(stripSqlCommentsPreservingQuotedText(tail));
        if (!matcher.find()) return "a";
        String value = matcher.group(1).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "NO ACTION" -> "a";
            case "RESTRICT" -> "r";
            case "CASCADE" -> "c";
            case "SET NULL" -> "n";
            case "SET DEFAULT" -> "d";
            default -> throw new IllegalArgumentException("Unsupported PostgreSQL referential action: " + value);
        };
    }

    private static String expectedMatch(String tail) {
        Matcher matcher = MATCH.matcher(stripSqlCommentsPreservingQuotedText(tail));
        if (!matcher.find()) return "s";
        return switch (matcher.group(1).toUpperCase(Locale.ROOT)) {
            case "SIMPLE" -> "s";
            case "FULL" -> "f";
            case "PARTIAL" -> "p";
            default -> "s";
        };
    }

    private static boolean expectedDeferrable(String tail) {
        String clean = stripSqlCommentsPreservingQuotedText(tail).toUpperCase(Locale.ROOT);
        if (clean.matches("(?s).*\\bNOT\\s+DEFERRABLE\\b.*")) return false;
        return clean.matches("(?s).*\\bDEFERRABLE\\b.*");
    }

    private static boolean expectedInitiallyDeferred(String tail) {
        Matcher matcher = INITIALLY.matcher(stripSqlCommentsPreservingQuotedText(tail));
        return matcher.find() && "DEFERRED".equalsIgnoreCase(matcher.group(1));
    }

    private static List<String> parseIdentifierList(String value) {
        List<String> result = new ArrayList<>();
        for (String item : splitTopLevel(value)) {
            String first = firstIdentifier(item);
            if (first != null) result.add(pgIdentifier(first));
        }
        return List.copyOf(result);
    }

    private static List<String> splitTopLevel(String body) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            char next = i + 1 < body.length() ? body.charAt(i + 1) : '\0';
            current.append(ch);
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { current.append(next); i++; blockComment = false; }
                continue;
            }
            if (dollarTag != null) {
                if (body.startsWith(dollarTag, i)) {
                    for (int j = 1; j < dollarTag.length(); j++) current.append(body.charAt(i + j));
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (single) {
                if (ch == '\'' && next == '\'') { current.append(next); i++; }
                else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                if (ch == '"' && next == '"') { current.append(next); i++; }
                else if (ch == '"') quotedIdentifier = false;
                continue;
            }
            if (ch == '-' && next == '-') { current.append(next); i++; lineComment = true; }
            else if (ch == '/' && next == '*') { current.append(next); i++; blockComment = true; }
            else if (ch == '\'') single = true;
            else if (ch == '"') quotedIdentifier = true;
            else if (ch == '$') {
                String tag = dollarTagAt(body, i);
                if (tag != null) {
                    for (int j = 1; j < tag.length(); j++) current.append(body.charAt(i + j));
                    i += tag.length() - 1;
                    dollarTag = tag;
                }
            } else if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) {
                current.setLength(current.length() - 1);
                result.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    static String stripSqlCommentsPreservingQuotedText(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n' || ch == '\r') { lineComment = false; out.append(' '); }
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { i++; blockComment = false; out.append(' '); }
                continue;
            }
            if (dollarTag != null) {
                out.append(ch);
                if (sql.startsWith(dollarTag, i)) {
                    for (int j = 1; j < dollarTag.length(); j++) out.append(sql.charAt(i + j));
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (single) {
                out.append(ch);
                if (ch == '\'' && next == '\'') { out.append(next); i++; }
                else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                out.append(ch);
                if (ch == '"' && next == '"') { out.append(next); i++; }
                else if (ch == '"') quotedIdentifier = false;
                continue;
            }
            if (ch == '-' && next == '-') { i++; lineComment = true; out.append(' '); }
            else if (ch == '/' && next == '*') { i++; blockComment = true; out.append(' '); }
            else if (ch == '\'') { single = true; out.append(ch); }
            else if (ch == '"') { quotedIdentifier = true; out.append(ch); }
            else if (ch == '$') {
                String tag = dollarTagAt(sql, i);
                if (tag != null) {
                    out.append(tag);
                    i += tag.length() - 1;
                    dollarTag = tag;
                } else out.append(ch);
            } else out.append(ch);
        }
        return out.toString();
    }

    private static String dollarTagAt(String text, int offset) {
        if (text.charAt(offset) != '$') return null;
        int end = text.indexOf('$', offset + 1);
        if (end < 0) return null;
        String middle = text.substring(offset + 1, end);
        if (!middle.isEmpty() && !middle.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        return text.substring(offset, end + 1);
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
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) break;
            i++;
        }
        return i == 0 ? null : trimmed.substring(0, i);
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

    private static List<Path> findSqlFiles(Path root, String suffix) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList();
        }
    }

    private static String pgIdentifier(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String quoteQualified(ObjectName name) {
        return quoteIdentifier(name.schema()) + "." + quoteIdentifier(name.name());
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String join(List<String> values) { return String.join("|", values); }
    private static String oneLine(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    private static String csv(String value) { return '"' + (value == null ? "" : value).replace("\"", "\"\"") + '"'; }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no rows: " + sql);
            return rs.getString(1);
        }
    }

    private static void verifyExpectedDatabase(String actual, String expected) {
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Refusing PG-P5: expected database " + expected + " but connected to " + actual);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + oneLine(current.getMessage());
    }

    private record ObjectName(String schema, String name) {
        static ObjectName parse(String qualified, String defaultSchema) {
            String normalized = normalizeName(qualified);
            int dot = normalized.indexOf('.');
            if (dot < 0) return new ObjectName(pgIdentifier(defaultSchema), pgIdentifier(normalized));
            return new ObjectName(pgIdentifier(normalized.substring(0, dot)), pgIdentifier(normalized.substring(dot + 1)));
        }
        String qualified() { return schema + "." + name; }
    }

    private record SelectedTable(ObjectName name, Path file, String relativeFile) { }
    private record FinalModel(Map<String, SelectedTable> selectedTables, List<ExpectedFk> foreignKeys) { }
    private record ExpectedFk(ObjectName source, String constraintName, List<String> sourceColumns,
                              ObjectName referenced, List<String> referencedColumns,
                              String deleteRule, String updateRule, String matchType,
                              boolean deferrable, boolean initiallyDeferred,
                              String sql, String file, int statementIndex) { }
    private record CatalogSnapshot(String schema, Map<String, Set<String>> columnsByTable,
                                   Map<String, Set<String>> uniqueKeySignaturesByTable) { }
    private record CatalogFk(ObjectName referenced, List<String> sourceColumns, List<String> referencedColumns,
                             String deleteRule, String updateRule, String matchType,
                             boolean deferrable, boolean initiallyDeferred) { }

    private record CleanupRow(String file, String sourceTable, String constraintName,
                              String sqlState, int errorCode, String message) { }

    private record ResultRow(String file, int statementIndex, String sourceTable, String constraintName,
                             String sourceColumns, String referencedTable, String referencedColumns,
                             String status, String detail, String sqlState, int errorCode, long elapsedMs) {
        static ResultRow blocked(ExpectedFk fk, String reason) {
            return base(fk, "BLOCKED", reason, "", 0, 0);
        }
        static ResultRow exact(ExpectedFk fk, String status, CatalogFk actual) {
            return base(fk, status, "", "", 0, 0);
        }
        static ResultRow mismatch(ExpectedFk fk, String status, String detail, CatalogFk actual) {
            return base(fk, status, detail, "", 0, 0);
        }
        static ResultRow error(ExpectedFk fk, String status, String detail) {
            return base(fk, status, detail, "", 0, 0);
        }
        static ResultRow sqlError(ExpectedFk fk, SQLException error, long elapsedMs) {
            return base(fk, "SQL_ERROR", oneLine(error.getMessage()), error.getSQLState(), error.getErrorCode(), elapsedMs);
        }
        private static ResultRow base(ExpectedFk fk, String status, String detail,
                                      String sqlState, int errorCode, long elapsedMs) {
            return new ResultRow(fk.file(), fk.statementIndex(), fk.source().qualified(), fk.constraintName(),
                    join(fk.sourceColumns()), fk.referenced().qualified(), join(fk.referencedColumns()),
                    status, detail, sqlState == null ? "" : sqlState, errorCode, elapsedMs);
        }
    }

    private record Config(Path root, String fileSuffix, String url, String user, String password, String driver,
                          String expectedDatabase, String expectedSchema, int loginTimeoutSeconds,
                          int statementTimeoutSeconds, int progressEvery, boolean strictBaseline,
                          boolean failOnBlockers, Path reportBase) {
        static Config load() {
            String root = firstNonBlank(
                    System.getProperty("schemaforge.postgresql.p5.sqlRoot"),
                    System.getProperty("schemaforge.postgresql.p4.sqlRoot"),
                    System.getProperty("schemaforge.postgresql.p3.sqlRoot"),
                    System.getProperty("postgresql.sql.root"),
                    System.getenv("POSTGRESQL_SQL_ROOT"));
            return new Config(
                    root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("schemaforge.postgresql.p5.fileSuffix", ".postgresql.sql"),
                    System.getProperty("schemaforge.postgresql.p5.jdbc.url", "jdbc:postgresql://localhost:5433/mydb"),
                    System.getProperty("schemaforge.postgresql.p5.jdbc.user", "postgres"),
                    System.getProperty("schemaforge.postgresql.p5.jdbc.password", "123456"),
                    System.getProperty("schemaforge.postgresql.p5.jdbc.driver", "org.postgresql.Driver"),
                    System.getProperty("schemaforge.postgresql.p5.expectedDatabase", "mydb"),
                    System.getProperty("schemaforge.postgresql.p5.expectedSchema", "TSTSHMA"),
                    Integer.getInteger("schemaforge.postgresql.p5.loginTimeoutSeconds", 20),
                    Integer.getInteger("schemaforge.postgresql.p5.statementTimeoutSeconds", 60),
                    Integer.getInteger("schemaforge.postgresql.p5.progressEvery", 50),
                    Boolean.parseBoolean(System.getProperty("schemaforge.postgresql.p5.strictBaseline", "true")),
                    Boolean.parseBoolean(System.getProperty("schemaforge.postgresql.p5.failOnBlockers", "false")),
                    Path.of(System.getProperty("schemaforge.postgresql.p5.reportBase",
                            "target/postgresql-p5-fk-live-validation")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("PG-P5 SQL root not found: " + root);
            if (progressEvery <= 0) throw new IllegalArgumentException("PG-P5 progressEvery must be > 0");
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("PG-P5 expected schema required");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static final class Report {
        private final Config config;
        private final Path reportDir;
        private final int filesDiscovered;
        private final int selectedFinalTables;
        private final int finalFkCandidates;
        private final List<ResultRow> rows = new ArrayList<>();
        private final List<CleanupRow> cleanupRows = new ArrayList<>();
        private final Map<String, Integer> blockerCounts = new LinkedHashMap<>();
        private final List<String> fatalMessages = new ArrayList<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String database = "";
        private String authorizationId = "";
        private String schema = "";
        private int catalogFkBefore;
        private int catalogFkAfter;
        private int eligible;
        private int blocked;
        private int createAttempts;
        private int createdForValidation;
        private int preexistingExact;
        private int catalogExact;
        private int catalogMismatch;
        private int executionErrors;
        private int cleanupErrors;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, int selectedFinalTables, int finalFkCandidates) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.selectedFinalTables = selectedFinalTables;
            this.finalFkCandidates = finalFkCandidates;
        }

        void write() throws IOException {
            Files.createDirectories(reportDir);
            writeRows();
            writeCleanup();
            writeSummary();
        }

        private void writeRows() throws IOException {
            Path file = reportDir.resolve("postgresql-p5-fk-reconciliation.csv");
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("file,statement,source_table,constraint_name,source_columns,referenced_table,referenced_columns,status,detail,sqlstate,error_code,elapsed_ms\n");
                for (ResultRow row : rows) {
                    out.write(csv(row.file()) + "," + row.statementIndex() + "," + csv(row.sourceTable()) + ","
                            + csv(row.constraintName()) + "," + csv(row.sourceColumns()) + ","
                            + csv(row.referencedTable()) + "," + csv(row.referencedColumns()) + ","
                            + csv(row.status()) + "," + csv(row.detail()) + "," + csv(row.sqlState()) + ","
                            + row.errorCode() + "," + row.elapsedMs() + "\n");
                }
            }
        }

        private void writeCleanup() throws IOException {
            Path file = reportDir.resolve("postgresql-p5-cleanup-errors.csv");
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("file,source_table,constraint_name,sqlstate,error_code,message\n");
                for (CleanupRow row : cleanupRows) {
                    out.write(csv(row.file()) + "," + csv(row.sourceTable()) + "," + csv(row.constraintName()) + ","
                            + csv(row.sqlState()) + "," + row.errorCode() + "," + csv(row.message()) + "\n");
                }
            }
        }

        private void writeSummary() throws IOException {
            Path file = reportDir.resolve("postgresql-p5-summary.txt");
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write(summaryText());
            }
        }

        void printSummary() { System.out.print(summaryText()); }

        private String summaryText() {
            StringBuilder out = new StringBuilder();
            out.append("PostgreSQL PG-P5 Final-State FK Live Validation\n")
                    .append("=============================================\n")
                    .append("Database product          : ").append(databaseProduct).append('\n')
                    .append("Database version          : ").append(databaseVersion).append('\n')
                    .append("Database                  : ").append(database).append('\n')
                    .append("Authorization ID          : ").append(authorizationId).append('\n')
                    .append("Schema                    : ").append(schema).append('\n')
                    .append("SQL root                  : ").append(config.root()).append('\n')
                    .append("Files discovered          : ").append(filesDiscovered).append('\n')
                    .append("Selected final tables     : ").append(selectedFinalTables).append('\n')
                    .append("Final FK candidates       : ").append(finalFkCandidates).append('\n')
                    .append("Structurally eligible     : ").append(eligible).append('\n')
                    .append("Structural blockers       : ").append(blocked).append('\n');
            if (!blockerCounts.isEmpty()) {
                out.append("Blocker classifications:\n");
                blockerCounts.forEach((key, value) -> out.append("  ").append(String.format(Locale.ROOT, "%-36s", key))
                        .append(value).append('\n'));
            }
            out.append("Catalog FK count before   : ").append(catalogFkBefore).append('\n')
                    .append("Create attempts           : ").append(createAttempts).append('\n')
                    .append("Created for validation    : ").append(createdForValidation).append('\n')
                    .append("Pre-existing exact        : ").append(preexistingExact).append('\n')
                    .append("Catalog exact             : ").append(catalogExact).append('\n')
                    .append("Catalog mismatch          : ").append(catalogMismatch).append('\n')
                    .append("Execution errors          : ").append(executionErrors).append('\n')
                    .append("Cleanup errors            : ").append(cleanupErrors).append('\n')
                    .append("Catalog FK count after    : ").append(catalogFkAfter).append('\n')
                    .append("Persistent state preserved: ").append(catalogFkBefore == catalogFkAfter).append('\n')
                    .append("Blocker fail policy       : ").append(config.failOnBlockers()).append('\n')
                    .append("Mutation policy           : ABSENT FK = CREATE / VERIFY / DROP; NO KEY SYNTHESIS\n")
                    .append("Elapsed                   : ").append(elapsed).append('\n')
                    .append("Report directory          : ").append(reportDir).append('\n');
            if (!fatalMessages.isEmpty()) {
                out.append("Fatal messages            :\n");
                fatalMessages.forEach(message -> out.append("  - ").append(message).append('\n'));
            }
            return out.toString();
        }
    }
}
