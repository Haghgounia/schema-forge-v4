package com.behsazan.schemaforge.testsupport;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour-oriented assertions for generated SQL.
 *
 * <p>The helper deliberately avoids asserting an entire rendered SQL line so tests remain
 * stable when harmless whitespace, datatype formatting, or comment ordering changes.</p>
 */
public final class SqlAssertionHelper {

    private SqlAssertionHelper() {
    }

    public static void assertValidationHeaderBeforeDdl(String sql, String ddlHeader) {
        assertNotNull(sql, "SQL must not be null");
        int findings = sql.indexOf("SchemaForge Validation Findings");
        int ddl = sql.indexOf(ddlHeader);
        assertTrue(findings >= 0, "Validation findings header was not generated");
        assertTrue(ddl >= 0, "DDL header was not generated: " + ddlHeader);
        assertTrue(findings < ddl, "Validation findings must appear before the DDL header");
    }

    public static void assertHeaderContainsIssue(String sql, String severity, String code, String path) {
        String expected = "[" + severity + "] " + code + " [" + path + "]";
        assertTrue(sql.contains(expected), "Expected validation issue was not found: " + expected);
    }

    public static void assertColumnContains(String sql, String columnName, String... fragments) {
        String line = findColumnLine(sql, columnName);
        for (String fragment : fragments) {
            assertTrue(line.contains(fragment),
                    () -> "Column " + columnName + " does not contain fragment '" + fragment + "': " + line);
        }
    }

    public static void assertInlineIssues(String sql, String columnName, String severityPrefix, String... issueCodes) {
        String line = findColumnLine(sql, columnName);
        int commentIndex = line.indexOf("--");
        assertTrue(commentIndex >= 0, "Column has no inline issue comment: " + line);

        String comment = line.substring(commentIndex + 2).trim().toUpperCase(Locale.ROOT);
        String marker = severityPrefix.toUpperCase(Locale.ROOT) + ":";
        int markerIndex = comment.indexOf(marker);
        assertTrue(markerIndex >= 0, "Inline severity group " + marker + " was not found: " + line);

        String group = comment.substring(markerIndex + marker.length()).split("\\s+", 2)[0];
        List<String> actualCodes = Arrays.stream(group.split("\\|"))
                .filter(value -> !value.isBlank())
                .toList();
        for (String issueCode : issueCodes) {
            assertTrue(actualCodes.contains(issueCode.toUpperCase(Locale.ROOT)),
                    () -> "Inline issue " + issueCode + " was not found for " + columnName + ": " + line);
        }
    }

    public static void assertColumnGeneratedOnce(String sql, String columnName) {
        long count = sql.lines()
                .map(String::stripLeading)
                .filter(line -> startsWithIdentifier(line, columnName))
                .count();
        assertEquals(1, count, "Column must be generated exactly once: " + columnName);
    }

    public static void assertNoInlineIssue(String sql, String columnName) {
        String line = findColumnLine(sql, columnName);
        assertFalse(line.contains("--"), "Unexpected inline issue comment: " + line);
    }

    private static String findColumnLine(String sql, String columnName) {
        return sql.lines()
                .map(String::stripLeading)
                .filter(line -> startsWithIdentifier(line, columnName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Column line was not found: " + columnName));
    }

    private static boolean startsWithIdentifier(String line, String columnName) {
        String normalized = line.toUpperCase(Locale.ROOT);
        String identifier = columnName.toUpperCase(Locale.ROOT);
        return normalized.startsWith(identifier + " ")
                || normalized.startsWith('"' + identifier + "\" ");
    }
}
