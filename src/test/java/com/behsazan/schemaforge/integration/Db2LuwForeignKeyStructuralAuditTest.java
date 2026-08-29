package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Offline P6 audit for Db2 LUW FK blockers produced by {@link Db2LuwForeignKeyDirectoryExecutionIT}.
 *
 * <p>The audit deliberately does not repair DDL. It compares each blocked/skipped FK with all
 * generated historical table revisions and classifies the evidence as version drift, naming drift,
 * key-model gap, or missing generated dependency. This keeps legacy/model defects separate from
 * dialect defects already proven by the live FK validator.</p>
 */
class Db2LuwForeignKeyStructuralAuditTest {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final String IDENTIFIER = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#@]*)";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(" + QUALIFIED_NAME + ")");
    private static final Pattern PRIMARY_KEY = Pattern.compile("(?is)\\bPRIMARY\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern UNIQUE_CONSTRAINT = Pattern.compile(
            "(?is)(?:\\bCONSTRAINT\\s+" + IDENTIFIER + "\\s+)?\\bUNIQUE\\s*\\(([^)]*)\\)");
    private static final Pattern UNIQUE_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+UNIQUE\\s+INDEX\\s+" + IDENTIFIER
                    + "\\s+ON\\s+(" + QUALIFIED_NAME + ")\\s*\\(([^)]*)\\)");

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void recognizesCreateTableWithInlineIssueCommentBeforeBody() {
        String sql = "CREATE TABLE TSTSHMA.PARENT /* [DDL REVIEW] detail (legacy) */\n"
                + "(\n  ID DECIMAL(9,0) NOT NULL,\n  CONSTRAINT PK_PARENT PRIMARY KEY (ID)\n)";
        Matcher create = CREATE_TABLE.matcher(sql);
        org.junit.jupiter.api.Assertions.assertTrue(create.find());
        TableRevision revision = parseCreateTable(normalizeName(create.group(1)), sql, "sample.db2luw.sql");
        org.junit.jupiter.api.Assertions.assertTrue(revision.columns().contains("ID"));
        org.junit.jupiter.api.Assertions.assertTrue(revision.keys().contains("ID"));
    }

    @Test
    void auditForeignKeyStructuralEvidence() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set db2luw.fk.audit.sql.root and db2luw.fk.audit.validationReportDir to run P6 audit.");

        Corpus corpus = loadCorpus(config.sqlRoot(), config.fileSuffix());
        List<InputRow> rows = new ArrayList<>();
        rows.addAll(readValidationCsv(config.validationReportDir().resolve("db2luw-fk-validation-blockers.csv")));
        rows.addAll(readValidationCsv(config.validationReportDir().resolve("db2luw-fk-validation-skipped.csv")));

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        List<AuditRow> audited = rows.stream().map(row -> classify(row, corpus)).toList();
        writeDetails(reportDir.resolve("db2luw-fk-structural-audit.csv"), audited);
        writeSummary(reportDir.resolve("db2luw-fk-structural-audit-summary.txt"), config, corpus, audited);

        System.out.println(summary(config, reportDir, corpus, audited));
        assertEquals(rows.size(), audited.size(), "Every blocker/skip must receive one P6 classification");
    }

    private Corpus loadCorpus(Path root, String suffix) throws IOException {
        Map<String, List<TableRevision>> revisions = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList()) {
                String script = Files.readString(file, StandardCharsets.UTF_8);
                List<String> statements = splitter.parse(script, DatabasePlatform.DB2_LUW);
                TableRevision revision = null;
                for (String raw : statements) {
                    String sql = stripLeadingComments(raw);
                    Matcher create = CREATE_TABLE.matcher(sql);
                    if (create.find()) {
                        String table = normalizeName(create.group(1));
                        revision = parseCreateTable(table, sql, normalize(root.relativize(file)));
                        revisions.computeIfAbsent(key(table), ignored -> new ArrayList<>()).add(revision);
                        continue;
                    }
                    Matcher uniqueIndex = UNIQUE_INDEX.matcher(sql);
                    if (uniqueIndex.find()) {
                        String table = normalizeName(uniqueIndex.group(1));
                        List<TableRevision> tableRevisions = revisions.get(key(table));
                        if (tableRevisions != null && !tableRevisions.isEmpty()) {
                            tableRevisions.get(tableRevisions.size() - 1).uniqueIndexes()
                                    .add(columnKey(parseIdentifierList(uniqueIndex.group(2))));
                        }
                    }
                }
            }
        }
        return new Corpus(revisions);
    }

    private static TableRevision parseCreateTable(String table, String sql, String file) {
        Matcher create = CREATE_TABLE.matcher(sql);
        int open = create.find() ? firstCodeParen(sql, create.end()) : -1;
        int close = matchingParen(sql, open);
        String body = open >= 0 && close > open ? sql.substring(open + 1, close) : "";
        Set<String> columns = new LinkedHashSet<>();
        for (String item : splitTopLevel(body)) {
            String trimmed = item.trim();
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CONSTRAINT ") || upper.startsWith("PRIMARY KEY")
                    || upper.startsWith("UNIQUE ") || upper.startsWith("CHECK ")
                    || upper.startsWith("FOREIGN KEY")) continue;
            Matcher id = Pattern.compile("^\\s*(" + IDENTIFIER + ")").matcher(trimmed);
            if (id.find()) columns.add(identifierKey(id.group(1)));
        }
        Set<String> keys = new LinkedHashSet<>();
        Matcher pk = PRIMARY_KEY.matcher(body);
        while (pk.find()) keys.add(columnKey(parseIdentifierList(pk.group(1))));
        Matcher uk = UNIQUE_CONSTRAINT.matcher(body);
        while (uk.find()) keys.add(columnKey(parseIdentifierList(uk.group(1))));
        return new TableRevision(table, file, columns, keys, new LinkedHashSet<>());
    }

    private static AuditRow classify(InputRow row, Corpus corpus) {
        String tableKey = key(row.referencedTable());
        List<TableRevision> revisions = corpus.revisions().getOrDefault(tableKey, List.of());
        String refColumns = columnKey(parsePipeColumns(row.referencedColumns()));
        String classification;
        String evidence = "";

        if ("REFERENCED_TABLE_NOT_FOUND".equals(row.reason())) {
            if (!revisions.isEmpty()) {
                classification = "TABLE_EXISTS_IN_GENERATED_HISTORY";
                evidence = revisions.get(revisions.size() - 1).file();
            } else {
                String alias = nearestNameVariant(row.referencedTable(), corpus.revisions().keySet());
                if (!alias.isEmpty()) {
                    classification = "NAME_OR_ALIAS_DRIFT";
                    evidence = alias;
                } else {
                    classification = "MISSING_FROM_GENERATED_CORPUS";
                }
            }
        } else if ("REFERENCED_COLUMN_NOT_FOUND".equals(row.reason())) {
            if (revisions.isEmpty()) {
                classification = "MISSING_PARENT_TABLE_EVIDENCE";
            } else {
                Set<String> requested = new LinkedHashSet<>(parsePipeColumns(row.referencedColumns()).stream()
                        .map(Db2LuwForeignKeyStructuralAuditTest::identifierKey).toList());
                List<TableRevision> hits = revisions.stream()
                        .filter(rev -> rev.columns().containsAll(requested)).toList();
                if (!hits.isEmpty()) {
                    classification = "COLUMN_VERSION_DRIFT";
                    evidence = hits.get(hits.size() - 1).file();
                } else {
                    classification = "COLUMN_REFERENCE_OR_EXTRACTION_GAP";
                }
            }
        } else if ("REFERENCED_COLUMNS_NOT_PK_OR_UNIQUE".equals(row.reason())) {
            List<TableRevision> keyHits = revisions.stream().filter(rev -> rev.keys().contains(refColumns)).toList();
            List<TableRevision> uniqueIndexHits = revisions.stream()
                    .filter(rev -> rev.uniqueIndexes().contains(refColumns)).toList();
            if (!keyHits.isEmpty()) {
                classification = "KEY_VERSION_DRIFT";
                evidence = keyHits.get(keyHits.size() - 1).file();
            } else if (!uniqueIndexHits.isEmpty()) {
                classification = "UNIQUE_INDEX_EVIDENCE_ONLY";
                evidence = uniqueIndexHits.get(uniqueIndexHits.size() - 1).file();
            } else {
                classification = "KEY_MODEL_OR_EXTRACTION_GAP";
            }
        } else {
            classification = "UNCLASSIFIED_" + row.reason();
        }
        return new AuditRow(row, classification, evidence);
    }

    private static String nearestNameVariant(String referencedTable, Set<String> availableKeys) {
        String target = objectNameOnly(referencedTable);
        String targetCore = stripLegacyPrefixes(target);
        return availableKeys.stream()
                .map(Db2LuwForeignKeyStructuralAuditTest::objectNameOnly)
                .filter(candidate -> !candidate.equals(target))
                .filter(candidate -> stripLegacyPrefixes(candidate).equals(targetCore)
                        || candidate.endsWith(target) || target.endsWith(candidate))
                .sorted(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                .findFirst().orElse("");
    }

    private static String stripLegacyPrefixes(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        for (String prefix : List.of("JT", "J")) {
            if (upper.startsWith(prefix) && upper.length() > prefix.length() + 2) {
                return upper.substring(prefix.length());
            }
        }
        return upper;
    }

    private static List<InputRow> readValidationCsv(Path file) throws IOException {
        if (!Files.exists(file)) return List.of();
        List<InputRow> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String> headers = parseCsvLine(in.readLine());
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) index.put(headers.get(i), i);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                rows.add(new InputRow(
                        get(values, index, "file"), get(values, index, "statement"),
                        get(values, index, "source_table"), get(values, index, "constraint_name"),
                        get(values, index, "source_columns"), get(values, index, "referenced_table"),
                        get(values, index, "referenced_columns"), get(values, index, "reason"),
                        get(values, index, "detail")));
            }
        }
        return rows;
    }

    private static List<String> parseCsvLine(String line) {
        if (line == null) return List.of();
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    token.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                result.add(token.toString()); token.setLength(0);
            } else token.append(ch);
        }
        result.add(token.toString());
        return result;
    }

    private static String get(List<String> values, Map<String, Integer> index, String key) {
        Integer i = index.get(key);
        return i == null || i >= values.size() ? "" : values.get(i);
    }

    private static void writeDetails(Path file, List<AuditRow> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("source_file,statement,source_table,constraint_name,source_columns,referenced_table,referenced_columns,validation_reason,p6_classification,evidence\n");
            for (AuditRow row : rows) {
                InputRow in = row.input();
                out.write(String.join(",", csv(in.file()), csv(in.statement()), csv(in.sourceTable()),
                        csv(in.constraintName()), csv(in.sourceColumns()), csv(in.referencedTable()),
                        csv(in.referencedColumns()), csv(in.reason()), csv(row.classification()), csv(row.evidence())));
                out.newLine();
            }
        }
    }

    private static void writeSummary(Path file, Config config, Corpus corpus, List<AuditRow> rows) throws IOException {
        Files.writeString(file, summary(config, file.getParent(), corpus, rows), StandardCharsets.UTF_8);
    }

    private static String summary(Config config, Path reportDir, Corpus corpus, List<AuditRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.stream().map(AuditRow::classification).sorted().forEach(value -> counts.merge(value, 1L, Long::sum));
        StringBuilder out = new StringBuilder();
        out.append("Db2 LUW FK structural audit (P6)\n")
                .append("=================================\n")
                .append("SQL root              : ").append(config.sqlRoot()).append('\n')
                .append("Validation report dir : ").append(config.validationReportDir()).append('\n')
                .append("Historical table keys : ").append(corpus.revisions().size()).append('\n')
                .append("Rows audited          : ").append(rows.size()).append('\n');
        counts.forEach((key, value) -> out.append(String.format(Locale.ROOT, "%-24s: %d%n", key, value)));
        out.append("Report directory       : ").append(reportDir).append('\n');
        return out.toString();
    }


    /** Finds the CREATE TABLE body opener while ignoring generated inline issue comments. */
    private static int firstCodeParen(String value, int start) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = Math.max(0, start); i < value.length(); i++) {
            char ch = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && ch == '-' && next == '-') { lineComment = true; i++; continue; }
            if (!singleQuoted && !doubleQuoted && ch == '/' && next == '*') { blockComment = true; i++; continue; }
            if (!doubleQuoted && ch == '\'') {
                if (singleQuoted && next == '\'') { i++; continue; }
                singleQuoted = !singleQuoted;
                continue;
            }
            if (!singleQuoted && ch == '"') {
                if (doubleQuoted && next == '"') { i++; continue; }
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && ch == '(') return i;
        }
        return -1;
    }

    private static int matchingParen(String value, int open) {
        if (open < 0) return -1;
        int depth = 0; boolean quoted = false;
        for (int i = open; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (quoted) continue;
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value) {
        List<String> result = new ArrayList<>(); StringBuilder token = new StringBuilder();
        int depth = 0; boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) { result.add(token.toString()); token.setLength(0); continue; }
            }
            token.append(ch);
        }
        if (!token.toString().isBlank()) result.add(token.toString());
        return result;
    }

    private static List<String> parseIdentifierList(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.split("\\s*,\\s*")) if (!token.isBlank()) result.add(token.trim());
        return result;
    }

    private static List<String> parsePipeColumns(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.split("\\|")) if (!token.isBlank()) result.add(token.trim());
        return result;
    }

    private static String columnKey(List<String> columns) {
        return columns.stream().map(Db2LuwForeignKeyStructuralAuditTest::identifierKey)
                .reduce((a, b) -> a + "|" + b).orElse("");
    }

    private static String identifierKey(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
            v = v.substring(1, v.length() - 1).replace("\"\"", "\"");
        return v.toUpperCase(Locale.ROOT);
    }

    private static String key(String value) { return normalizeName(value).replace("\"", "").toUpperCase(Locale.ROOT); }
    private static String objectNameOnly(String value) {
        String normalized = key(value); int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }
    private static String normalizeName(String value) { return value.replaceAll("\\s*\\.\\s*", ".").trim(); }
    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql;
        boolean changed;
        do {
            changed = false; String trimmed = value.stripLeading();
            if (trimmed.startsWith("--")) { int n = trimmed.indexOf('\n'); value = n < 0 ? "" : trimmed.substring(n + 1); changed = true; }
            else if (trimmed.startsWith("/*")) { int e = trimmed.indexOf("*/", 2); value = e < 0 ? "" : trimmed.substring(e + 2); changed = true; }
        } while (changed);
        return value.stripLeading();
    }
    private static String csv(String value) { String s = value == null ? "" : value; return '"' + s.replace("\"", "\"\"") + '"'; }

    private record TableRevision(String table, String file, Set<String> columns, Set<String> keys, Set<String> uniqueIndexes) {}
    private record Corpus(Map<String, List<TableRevision>> revisions) {}
    private record InputRow(String file, String statement, String sourceTable, String constraintName,
                            String sourceColumns, String referencedTable, String referencedColumns,
                            String reason, String detail) {}
    private record AuditRow(InputRow input, String classification, String evidence) {}

    private record Config(Path sqlRoot, String fileSuffix, Path validationReportDir, Path reportBase) {
        static Config load() {
            return new Config(path(System.getProperty("db2luw.fk.audit.sql.root")),
                    System.getProperty("db2luw.fk.audit.fileSuffix", ".db2luw.sql"),
                    path(System.getProperty("db2luw.fk.audit.validationReportDir")),
                    pathOrDefault(System.getProperty("db2luw.fk.audit.reportBase"),
                            Path.of("target", "db2luw-fk-structural-audit")));
        }
        boolean enabled() { return sqlRoot != null && validationReportDir != null; }
        private static Path path(String value) { return value == null || value.isBlank() ? null : Path.of(value); }
        private static Path pathOrDefault(String value, Path fallback) { return value == null || value.isBlank() ? fallback : Path.of(value); }
    }
}
