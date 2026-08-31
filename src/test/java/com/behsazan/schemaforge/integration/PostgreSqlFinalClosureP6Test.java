package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL PG-P6 final closure gate.
 *
 * <p>This gate is evidence-only and deterministic. It freezes the accepted PG-P2 historical
 * execution result plus the PG-P4 key/index and PG-P5 FK catalog reconciliations. The live PG-P3,
 * PG-P4 and PG-P5 tests are intentionally kept separate and can be run alongside this gate when a
 * database-backed final closure is required. No generated SQL or PostgreSQL state is mutated here.</p>
 */
class PostgreSqlFinalClosureP6Test {
    private static final String P2_SUMMARY =
            "evidence/postgresql-pg-p2/20260830_135727_317/postgresql-sql-execution-summary.txt";
    private static final String P2_ERRORS =
            "evidence/postgresql-pg-p2/20260830_135727_317/postgresql-sql-execution-errors.csv";
    private static final String P4_SUMMARY =
            "evidence/postgresql-p4/20260831_085530_341/postgresql-p4-summary.txt";
    private static final String P4_CONSTRAINTS =
            "evidence/postgresql-p4/20260831_085530_341/postgresql-p4-constraints.csv";
    private static final String P4_INDEXES =
            "evidence/postgresql-p4/20260831_085530_341/postgresql-p4-indexes.csv";
    private static final String P5_SUMMARY =
            "evidence/postgresql-p5/20260831_091154_399/postgresql-p5-summary.txt";
    private static final String P5_ROWS =
            "evidence/postgresql-p5/20260831_091154_399/postgresql-p5-fk-reconciliation.csv";
    private static final String P5_CLEANUP =
            "evidence/postgresql-p5/20260831_091154_399/postgresql-p5-cleanup-errors.csv";

    @Test
    void closesPostgreSqlWithEvidenceBackedP2P4AndP5Baseline() throws Exception {
        Map<String, String> p2 = readSummary(P2_SUMMARY);
        assertEquals("5321", p2.get("Files discovered"));
        assertEquals("134075", p2.get("Statements executed"));
        assertEquals("134075", p2.get("Statements succeeded"));
        assertEquals("0", p2.get("Statements failed"));
        assertEquals("0", p2.get("Actionable failures"));
        assertEquals("1325", p2.get("Statements skipped"));
        assertEquals("10642", p2.get("psql commands skipped"));
        assertEquals("5321", p2.get("Cleanup attempted"));
        assertEquals("5321", p2.get("Cleanup succeeded"));
        assertEquals("0", p2.get("Cleanup failed"));
        assertEquals("HISTORICAL", p2.get("Execution mode"));
        assertTrue(readCsvAllowEmpty(P2_ERRORS).isEmpty());

        Map<String, String> p4 = readSummary(P4_SUMMARY);
        assertEquals("5321", p4.get("Files discovered"));
        assertEquals("2670", p4.get("Expected final tables"));
        assertEquals("2265", p4.get("Expected PK/UK constraints"));
        assertEquals("2265", p4.get("Catalog PK/UK constraints"));
        assertEquals("2265", p4.get("Exact PK/UK constraints"));
        assertEquals("0", p4.get("Missing constraints"));
        assertEquals("0", p4.get("Mismatched constraints"));
        assertEquals("0", p4.get("Extra catalog constraints"));
        assertEquals("1372", p4.get("Expected explicit indexes"));
        assertEquals("3637", p4.get("Catalog indexes"));
        assertEquals("1372", p4.get("Exact explicit indexes"));
        assertEquals("0", p4.get("Missing explicit indexes"));
        assertEquals("0", p4.get("Mismatched indexes"));
        assertEquals("2265", p4.get("Extra catalog indexes"));
        assertEquals(integer(p4, "Catalog indexes"),
                integer(p4, "Catalog PK/UK constraints") + integer(p4, "Expected explicit indexes"));

        List<Map<String, String>> constraints = readCsv(P4_CONSTRAINTS);
        assertEquals(2265, constraints.size());
        assertTrue(constraints.stream().allMatch(row -> "EXACT".equals(row.get("status"))));

        List<Map<String, String>> indexes = readCsv(P4_INDEXES);
        assertEquals(1372, indexes.size());
        assertTrue(indexes.stream().allMatch(row -> "EXACT".equals(row.get("status"))));

        Map<String, String> p5 = readSummary(P5_SUMMARY);
        assertEquals("5321", p5.get("Files discovered"));
        assertEquals("2670", p5.get("Selected final tables"));
        assertEquals("574", p5.get("Final FK candidates"));
        assertEquals("249", p5.get("Structurally eligible"));
        assertEquals("325", p5.get("Structural blockers"));
        assertEquals("249", p5.get("Create attempts"));
        assertEquals("249", p5.get("Created for validation"));
        assertEquals("0", p5.get("Pre-existing exact"));
        assertEquals("249", p5.get("Catalog exact"));
        assertEquals("0", p5.get("Catalog mismatch"));
        assertEquals("0", p5.get("Execution errors"));
        assertEquals("0", p5.get("Cleanup errors"));
        assertEquals(p5.get("Catalog FK count before"), p5.get("Catalog FK count after"));
        assertEquals("true", p5.get("Persistent state preserved"));
        assertEquals("false", p5.get("Blocker fail policy"));
        assertEquals(integer(p5, "Final FK candidates"),
                integer(p5, "Structurally eligible") + integer(p5, "Structural blockers"));

        List<Map<String, String>> fkRows = readCsv(P5_ROWS);
        assertEquals(574, fkRows.size());
        assertEquals(574, fkRows.stream()
                .map(row -> row.get("source_table") + "|" + row.get("constraint_name"))
                .distinct().count());
        assertEquals(249, count(fkRows, "status", "CREATED_AND_EXACT"));
        assertEquals(325, count(fkRows, "status", "BLOCKED"));
        assertEquals(178, count(fkRows, "detail", "REFERENCED_TABLE_MISSING"));
        assertEquals(81, count(fkRows, "detail", "REFERENCED_COLUMN_MISSING"));
        assertEquals(66, count(fkRows, "detail", "REFERENCED_COLUMNS_NOT_UNIQUE"));
        assertTrue(fkRows.stream().allMatch(row -> {
            String status = row.get("status");
            return "CREATED_AND_EXACT".equals(status) || "BLOCKED".equals(status);
        }));
        assertTrue(readCsvAllowEmpty(P5_CLEANUP).isEmpty());

        System.out.println("PostgreSQL PG-P6 Final Closure Gate");
        System.out.println("==================================");
        System.out.println("Generated files                    : 5321");
        System.out.println("Historical SQL execution           : 134075 / 134075 succeeded");
        System.out.println("Selected final tables              : 2670");
        System.out.println("PK/UK constraints                  : 2265 / 2265 exact");
        System.out.println("Explicit generated indexes         : 1372 / 1372 exact");
        System.out.println("Final FK candidates                : 574");
        System.out.println("Structurally eligible/live FKs      : 249 / 249 exact");
        System.out.println("Structural blockers                : 325");
        System.out.println("  referenced table missing         : 178");
        System.out.println("  referenced column missing        : 81");
        System.out.println("  referenced columns not unique    : 66");
        System.out.println("P5 persistent FK state preserved  : true");
        System.out.println("Synthetic key policy               : FORBIDDEN");
        System.out.println("PostgreSQL status                  : PG-P6 CLOSED BASELINE");
    }

    private static int count(List<Map<String, String>> rows, String key, String expected) {
        return (int) rows.stream().filter(row -> expected.equals(row.get(key))).count();
    }

    private static int integer(Map<String, String> summary, String key) {
        return Integer.parseInt(summary.get(key));
    }

    private static Map<String, String> readSummary(String resource) throws IOException {
        InputStream input = PostgreSqlFinalClosureP6Test.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(input, "Missing retained evidence resource: " + resource);
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (!key.isEmpty()) values.put(key, value);
            }
        }
        assertFalse(values.isEmpty(), "No summary values in retained evidence: " + resource);
        return values;
    }

    private static List<Map<String, String>> readCsv(String resource) throws IOException {
        List<Map<String, String>> rows = readCsvAllowEmpty(resource);
        assertFalse(rows.isEmpty(), "No rows in retained evidence: " + resource);
        return rows;
    }

    private static List<Map<String, String>> readCsvAllowEmpty(String resource) throws IOException {
        InputStream input = PostgreSqlFinalClosureP6Test.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(input, "Missing retained evidence resource: " + resource);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertNotNull(header, "Missing CSV header: " + resource);
            List<String> names = parseCsvLine(header);
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                assertEquals(names.size(), values.size(), "CSV width mismatch in " + resource);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) row.put(names.get(i), values.get(i));
                rows.add(row);
            }
            return rows;
        }
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
        assertFalse(quoted, "Unclosed CSV quote");
        return values;
    }
}
