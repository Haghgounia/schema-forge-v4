package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.validation.db2zos.Db2ZosOfflineDdlValidator;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosOfflineValidationIssue;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosOfflineValidationResult;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only offline gate for the generated Db2 for z/OS legacy corpus.
 *
 * <p>The test deliberately consumes already generated SQL instead of reparsing Legacy Word or
 * regenerating canonical JSON. It validates the accepted generated corpus with the production
 * {@link Db2ZosOfflineDdlValidator} and writes deterministic evidence under target/.</p>
 */
class Db2ZosGeneratedSqlCorpusOfflineValidationTest {

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)\\bCREATE\\s+TABLE\\b");
    private static final Pattern EXECUTABLE_PLACEHOLDER = Pattern.compile("<[^>\\r\\n]+>");

    @Test
    void validatesAcceptedGeneratedDb2ZosCorpusOffline() throws Exception {
        Config config = Config.load();
        Assumptions.assumeTrue(config.enabled(),
                "Set -Dschemaforge.db2zos.offline.sqlRoot=<generated Db2 z/OS SQL directory> to run this gate.");
        assertTrue(Files.isDirectory(config.sqlRoot()),
                "Db2 z/OS generated SQL directory not found: " + config.sqlRoot());

        List<Path> files = findSqlFiles(config.sqlRoot(), config.fileSuffix());
        assertEquals(config.expectedFiles(), files.size(),
                "Db2 z/OS accepted generated corpus file count changed");

        Db2ZosOfflineDdlValidator validator = new Db2ZosOfflineDdlValidator();
        int validFiles = 0;
        int invalidFiles = 0;
        long totalStatements = 0;
        int createTableAnomalies = 0;
        int executablePlaceholderFiles = 0;
        Map<String, Integer> issueCounts = new LinkedHashMap<>();
        List<String> issueRows = new ArrayList<>();
        issueRows.add("file,severity,code,statement,message");

        int processed = 0;
        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            Db2ZosOfflineValidationResult result = validator.validate(sql);
            totalStatements += result.statementCount();

            String lexical = stripComments(sql);
            int createTables = count(CREATE_TABLE, lexical);
            if (createTables != 1) {
                createTableAnomalies++;
                addIssue(issueRows, config.sqlRoot(), file, "ERROR", "CREATE_TABLE_COUNT", 0,
                        "Expected exactly one CREATE TABLE but found " + createTables);
                issueCounts.merge("CREATE_TABLE_COUNT", 1, Integer::sum);
            }

            Matcher placeholder = EXECUTABLE_PLACEHOLDER.matcher(lexical);
            if (placeholder.find()) {
                executablePlaceholderFiles++;
                addIssue(issueRows, config.sqlRoot(), file, "ERROR", "EXECUTABLE_PLACEHOLDER", 0,
                        "Unresolved executable placeholder: " + placeholder.group());
                issueCounts.merge("EXECUTABLE_PLACEHOLDER", 1, Integer::sum);
            }

            if (result.valid() && createTables == 1 && !placeholder.find(0)) {
                validFiles++;
            } else {
                invalidFiles++;
            }

            for (Db2ZosOfflineValidationIssue issue : result.issues()) {
                issueCounts.merge(issue.code(), 1, Integer::sum);
                addIssue(issueRows, config.sqlRoot(), file, issue.severity(), issue.code(),
                        issue.statementNumber(), issue.message());
            }

            processed++;
            if (processed % 250 == 0 || processed == files.size()) {
                System.out.printf(Locale.ROOT,
                        "Db2 z/OS offline corpus: %d / %d, valid=%d, invalid=%d, issues=%d%n",
                        processed, files.size(), validFiles, invalidFiles, issueRows.size() - 1);
            }
        }

        Path reportDir = config.reportBase().resolve(LocalDateTime.now().format(RUN_ID));
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("db2zos-offline-corpus-issues.csv"),
                String.join(System.lineSeparator(), issueRows) + System.lineSeparator(), StandardCharsets.UTF_8);

        List<String> countRows = new ArrayList<>();
        countRows.add("code,count");
        issueCounts.forEach((code, count) -> countRows.add(csv(code) + "," + count));
        Files.writeString(reportDir.resolve("db2zos-offline-corpus-issue-counts.csv"),
                String.join(System.lineSeparator(), countRows) + System.lineSeparator(), StandardCharsets.UTF_8);

        StringBuilder breakdown = new StringBuilder();
        if (issueCounts.isEmpty()) {
            breakdown.append("  none").append(System.lineSeparator());
        } else {
            issueCounts.forEach((code, count) -> breakdown.append(String.format(Locale.ROOT,
                    "  %-40s %d%n", code, count)));
        }

        String summary = """
                Db2 z/OS Generated SQL Offline Corpus Validation
                ================================================
                SQL root                  : %s
                Files discovered          : %d
                Expected accepted files   : %d
                Valid files               : %d
                Invalid files             : %d
                Executable statements     : %d
                CREATE TABLE anomalies    : %d
                Executable placeholders   : %d
                Issue classifications:
                %sMutation policy           : READ ONLY; GENERATED SQL UNCHANGED
                Legacy Word policy        : NOT READ / NOT REPARSED
                Canonical JSON policy     : NOT REGENERATED
                Report directory          : %s
                """.formatted(
                config.sqlRoot(), files.size(), config.expectedFiles(), validFiles, invalidFiles,
                totalStatements, createTableAnomalies, executablePlaceholderFiles,
                breakdown, reportDir);
        Files.writeString(reportDir.resolve("db2zos-offline-corpus-summary.txt"), summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        assertEquals(0, invalidFiles, "Db2 z/OS generated SQL corpus contains offline validation failures");
        assertEquals(0, createTableAnomalies, "Db2 z/OS generated SQL corpus contains CREATE TABLE anomalies");
        assertEquals(0, executablePlaceholderFiles,
                "Db2 z/OS generated SQL corpus contains unresolved executable placeholders");
        assertEquals(files.size(), validFiles, "Every accepted Db2 z/OS generated script must pass offline validation");
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

    private static void addIssue(List<String> rows, Path root, Path file, String severity,
                                 String code, int statement, String message) {
        rows.add(csv(relative(root, file)) + "," + csv(severity) + "," + csv(code) + ","
                + statement + "," + csv(message));
    }

    /** Removes SQL line/block comments while preserving string literals. */
    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean single = false;
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char n = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n' || c == '\r') {
                    line = false;
                    out.append(c);
                } else out.append(' ');
                continue;
            }
            if (block) {
                if (c == '*' && n == '/') {
                    out.append("  ");
                    i++;
                    block = false;
                } else out.append(c == '\n' || c == '\r' ? c : ' ');
                continue;
            }
            if (!single && c == '-' && n == '-') {
                out.append("  ");
                i++;
                line = true;
                continue;
            }
            if (!single && c == '/' && n == '*') {
                out.append("  ");
                i++;
                block = true;
                continue;
            }
            if (c == '\'' && single && n == '\'') {
                out.append(c).append(n);
                i++;
                continue;
            }
            if (c == '\'') single = !single;
            out.append(c);
        }
        return out.toString();
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
                    System.getProperty("schemaforge.db2zos.offline.sqlRoot"),
                    System.getProperty("db2zos.sql.root"),
                    System.getenv("DB2ZOS_SQL_ROOT"));
            return new Config(
                    root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize(),
                    System.getProperty("schemaforge.db2zos.offline.fileSuffix", ".db2zos.sql"),
                    Integer.parseInt(System.getProperty("schemaforge.db2zos.offline.expectedFiles", "4693")),
                    Path.of(System.getProperty("schemaforge.db2zos.offline.report.dir",
                            "target/db2zos-generated-sql-offline-validation")).toAbsolutePath().normalize());
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
