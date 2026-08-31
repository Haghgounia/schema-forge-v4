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
 * PostgreSQL PG-P4 read-only reconciliation of selected-final PK/UK constraints and explicit indexes.
 *
 * <p>The expected model is derived from the same deterministic final CREATE TABLE selection as PG-P3.
 * PK/UK definitions are collected from both CREATE TABLE and ALTER TABLE ... ADD CONSTRAINT statements
 * in the selected-final file. Explicit CREATE INDEX definitions are likewise accepted only from that
 * selected-final file. SQL comments are removed before syntax classification so generated DBA hints
 * cannot create false-positive keys.</p>
 */
class PostgreSqlCatalogKeysIndexesP4IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:(?:UNLOGGED|TEMP|TEMPORARY)\\s+)?TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + QUALIFIED_NAME + ")");
    private static final Pattern TABLE_CONSTRAINT = Pattern.compile(
            "(?is)^(?:CONSTRAINT\\s+(" + IDENTIFIER + ")\\s+)?(PRIMARY\\s+KEY|UNIQUE)\\s*\\(([^)]*)\\)");
    private static final Pattern ALTER_CONSTRAINT = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(" + QUALIFIED_NAME + ")\\s+"
                    + "ADD\\s+CONSTRAINT\\s+(" + IDENTIFIER + ")\\s+"
                    + "(PRIMARY\\s+KEY|UNIQUE)\\s*\\(([^)]*)\\)");
    private static final Pattern CREATE_INDEX_HEAD = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + QUALIFIED_NAME + ")\\s+ON\\s+"
                    + "(?:ONLY\\s+)?(" + QUALIFIED_NAME + ")\\s*(?:USING\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*)?");
    private static final Pattern INCLUDE = Pattern.compile("(?is)\\bINCLUDE\\s*\\(([^)]*)\\)");
    private static final Pattern WHERE = Pattern.compile("(?is)\\bWHERE\\s+(.+)$");
    private static final Set<String> NON_COLUMN_PREFIXES = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE", "LIKE");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void reconcilesSelectedFinalPrimaryUniqueKeysAndExplicitIndexes() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.postgresql.p4.sqlRoot=<generated PostgreSQL root> to run PG-P4.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix());
        if (files.isEmpty()) fail("No PostgreSQL SQL files found below " + config.root());
        if (config.strictBaseline()) assertEquals(5321, files.size(), "PG-P4 accepted corpus file baseline changed");

        ExpectedModel expected = loadExpected(files, config.root(), pgIdentifier(config.expectedSchema()));
        if (config.strictBaseline()) assertEquals(2670, expected.tables().size(), "PG-P4 final table baseline changed");

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Report report = new Report(config, reportDir, files.size(), expected);
        Instant started = Instant.now();

        DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
        Class.forName(config.driver());
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setReadOnly(true);
            report.databaseProduct = connection.getMetaData().getDatabaseProductName();
            report.databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            report.database = scalar(connection, "SELECT current_database()");
            report.authorizationId = scalar(connection, "SELECT current_user");
            report.schema = pgIdentifier(config.expectedSchema());
            if (!config.expectedDatabase().isBlank()
                    && !config.expectedDatabase().equalsIgnoreCase(report.database)) {
                throw new IllegalStateException("Refusing PG-P4: expected database "
                        + config.expectedDatabase() + " but connected to " + report.database);
            }

            Map<String, List<CatalogConstraint>> actualConstraints =
                    loadCatalogConstraints(connection, report.schema);
            Map<String, CatalogIndex> actualIndexes = loadCatalogIndexes(connection, report.schema);
            report.catalogConstraintCount = actualConstraints.values().stream().mapToInt(List::size).sum();
            report.catalogIndexCount = actualIndexes.size();

            reconcileConstraints(expected, actualConstraints, report);
            reconcileIndexes(expected, actualIndexes, report);
        } finally {
            report.elapsed = Duration.between(started, Instant.now());
            report.write();
            report.printSummary();
        }

        if (report.missingConstraints > 0 || report.mismatchedConstraints > 0 || report.extraConstraints > 0) {
            fail("PostgreSQL PG-P4 PK/UK reconciliation found constraint differences. Report: " + reportDir);
        }
        if (report.missingIndexes > 0 || report.mismatchedIndexes > 0) {
            fail("PostgreSQL PG-P4 explicit-index reconciliation found missing/mismatched generated indexes. Report: " + reportDir);
        }
        if (config.failOnExtraIndexes() && report.extraIndexes > 0) {
            fail("PostgreSQL PG-P4 found extra catalog indexes under strict extra-index policy. Report: " + reportDir);
        }
    }

    private ExpectedModel loadExpected(List<Path> files, Path root, String expectedSchema) throws IOException {
        Map<String, ExpectedTable> tables = new LinkedHashMap<>();
        for (Path file : files) {
            String relative = normalize(root.relativize(file));
            String script = Files.readString(file, StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.POSTGRESQL)) {
                String sql = stripLeadingComments(raw);
                Matcher create = CREATE_TABLE.matcher(sql);
                if (!create.find()) continue;
                ObjectName tableName = ObjectName.parse(normalizeName(create.group(1)), expectedSchema);
                if (!tableName.schema().equals(expectedSchema)) continue;
                List<ExpectedConstraint> constraints = parseCreateTableConstraints(sql, create.end(), relative);
                tables.put(tableName.name(), new ExpectedTable(
                        tableName.name(), file, relative, new ArrayList<>(constraints), new ArrayList<>()));
            }
        }

        // Only statements in the file that supplied the selected-final CREATE TABLE can define final keys/indexes.
        for (ExpectedTable table : tables.values()) {
            String script = Files.readString(table.file(), StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.POSTGRESQL)) {
                String sql = stripLeadingComments(raw);
                String syntaxOnly = stripSqlCommentsPreservingQuotedText(sql).trim();
                if (syntaxOnly.isBlank()) continue;

                Matcher alter = ALTER_CONSTRAINT.matcher(syntaxOnly);
                if (alter.find()) {
                    ObjectName altered = ObjectName.parse(normalizeName(alter.group(1)), expectedSchema);
                    if (altered.schema().equals(expectedSchema) && altered.name().equals(table.table())) {
                        String name = pgIdentifier(alter.group(2));
                        String type = alter.group(3).toUpperCase(Locale.ROOT).startsWith("PRIMARY") ? "P" : "U";
                        table.constraints().add(new ExpectedConstraint(
                                name, type, parseIdentifierList(alter.group(4)), table.relativeFile()));
                    }
                    continue;
                }

                Matcher indexHead = CREATE_INDEX_HEAD.matcher(syntaxOnly);
                if (!indexHead.find()) continue;
                ObjectName indexedTable = ObjectName.parse(normalizeName(indexHead.group(3)), expectedSchema);
                if (!indexedTable.schema().equals(expectedSchema) || !indexedTable.name().equals(table.table())) continue;
                ExpectedIndex index = parseExpectedIndex(syntaxOnly, indexHead, expectedSchema, table.relativeFile());
                if (index != null) table.indexes().add(index);
            }
        }

        return new ExpectedModel(tables);
    }

    static List<ExpectedConstraint> parseCreateTableConstraints(String sql, int searchFrom, String file) {
        int open = sql.indexOf('(', searchFrom);
        if (open < 0) return List.of();
        int close = matchingParen(sql, open);
        if (close < 0) return List.of();
        String body = sql.substring(open + 1, close);
        List<ExpectedConstraint> result = new ArrayList<>();
        for (String element : splitTopLevel(body)) {
            String cleaned = stripLeadingComments(element.trim());
            if (cleaned.isBlank()) continue;
            String syntaxOnly = stripSqlCommentsPreservingQuotedText(cleaned).trim();
            if (syntaxOnly.isBlank()) continue;
            Matcher matcher = TABLE_CONSTRAINT.matcher(syntaxOnly);
            if (matcher.find()) {
                String name = matcher.group(1) == null ? "" : pgIdentifier(matcher.group(1));
                String type = matcher.group(2).toUpperCase(Locale.ROOT).startsWith("PRIMARY") ? "P" : "U";
                result.add(new ExpectedConstraint(name, type, parseIdentifierList(matcher.group(3)), file));
                continue;
            }
            String first = firstIdentifier(syntaxOnly);
            if (first == null) continue;
            String keyword = identifierText(first).toUpperCase(Locale.ROOT);
            if (NON_COLUMN_PREFIXES.contains(keyword)) continue;
            String after = syntaxOnly.substring(Math.min(syntaxOnly.length(), first.length()));
            if (Pattern.compile("(?is)\\bPRIMARY\\s+KEY\\b").matcher(after).find()) {
                result.add(new ExpectedConstraint("", "P", List.of(pgIdentifier(first)), file));
            } else if (Pattern.compile("(?is)\\bUNIQUE\\b").matcher(after).find()) {
                result.add(new ExpectedConstraint("", "U", List.of(pgIdentifier(first)), file));
            }
        }
        return List.copyOf(result);
    }

    private static ExpectedIndex parseExpectedIndex(
            String syntaxOnly, Matcher head, String expectedSchema, String file) {
        int open = syntaxOnly.indexOf('(', head.end());
        if (open < 0) return null;
        int close = matchingParen(syntaxOnly, open);
        if (close < 0) return null;
        String indexName = ObjectName.parse(normalizeName(head.group(2)), expectedSchema).name();
        ObjectName table = ObjectName.parse(normalizeName(head.group(3)), expectedSchema);
        boolean unique = head.group(1) != null;
        String method = head.group(4) == null ? "btree" : head.group(4).toLowerCase(Locale.ROOT);
        List<String> keys = parseIndexTerms(syntaxOnly.substring(open + 1, close));
        String tail = syntaxOnly.substring(close + 1);
        Matcher include = INCLUDE.matcher(tail);
        List<String> includes = include.find() ? parseIdentifierList(include.group(1)) : List.of();
        Matcher where = WHERE.matcher(tail);
        boolean partial = where.find();
        return new ExpectedIndex(indexName, table.name(), unique, method, keys, includes, partial, file);
    }

    private static List<String> parseIndexTerms(String value) {
        List<String> result = new ArrayList<>();
        for (String term : splitTopLevel(value)) {
            String normalized = normalizeIndexTerm(stripSqlCommentsPreservingQuotedText(term).trim());
            if (!normalized.isBlank()) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static void reconcileConstraints(
            ExpectedModel expected, Map<String, List<CatalogConstraint>> actualByTable, Report report) {
        for (ExpectedTable table : expected.tables().values()) {
            List<CatalogConstraint> actual = new ArrayList<>(actualByTable.getOrDefault(table.table(), List.of()));
            boolean[] used = new boolean[actual.size()];
            for (ExpectedConstraint exp : table.constraints()) {
                int signatureMatch = -1;
                int nameMatch = -1;
                for (int i = 0; i < actual.size(); i++) {
                    if (used[i]) continue;
                    CatalogConstraint act = actual.get(i);
                    if (!act.type().equals(exp.type())) continue;
                    if (act.columns().equals(exp.columns()) && signatureMatch < 0) signatureMatch = i;
                    if (!exp.name().isBlank() && act.name().equals(exp.name())) nameMatch = i;
                }
                int match = !exp.name().isBlank() ? nameMatch : signatureMatch;
                if (match >= 0) {
                    CatalogConstraint act = actual.get(match);
                    used[match] = true;
                    if (act.type().equals(exp.type()) && act.columns().equals(exp.columns())) {
                        report.exactConstraints++;
                        report.constraintRows.add(new ConstraintRow(table.table(), "EXACT", exp.name(), exp.type(),
                                join(exp.columns()), act.name(), act.type(), join(act.columns()), exp.file()));
                    } else {
                        report.mismatchedConstraints++;
                        report.constraintRows.add(new ConstraintRow(table.table(), "MISMATCH", exp.name(), exp.type(),
                                join(exp.columns()), act.name(), act.type(), join(act.columns()), exp.file()));
                    }
                } else if (signatureMatch >= 0) {
                    CatalogConstraint act = actual.get(signatureMatch);
                    used[signatureMatch] = true;
                    report.mismatchedConstraints++;
                    report.constraintRows.add(new ConstraintRow(table.table(), "NAME_MISMATCH", exp.name(), exp.type(),
                            join(exp.columns()), act.name(), act.type(), join(act.columns()), exp.file()));
                } else {
                    report.missingConstraints++;
                    report.constraintRows.add(new ConstraintRow(table.table(), "MISSING", exp.name(), exp.type(),
                            join(exp.columns()), "", "", "", exp.file()));
                }
            }
            for (int i = 0; i < actual.size(); i++) {
                if (used[i]) continue;
                CatalogConstraint act = actual.get(i);
                report.extraConstraints++;
                report.constraintRows.add(new ConstraintRow(table.table(), "EXTRA_CATALOG_CONSTRAINT", "", "", "",
                        act.name(), act.type(), join(act.columns()), table.relativeFile()));
            }
        }
    }

    private static void reconcileIndexes(
            ExpectedModel expected, Map<String, CatalogIndex> actualIndexes, Report report) {
        Set<String> expectedKeys = new LinkedHashSet<>();
        for (ExpectedTable table : expected.tables().values()) {
            for (ExpectedIndex exp : table.indexes()) {
                String key = exp.table() + "." + exp.name();
                expectedKeys.add(key);
                CatalogIndex act = actualIndexes.get(key);
                if (act == null) {
                    report.missingIndexes++;
                    report.indexRows.add(new IndexRow(exp.name(), exp.table(), "MISSING", exp.unique(), exp.method(),
                            join(exp.keys()), join(exp.includes()), exp.partial(), "", false, "", "", "", false, exp.file()));
                    continue;
                }
                boolean exact = act.table().equals(exp.table())
                        && act.unique() == exp.unique()
                        && normalizeMethod(act.method()).equals(normalizeMethod(exp.method()))
                        && act.keys().equals(exp.keys())
                        && act.includes().equals(exp.includes())
                        && act.partial() == exp.partial();
                if (exact) report.exactIndexes++; else report.mismatchedIndexes++;
                report.indexRows.add(new IndexRow(exp.name(), exp.table(), exact ? "EXACT" : "MISMATCH", exp.unique(),
                        exp.method(), join(exp.keys()), join(exp.includes()), exp.partial(), act.table(), act.unique(),
                        act.method(), join(act.keys()), join(act.includes()), act.partial(), exp.file()));
            }
        }
        for (Map.Entry<String, CatalogIndex> entry : actualIndexes.entrySet()) {
            if (!expectedKeys.contains(entry.getKey())) report.extraIndexes++;
        }
    }

    private static Map<String, List<CatalogConstraint>> loadCatalogConstraints(Connection connection, String schema)
            throws SQLException {
        record Key(String table, String name, String type) { }
        Map<Key, List<String>> columns = new LinkedHashMap<>();
        String sql = """
                SELECT c.relname, con.conname, con.contype, a.attname, k.ordinality
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ordinality) ON true
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
                WHERE n.nspname = ? AND con.contype IN ('p','u')
                ORDER BY c.relname, con.conname, k.ordinality
                """;
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Key key = new Key(rs.getString(1), rs.getString(2), rs.getString(3).toUpperCase(Locale.ROOT));
                    columns.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rs.getString(4));
                }
            }
        }
        Map<String, List<CatalogConstraint>> result = new LinkedHashMap<>();
        for (Map.Entry<Key, List<String>> entry : columns.entrySet()) {
            Key key = entry.getKey();
            result.computeIfAbsent(key.table(), ignored -> new ArrayList<>())
                    .add(new CatalogConstraint(key.name(), key.type(), List.copyOf(entry.getValue())));
        }
        result.replaceAll((key, value) -> List.copyOf(value));
        return result;
    }

    private static Map<String, CatalogIndex> loadCatalogIndexes(Connection connection, String schema) throws SQLException {
        record Header(long oid, String name, String table, boolean unique, String method,
                      int keyCount, int totalCount, boolean partial) { }
        Map<Long, Header> headers = new LinkedHashMap<>();
        String headerSql = """
                SELECT i.indexrelid, idx.relname, tbl.relname, i.indisunique, am.amname,
                       i.indnkeyatts, i.indnatts, (i.indpred IS NOT NULL)
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                JOIN pg_catalog.pg_class tbl ON tbl.oid = i.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace
                JOIN pg_catalog.pg_am am ON am.oid = idx.relam
                WHERE n.nspname = ? AND tbl.relkind IN ('r','p')
                ORDER BY idx.relname
                """;
        try (var ps = connection.prepareStatement(headerSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long oid = rs.getLong(1);
                    headers.put(oid, new Header(oid, rs.getString(2), rs.getString(3), rs.getBoolean(4),
                            rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getBoolean(8)));
                }
            }
        }

        Map<Long, List<String>> terms = new LinkedHashMap<>();
        String termSql = """
                SELECT i.indexrelid, s.pos, pg_catalog.pg_get_indexdef(i.indexrelid, s.pos, true)
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class tbl ON tbl.oid = i.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace
                JOIN LATERAL generate_series(1, i.indnatts) AS s(pos) ON true
                WHERE n.nspname = ? AND tbl.relkind IN ('r','p')
                ORDER BY i.indexrelid, s.pos
                """;
        try (var ps = connection.prepareStatement(termSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    terms.computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>())
                            .add(normalizeIndexTerm(rs.getString(3)));
                }
            }
        }

        Map<String, CatalogIndex> result = new LinkedHashMap<>();
        for (Header header : headers.values()) {
            List<String> all = terms.getOrDefault(header.oid(), List.of());
            int keyCount = Math.min(header.keyCount(), all.size());
            int totalCount = Math.min(header.totalCount(), all.size());
            List<String> keys = List.copyOf(all.subList(0, keyCount));
            List<String> includes = List.copyOf(all.subList(keyCount, totalCount));
            CatalogIndex index = new CatalogIndex(header.name(), header.table(), header.unique(), header.method(),
                    keys, includes, header.partial());
            result.put(header.table() + "." + header.name(), index);
        }
        return result;
    }

    private static List<String> parseIdentifierList(String value) {
        List<String> result = new ArrayList<>();
        for (String item : splitTopLevel(value)) {
            String first = firstIdentifier(item);
            if (first != null) result.add(pgIdentifier(first));
        }
        return List.copyOf(result);
    }

    private static String normalizeIndexTerm(String value) {
        if (value == null) return "";
        String v = stripSqlCommentsPreservingQuotedText(value).trim().toLowerCase(Locale.ROOT);
        v = v.replaceAll("\\s+", " ").replaceAll("\\s*([(),])\\s*", "$1").trim();
        v = v.replaceAll("\\s+asc(?:\\s+nulls\\s+last)?$", "");
        v = v.replaceAll("\\s+nulls\\s+last$", "");
        while (isWrappedBySingleOuterParenPair(v)) v = v.substring(1, v.length() - 1).trim();
        return v;
    }

    private static boolean isWrappedBySingleOuterParenPair(String value) {
        if (value.length() < 2 || value.charAt(0) != '(' || value.charAt(value.length() - 1) != ')') return false;
        int close = matchingParen(value, 0);
        return close == value.length() - 1;
    }

    private static String normalizeMethod(String method) {
        return method == null || method.isBlank() ? "btree" : method.toLowerCase(Locale.ROOT);
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { i++; blockComment = false; }
                continue;
            }
            if (dollarTag != null) {
                if (text.startsWith(dollarTag, i)) { i += dollarTag.length() - 1; dollarTag = null; }
                continue;
            }
            if (single) {
                if (ch == '\'' && next == '\'') i++;
                else if (ch == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                if (ch == '"' && next == '"') i++;
                else if (ch == '"') quotedIdentifier = false;
                continue;
            }
            if (ch == '-' && next == '-') { i++; lineComment = true; }
            else if (ch == '/' && next == '*') { i++; blockComment = true; }
            else if (ch == '\'') single = true;
            else if (ch == '"') quotedIdentifier = true;
            else if (ch == '$') {
                String tag = dollarTagAt(text, i);
                if (tag != null) { dollarTag = tag; i += tag.length() - 1; }
            } else if (ch == '(') depth++;
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

    private static String dollarTagAt(String text, int offset) {
        if (text.charAt(offset) != '$') return null;
        int end = text.indexOf('$', offset + 1);
        if (end < 0) return null;
        String middle = text.substring(offset + 1, end);
        if (!middle.isEmpty() && !middle.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        return text.substring(offset, end + 1);
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

    private static List<Path> findSqlFiles(Path root, String suffix) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
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

    private static String normalizeName(String value) {
        return value.replaceAll("\\s*\\.\\s*", ".").trim();
    }

    private static String identifierText(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    private static String pgIdentifier(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) return identifierText(trimmed);
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String join(List<String> values) { return String.join("|", values); }
    private static String csv(String value) { return '"' + (value == null ? "" : value).replace("\"", "\"\"") + '"'; }

    private record ObjectName(String schema, String name) {
        static ObjectName parse(String qualified, String defaultSchema) {
            String normalized = normalizeName(qualified);
            int dot = normalized.indexOf('.');
            if (dot < 0) return new ObjectName(pgIdentifier(defaultSchema), pgIdentifier(normalized));
            return new ObjectName(pgIdentifier(normalized.substring(0, dot)), pgIdentifier(normalized.substring(dot + 1)));
        }
    }

    record ExpectedConstraint(String name, String type, List<String> columns, String file) { }
    private record CatalogConstraint(String name, String type, List<String> columns) { }
    private record ExpectedIndex(String name, String table, boolean unique, String method,
                                 List<String> keys, List<String> includes, boolean partial, String file) { }
    private record CatalogIndex(String name, String table, boolean unique, String method,
                                List<String> keys, List<String> includes, boolean partial) { }
    private record ExpectedTable(String table, Path file, String relativeFile,
                                 List<ExpectedConstraint> constraints, List<ExpectedIndex> indexes) { }
    private record ExpectedModel(Map<String, ExpectedTable> tables) { }
    private record ConstraintRow(String table, String status, String expectedName, String expectedType,
                                 String expectedColumns, String catalogName, String catalogType,
                                 String catalogColumns, String file) { }
    private record IndexRow(String indexName, String expectedTable, String status, boolean expectedUnique,
                            String expectedMethod, String expectedKeys, String expectedIncludes, boolean expectedPartial,
                            String catalogTable, boolean catalogUnique, String catalogMethod, String catalogKeys,
                            String catalogIncludes, boolean catalogPartial, String file) { }

    private record Config(
            Path root, String fileSuffix, String url, String user, String password, String driver,
            String expectedDatabase, String expectedSchema, int loginTimeoutSeconds, boolean strictBaseline,
            boolean failOnExtraIndexes, Path reportBase) {
        static Config load() {
            String root = firstNonBlank(
                    System.getProperty("schemaforge.postgresql.p4.sqlRoot"),
                    System.getProperty("schemaforge.postgresql.p3.sqlRoot"),
                    System.getProperty("postgresql.sql.root"),
                    System.getenv("POSTGRESQL_SQL_ROOT"));
            return new Config(
                    root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("schemaforge.postgresql.p4.fileSuffix", ".postgresql.sql"),
                    System.getProperty("schemaforge.postgresql.p4.jdbc.url", "jdbc:postgresql://localhost:5433/mydb"),
                    System.getProperty("schemaforge.postgresql.p4.jdbc.user", "postgres"),
                    System.getProperty("schemaforge.postgresql.p4.jdbc.password", "123456"),
                    System.getProperty("schemaforge.postgresql.p4.jdbc.driver", "org.postgresql.Driver"),
                    System.getProperty("schemaforge.postgresql.p4.expectedDatabase", "mydb"),
                    System.getProperty("schemaforge.postgresql.p4.expectedSchema", "TSTSHMA"),
                    Integer.getInteger("schemaforge.postgresql.p4.loginTimeoutSeconds", 20),
                    Boolean.parseBoolean(System.getProperty("schemaforge.postgresql.p4.strictBaseline", "true")),
                    Boolean.parseBoolean(System.getProperty("schemaforge.postgresql.p4.failOnExtraIndexes", "false")),
                    Path.of(System.getProperty("schemaforge.postgresql.p4.reportBase",
                            "target/postgresql-p4-keys-indexes-catalog")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("PG-P4 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("PG-P4 expected schema required");
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
        private final int expectedTables;
        private final int expectedConstraintCount;
        private final int expectedIndexCount;
        private final List<ConstraintRow> constraintRows = new ArrayList<>();
        private final List<IndexRow> indexRows = new ArrayList<>();
        private String databaseProduct = "";
        private String databaseVersion = "";
        private String database = "";
        private String authorizationId = "";
        private String schema = "";
        private int catalogConstraintCount;
        private int exactConstraints;
        private int missingConstraints;
        private int mismatchedConstraints;
        private int extraConstraints;
        private int catalogIndexCount;
        private int exactIndexes;
        private int missingIndexes;
        private int mismatchedIndexes;
        private int extraIndexes;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, ExpectedModel expected) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.expectedTables = expected.tables().size();
            this.expectedConstraintCount = expected.tables().values().stream().mapToInt(t -> t.constraints().size()).sum();
            this.expectedIndexCount = expected.tables().values().stream().mapToInt(t -> t.indexes().size()).sum();
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("postgresql-p4-constraints.csv"), StandardCharsets.UTF_8)) {
                out.write("table,status,expected_name,expected_type,expected_columns,catalog_name,catalog_type,catalog_columns,file\n");
                for (ConstraintRow row : constraintRows) {
                    out.write(csv(row.table()) + "," + csv(row.status()) + "," + csv(row.expectedName()) + ","
                            + csv(row.expectedType()) + "," + csv(row.expectedColumns()) + "," + csv(row.catalogName()) + ","
                            + csv(row.catalogType()) + "," + csv(row.catalogColumns()) + "," + csv(row.file()) + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(
                    reportDir.resolve("postgresql-p4-indexes.csv"), StandardCharsets.UTF_8)) {
                out.write("index_name,expected_table,status,expected_unique,expected_method,expected_keys,expected_includes,expected_partial,"
                        + "catalog_table,catalog_unique,catalog_method,catalog_keys,catalog_includes,catalog_partial,file\n");
                for (IndexRow row : indexRows) {
                    out.write(csv(row.indexName()) + "," + csv(row.expectedTable()) + "," + csv(row.status()) + ","
                            + row.expectedUnique() + "," + csv(row.expectedMethod()) + "," + csv(row.expectedKeys()) + ","
                            + csv(row.expectedIncludes()) + "," + row.expectedPartial() + "," + csv(row.catalogTable()) + ","
                            + row.catalogUnique() + "," + csv(row.catalogMethod()) + "," + csv(row.catalogKeys()) + ","
                            + csv(row.catalogIncludes()) + "," + row.catalogPartial() + "," + csv(row.file()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("postgresql-p4-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            return "PostgreSQL PG-P4 Catalog Reconciliation - PK/UK/Explicit Indexes\n"
                    + "================================================================\n"
                    + "Database product          : " + databaseProduct + "\n"
                    + "Database version          : " + databaseVersion + "\n"
                    + "Database                  : " + database + "\n"
                    + "Authorization ID          : " + authorizationId + "\n"
                    + "Schema                    : " + schema + "\n"
                    + "SQL root                  : " + config.root() + "\n"
                    + "Files discovered          : " + filesDiscovered + "\n"
                    + "Expected final tables     : " + expectedTables + "\n"
                    + "Expected PK/UK constraints: " + expectedConstraintCount + "\n"
                    + "Catalog PK/UK constraints : " + catalogConstraintCount + "\n"
                    + "Exact PK/UK constraints   : " + exactConstraints + "\n"
                    + "Missing constraints       : " + missingConstraints + "\n"
                    + "Mismatched constraints    : " + mismatchedConstraints + "\n"
                    + "Extra catalog constraints : " + extraConstraints + "\n"
                    + "Expected explicit indexes : " + expectedIndexCount + "\n"
                    + "Catalog indexes           : " + catalogIndexCount + "\n"
                    + "Exact explicit indexes    : " + exactIndexes + "\n"
                    + "Missing explicit indexes  : " + missingIndexes + "\n"
                    + "Mismatched indexes        : " + mismatchedIndexes + "\n"
                    + "Extra catalog indexes     : " + extraIndexes + "\n"
                    + "Extra-index fail policy   : " + config.failOnExtraIndexes() + "\n"
                    + "Mutation policy           : READ ONLY\n"
                    + "Elapsed                   : " + elapsed + "\n"
                    + "Report directory          : " + reportDir.toAbsolutePath() + "\n";
        }

        void printSummary() { System.out.println(summary()); }
    }
}
