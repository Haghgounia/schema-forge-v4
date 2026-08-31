package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL PG-P1 final corpus inventory gate.
 *
 * <p>This test is intentionally offline/read-only. It freezes the accepted R7.2 PostgreSQL corpus
 * size before live execution begins and records the repeated-table/final-table shape without
 * mutating generated SQL or a database.</p>
 */
class PostgreSqlFinalCorpusInventoryP1Test {

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QUALIFIED_NAME =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:(?:UNLOGGED|TEMP|TEMPORARY)\\s+)?TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + QUALIFIED_NAME + ")");
    private static final Pattern FOREIGN_KEY = Pattern.compile("(?is)\\bFOREIGN\\s+KEY\\s*\\(");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?is)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b");
    private static final Pattern GRANT = Pattern.compile("(?im)^\\s*GRANT\\b");

    @Test
    void inventoriesAcceptedPostgreSqlFinalCorpus() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set schemaforge.postgresql.p1.sqlRoot (or postgresql.sql.root) to run PG-P1 inventory.");
        assertTrue(Files.isDirectory(config.sqlRoot()), "PostgreSQL corpus directory not found: " + config.sqlRoot());

        List<Path> files = findSqlFiles(config.sqlRoot(), config.fileSuffix());
        assertEquals(config.expectedFiles(), files.size(),
                "PostgreSQL accepted corpus file count changed");

        int filesWithCreate = 0;
        int createEvents = 0;
        int fkOccurrences = 0;
        int explicitIndexes = 0;
        int grantOccurrences = 0;
        Set<String> distinctTables = new LinkedHashSet<>();
        Map<String, Integer> tableVersions = new LinkedHashMap<>();
        List<String> anomalies = new ArrayList<>();
        anomalies.add("file,issue,detail");

        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            String lexical = stripSqlComments(sql);
            Matcher create = CREATE_TABLE.matcher(lexical);
            int perFileCreates = 0;
            while (create.find()) {
                perFileCreates++;
                createEvents++;
                String table = normalizeQualifiedName(create.group(1));
                distinctTables.add(table);
                tableVersions.merge(table, 1, Integer::sum);
            }
            if (perFileCreates > 0) filesWithCreate++;
            if (perFileCreates != 1) {
                anomalies.add(csv(relative(config.sqlRoot(), file)) + "," + csv("CREATE_TABLE_COUNT") + "," + csv(Integer.toString(perFileCreates)));
            }
            fkOccurrences += count(FOREIGN_KEY, lexical);
            explicitIndexes += count(CREATE_INDEX, lexical);
            grantOccurrences += count(GRANT, lexical);
        }

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("postgresql-p1-anomalies.csv"),
                String.join(System.lineSeparator(), anomalies) + System.lineSeparator(), StandardCharsets.UTF_8);

        List<String> versions = new ArrayList<>();
        versions.add("table,versions");
        tableVersions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> versions.add(csv(entry.getKey()) + "," + entry.getValue()));
        Files.writeString(reportDir.resolve("postgresql-p1-table-versions.csv"),
                String.join(System.lineSeparator(), versions) + System.lineSeparator(), StandardCharsets.UTF_8);

        String summary = """
                PostgreSQL PG-P1 Final Corpus Inventory
                =======================================
                SQL root                 : %s
                Files discovered         : %d
                Expected accepted files  : %d
                Files with CREATE TABLE  : %d
                CREATE TABLE events      : %d
                Distinct logical tables  : %d
                Repeated table revisions : %d
                Foreign key occurrences  : %d
                Explicit index occurrences: %d
                GRANT occurrences        : %d
                Files with create anomaly: %d
                Mutation policy          : READ ONLY; GENERATED CORPUS UNCHANGED
                Report directory         : %s
                """.formatted(
                config.sqlRoot(), files.size(), config.expectedFiles(), filesWithCreate, createEvents,
                distinctTables.size(), createEvents - distinctTables.size(), fkOccurrences,
                explicitIndexes, grantOccurrences, anomalies.size() - 1, reportDir);
        Files.writeString(reportDir.resolve("postgresql-p1-summary.txt"), summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        assertEquals(files.size(), filesWithCreate,
                "Every accepted PostgreSQL snapshot script must contain one CREATE TABLE");
        assertEquals(files.size(), createEvents,
                "Expected exactly one CREATE TABLE event per accepted PostgreSQL script");
        assertEquals(1, anomalies.size(), "PostgreSQL corpus contains CREATE TABLE inventory anomalies");
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

    private static int count(Pattern pattern, String value) {
        int result = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) result++;
        return result;
    }

    /** Removes line/block comments while preserving quoted strings and quoted identifiers. */
    static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char n = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && n == '/') {
                    out.append("  ");
                    i++;
                    blockComment = false;
                } else {
                    out.append(c == '\n' || c == '\r' ? c : ' ');
                }
                continue;
            }
            if (!single && !quotedIdentifier && c == '-' && n == '-') {
                out.append("  ");
                i++;
                lineComment = true;
                continue;
            }
            if (!single && !quotedIdentifier && c == '/' && n == '*') {
                out.append("  ");
                i++;
                blockComment = true;
                continue;
            }
            if (!quotedIdentifier && c == '\'') {
                out.append(c);
                if (single && n == '\'') {
                    out.append(n);
                    i++;
                } else {
                    single = !single;
                }
                continue;
            }
            if (!single && c == '"') {
                out.append(c);
                if (quotedIdentifier && n == '"') {
                    out.append(n);
                    i++;
                } else {
                    quotedIdentifier = !quotedIdentifier;
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String normalizeQualifiedName(String value) {
        return value.replaceAll("\\s*\\.\\s*", ".").trim().toUpperCase(Locale.ROOT);
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record Config(Path sqlRoot, String fileSuffix, int expectedFiles, Path reportBase) {
        static Config load() {
            String root = firstNonBlank(
                    System.getProperty("schemaforge.postgresql.p1.sqlRoot"),
                    System.getProperty("postgresql.sql.root"),
                    System.getenv("POSTGRESQL_SQL_ROOT"));
            return new Config(
                    root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("schemaforge.postgresql.p1.fileSuffix", ".postgresql.sql"),
                    Integer.parseInt(System.getProperty("schemaforge.postgresql.p1.expectedFiles", "5321")),
                    Path.of(System.getProperty("schemaforge.postgresql.p1.report.dir",
                            "target/postgresql-p1-final-corpus-inventory")).toAbsolutePath().normalize());
        }

        boolean enabled() {
            return sqlRoot != null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }
}
