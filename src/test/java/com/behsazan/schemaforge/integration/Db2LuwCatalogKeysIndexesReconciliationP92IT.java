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
 * DB2 LUW P9.2 read-only reconciliation of selected-final PK/UK constraints and explicit indexes.
 *
 * <p>The expected model is derived from the same deterministic final CREATE TABLE file selection used
 * by P9.1. Inline CREATE TABLE comments are ignored before parsing constraint/column elements. Explicit
 * CREATE INDEX statements are taken only from the file selected as final for the indexed table.</p>
 */
class Db2LuwCatalogKeysIndexesReconciliationP92IT {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern TABLE_CONSTRAINT = Pattern.compile(
            "(?is)^(?:CONSTRAINT\\s+(" + IDENTIFIER + ")\\s+)?(PRIMARY\\s+KEY|UNIQUE)\\s*\\(([^)]*)\\)");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(" + QUALIFIED_NAME + ")\\s+ON\\s+(" + QUALIFIED_NAME + ")\\s*\\(([^)]*)\\)");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void reconcilesSelectedFinalPrimaryUniqueKeysAndExplicitIndexes() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.db2luw.p9.sqlRoot=<generated DB2 LUW root> to run P9.2.");
        config.validate();

        List<Path> files = findSqlFiles(config.root(), config.fileSuffix(), config.maxFiles());
        if (files.isEmpty()) fail("No DB2 LUW SQL files found below " + config.root());

        ExpectedModel expected = loadExpected(files, config.root(), config.expectedSchema());
        if (config.strictBaseline()) assertEquals(2310, expected.tables().size(), "P9.2 final table baseline changed");

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Report report = new Report(config, reportDir, files.size(), expected);
        Instant started = Instant.now();

        DriverManager.setLoginTimeout(config.loginTimeoutSeconds());
        Class.forName(config.driver());
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setReadOnly(true);
            verifyExpectedDatabase(connection, config.expectedDatabase());
            report.databaseProduct = connection.getMetaData().getDatabaseProductName();
            report.databaseVersion = connection.getMetaData().getDatabaseProductVersion();
            report.database = scalar(connection, "VALUES CURRENT SERVER");
            report.authorizationId = scalar(connection, "VALUES CURRENT USER");

            Map<String, List<CatalogConstraint>> actualConstraints = loadCatalogConstraints(connection, config.expectedSchema());
            Map<String, CatalogIndex> actualIndexes = loadCatalogIndexes(connection, config.expectedSchema());
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
            fail("DB2 LUW P9.2 PK/UK reconciliation found constraint differences. Report: " + reportDir);
        }
        if (report.missingIndexes > 0 || report.mismatchedIndexes > 0) {
            fail("DB2 LUW P9.2 explicit-index reconciliation found missing/mismatched generated indexes. Report: " + reportDir);
        }
        if (config.failOnExtraIndexes() && report.extraIndexes > 0) {
            fail("DB2 LUW P9.2 found extra catalog indexes under strict extra-index policy. Report: " + reportDir);
        }
    }

    private ExpectedModel loadExpected(List<Path> files, Path root, String schema) throws IOException {
        Map<String, ExpectedTable> tables = new LinkedHashMap<>();
        for (Path file : files) {
            String relative = normalize(root.relativize(file));
            String script = Files.readString(file, StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.DB2_LUW)) {
                String sql = stripLeadingComments(raw);
                Matcher create = CREATE_TABLE.matcher(sql);
                if (!create.find()) continue;
                ObjectName tableName = ObjectName.parse(normalizeName(create.group(1)), schema);
                if (!tableName.owner().equalsIgnoreCase(schema)) continue;
                List<ExpectedConstraint> constraints = parseTableConstraints(sql, create.end(), relative);
                tables.put(tableName.name(), new ExpectedTable(tableName.name(), file, relative, constraints, new ArrayList<>()));
            }
        }

        for (Path file : files) {
            String script = Files.readString(file, StandardCharsets.UTF_8);
            for (String raw : splitter.parse(script, DatabasePlatform.DB2_LUW)) {
                String sql = stripLeadingComments(raw);
                Matcher index = CREATE_INDEX.matcher(sql);
                if (!index.find()) continue;
                ObjectName tableName = ObjectName.parse(normalizeName(index.group(3)), schema);
                if (!tableName.owner().equalsIgnoreCase(schema)) continue;
                ExpectedTable table = tables.get(tableName.name());
                if (table == null || !table.file().equals(file)) continue;
                String indexName = ObjectName.parse(normalizeName(index.group(2)), schema).name();
                boolean unique = index.group(1) != null;
                List<IndexColumn> columns = parseIndexColumns(index.group(4));
                table.indexes().add(new ExpectedIndex(indexName, tableName.name(), unique, columns, table.relativeFile()));
            }
        }
        return new ExpectedModel(tables);
    }

    static List<ExpectedConstraint> parseTableConstraints(String sql, int searchFrom, String file) {
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
                String name = matcher.group(1) == null ? "" : identifierKey(matcher.group(1));
                String type = matcher.group(2).toUpperCase(Locale.ROOT).startsWith("PRIMARY") ? "P" : "U";
                result.add(new ExpectedConstraint(name, type, parseIdentifierList(matcher.group(3)), file));
                continue;
            }
            String first = firstIdentifier(syntaxOnly);
            if (first != null) {
                String after = syntaxOnly.substring(Math.min(syntaxOnly.length(), first.length()));
                if (Pattern.compile("(?is)\\bPRIMARY\\s+KEY\\b").matcher(after).find()) {
                    result.add(new ExpectedConstraint("", "P", List.of(identifierKey(first)), file));
                } else if (Pattern.compile("(?is)\\bUNIQUE\\b").matcher(after).find()) {
                    result.add(new ExpectedConstraint("", "U", List.of(identifierKey(first)), file));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void reconcileConstraints(ExpectedModel expected,
                                             Map<String, List<CatalogConstraint>> actualByTable,
                                             Report report) {
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

    private static void reconcileIndexes(ExpectedModel expected,
                                         Map<String, CatalogIndex> actualIndexes,
                                         Report report) {
        Set<String> expectedNames = new LinkedHashSet<>();
        for (ExpectedTable table : expected.tables().values()) {
            for (ExpectedIndex exp : table.indexes()) {
                expectedNames.add(exp.name());
                CatalogIndex act = actualIndexes.get(exp.name());
                if (act == null) {
                    report.missingIndexes++;
                    report.indexRows.add(new IndexRow(exp.name(), exp.table(), "MISSING", exp.unique(),
                            joinIndex(exp.columns()), "", false, "", exp.file()));
                    continue;
                }
                boolean exact = act.table().equals(exp.table()) && act.unique() == exp.unique()
                        && act.columns().equals(exp.columns());
                if (exact) report.exactIndexes++; else report.mismatchedIndexes++;
                report.indexRows.add(new IndexRow(exp.name(), exp.table(), exact ? "EXACT" : "MISMATCH", exp.unique(),
                        joinIndex(exp.columns()), act.table(), act.unique(), joinIndex(act.columns()), exp.file()));
            }
        }
        for (CatalogIndex act : actualIndexes.values()) {
            if (!expectedNames.contains(act.name())) {
                report.extraIndexes++;
            }
        }
    }

    private static Map<String, List<CatalogConstraint>> loadCatalogConstraints(Connection connection, String schema)
            throws SQLException {
        record Key(String table, String name, String type) { }
        Map<Key, List<String>> columns = new LinkedHashMap<>();
        try (var ps = connection.prepareStatement(
                "SELECT C.TABNAME, C.CONSTNAME, C.TYPE, K.COLNAME " +
                        "FROM SYSCAT.TABCONST C JOIN SYSCAT.KEYCOLUSE K " +
                        "ON K.TABSCHEMA=C.TABSCHEMA AND K.TABNAME=C.TABNAME AND K.CONSTNAME=C.CONSTNAME " +
                        "WHERE C.TABSCHEMA=? AND C.TYPE IN ('P','U') " +
                        "ORDER BY C.TABNAME, C.CONSTNAME, K.COLSEQ WITH UR")) {
            ps.setString(1, schema.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Key key = new Key(upper(rs.getString(1)), upper(rs.getString(2)), upper(rs.getString(3)));
                    columns.computeIfAbsent(key, ignored -> new ArrayList<>()).add(upper(rs.getString(4)));
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
        record Header(String name, String table, boolean unique) { }
        Map<String, Header> headers = new LinkedHashMap<>();
        try (var ps = connection.prepareStatement(
                "SELECT INDNAME, TABNAME, UNIQUERULE FROM SYSCAT.INDEXES WHERE TABSCHEMA=? ORDER BY INDNAME WITH UR")) {
            ps.setString(1, schema.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = upper(rs.getString(1));
                    String rule = upper(rs.getString(3));
                    headers.put(name, new Header(name, upper(rs.getString(2)), !"D".equals(rule)));
                }
            }
        }
        Map<String, List<IndexColumn>> columns = new LinkedHashMap<>();
        try (var ps = connection.prepareStatement(
                "SELECT I.INDNAME, C.COLNAME, C.COLORDER FROM SYSCAT.INDEXES I " +
                        "JOIN SYSCAT.INDEXCOLUSE C ON C.INDSCHEMA=I.INDSCHEMA AND C.INDNAME=I.INDNAME " +
                        "WHERE I.TABSCHEMA=? ORDER BY I.INDNAME, C.COLSEQ WITH UR")) {
            ps.setString(1, schema.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String order = upper(rs.getString(3));
                    columns.computeIfAbsent(upper(rs.getString(1)), ignored -> new ArrayList<>())
                            .add(new IndexColumn(upper(rs.getString(2)), "D".equals(order) ? "D" : "A"));
                }
            }
        }
        Map<String, CatalogIndex> result = new LinkedHashMap<>();
        for (Header header : headers.values()) {
            result.put(header.name(), new CatalogIndex(header.name(), header.table(), header.unique(),
                    List.copyOf(columns.getOrDefault(header.name(), List.of()))));
        }
        return result;
    }

    private static List<IndexColumn> parseIndexColumns(String raw) {
        List<IndexColumn> result = new ArrayList<>();
        for (String item : splitTopLevel(raw)) {
            String cleaned = stripLeadingComments(item.trim());
            String id = firstIdentifier(cleaned);
            if (id == null) continue;
            String upper = cleaned.toUpperCase(Locale.ROOT);
            String order = Pattern.compile("(?is)\\bDESC\\b").matcher(upper).find() ? "D" : "A";
            result.add(new IndexColumn(identifierKey(id), order));
        }
        return List.copyOf(result);
    }

    private static List<String> parseIdentifierList(String raw) {
        List<String> result = new ArrayList<>();
        for (String item : splitTopLevel(raw)) {
            String cleaned = stripLeadingComments(item.trim());
            String id = firstIdentifier(cleaned);
            if (id != null) result.add(identifierKey(id));
        }
        return List.copyOf(result);
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean single = false;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (single) {
                if (ch == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else if (ch == '\'') single = false;
                continue;
            }
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                else if (ch == '"') quoted = false;
                continue;
            }
            if (ch == '\'') single = true;
            else if (ch == '"') quoted = true;
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
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (single) {
                current.append(ch);
                if (ch == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') current.append(body.charAt(++i));
                else if (ch == '\'') single = false;
                continue;
            }
            if (quoted) {
                current.append(ch);
                if (ch == '"' && i + 1 < body.length() && body.charAt(i + 1) == '"') current.append(body.charAt(++i));
                else if (ch == '"') quoted = false;
                continue;
            }
            if (ch == '\'') { single = true; current.append(ch); }
            else if (ch == '"') { quoted = true; current.append(ch); }
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
            throw new IllegalStateException("Refusing P9.2: expected DB " + expected + " but connected to " + actual);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no rows: " + sql);
            return rs.getString(1);
        }
    }

    private static String stripSqlCommentsPreservingQuotedText(String sql) {
        String value = sql == null ? "" : sql;
        StringBuilder out = new StringBuilder(value.length());
        boolean single = false;
        boolean quoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : '\0';

            if (lineComment) {
                if (ch == '\n' || ch == '\r') {
                    lineComment = false;
                    out.append(ch);
                }
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                    out.append(' ');
                }
                continue;
            }
            if (single) {
                out.append(ch);
                if (ch == '\'' && next == '\'') {
                    out.append(next);
                    i++;
                } else if (ch == '\'') {
                    single = false;
                }
                continue;
            }
            if (quoted) {
                out.append(ch);
                if (ch == '"' && next == '"') {
                    out.append(next);
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }

            if (ch == '-' && next == '-') {
                lineComment = true;
                i++;
                out.append(' ');
            } else if (ch == '/' && next == '*') {
                blockComment = true;
                i++;
                out.append(' ');
            } else if (ch == '\'') {
                single = true;
                out.append(ch);
            } else if (ch == '"') {
                quoted = true;
                out.append(ch);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
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
    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String identifierKey(String value) { return unquote(value).toUpperCase(Locale.ROOT); }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String join(List<String> values) { return String.join("|", values); }
    private static String joinIndex(List<IndexColumn> values) {
        return values.stream().map(v -> v.column() + ":" + v.order()).reduce((a, b) -> a + "|" + b).orElse("");
    }
    private static String unquote(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }
    private static String csv(String value) { return '"' + (value == null ? "" : value).replace("\"", "\"\"") + '"'; }

    private record ObjectName(String owner, String name) {
        static ObjectName parse(String qualified, String defaultSchema) {
            String normalized = normalizeName(qualified).replace("\"", "");
            int dot = normalized.indexOf('.');
            if (dot < 0) return new ObjectName(upper(defaultSchema), upper(normalized));
            return new ObjectName(upper(normalized.substring(0, dot)), upper(normalized.substring(dot + 1)));
        }
    }
    private record ExpectedConstraint(String name, String type, List<String> columns, String file) { }
    private record IndexColumn(String column, String order) { }
    private record ExpectedIndex(String name, String table, boolean unique, List<IndexColumn> columns, String file) { }
    private record ExpectedTable(String table, Path file, String relativeFile,
                                 List<ExpectedConstraint> constraints, List<ExpectedIndex> indexes) { }
    private record ExpectedModel(Map<String, ExpectedTable> tables) {
        int constraintCount() { return tables.values().stream().mapToInt(t -> t.constraints().size()).sum(); }
        int indexCount() { return tables.values().stream().mapToInt(t -> t.indexes().size()).sum(); }
    }
    private record CatalogConstraint(String name, String type, List<String> columns) { }
    private record CatalogIndex(String name, String table, boolean unique, List<IndexColumn> columns) { }
    private record ConstraintRow(String table, String status, String expectedName, String expectedType,
                                 String expectedColumns, String catalogName, String catalogType,
                                 String catalogColumns, String file) { }
    private record IndexRow(String expectedName, String expectedTable, String status, boolean expectedUnique,
                            String expectedColumns, String catalogTable, boolean catalogUnique,
                            String catalogColumns, String file) { }

    private record Config(Path root, String fileSuffix, int maxFiles, String url, String user, String password,
                          String driver, String expectedDatabase, String expectedSchema, int loginTimeoutSeconds,
                          boolean strictBaseline, boolean failOnExtraIndexes, Path reportBase) {
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
                    Boolean.parseBoolean(System.getProperty("schemaforge.db2luw.p9.failOnExtraIndexes", "false")),
                    Path.of(System.getProperty("schemaforge.db2luw.p9.p92.reportBase", "target/db2luw-p9.2-keys-indexes-reconciliation")));
        }
        boolean enabled() { return root != null; }
        void validate() {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("P9.2 SQL root not found: " + root);
            if (expectedSchema == null || expectedSchema.isBlank()) throw new IllegalArgumentException("P9.2 expected schema required");
        }
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
        private int catalogConstraintCount;
        private int catalogIndexCount;
        private int exactConstraints;
        private int missingConstraints;
        private int mismatchedConstraints;
        private int extraConstraints;
        private int exactIndexes;
        private int missingIndexes;
        private int mismatchedIndexes;
        private int extraIndexes;
        private Duration elapsed = Duration.ZERO;

        private Report(Config config, Path reportDir, int filesDiscovered, ExpectedModel model) {
            this.config = config;
            this.reportDir = reportDir;
            this.filesDiscovered = filesDiscovered;
            this.expectedTables = model.tables().size();
            this.expectedConstraintCount = model.constraintCount();
            this.expectedIndexCount = model.indexCount();
        }

        void write() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(reportDir.resolve("db2luw-p9.2-constraints.csv"), StandardCharsets.UTF_8)) {
                out.write("table,status,expected_name,expected_type,expected_columns,catalog_name,catalog_type,catalog_columns,file\n");
                for (ConstraintRow row : constraintRows) {
                    out.write(csv(row.table()) + "," + csv(row.status()) + "," + csv(row.expectedName()) + ","
                            + csv(row.expectedType()) + "," + csv(row.expectedColumns()) + "," + csv(row.catalogName()) + ","
                            + csv(row.catalogType()) + "," + csv(row.catalogColumns()) + "," + csv(row.file()) + "\n");
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(reportDir.resolve("db2luw-p9.2-indexes.csv"), StandardCharsets.UTF_8)) {
                out.write("expected_name,expected_table,status,expected_unique,expected_columns,catalog_table,catalog_unique,catalog_columns,file\n");
                for (IndexRow row : indexRows) {
                    out.write(csv(row.expectedName()) + "," + csv(row.expectedTable()) + "," + csv(row.status()) + ","
                            + row.expectedUnique() + "," + csv(row.expectedColumns()) + "," + csv(row.catalogTable()) + ","
                            + row.catalogUnique() + "," + csv(row.catalogColumns()) + "," + csv(row.file()) + "\n");
                }
            }
            Files.writeString(reportDir.resolve("db2luw-p9.2-summary.txt"), summary(), StandardCharsets.UTF_8);
        }

        String summary() {
            return "DB2 LUW P9.2 Catalog Reconciliation - PK/UK/Indexes\n"
                    + "=================================================\n"
                    + "Database product          : " + databaseProduct + "\n"
                    + "Database version          : " + databaseVersion + "\n"
                    + "Database                  : " + database.trim() + "\n"
                    + "Authorization ID          : " + authorizationId.trim() + "\n"
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
