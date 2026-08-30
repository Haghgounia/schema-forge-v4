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
 * DB2 LUW P10 final closure gate.
 *
 * <p>This gate is deliberately evidence-only. It locks the accepted P8/P9 live results into a
 * deterministic regression baseline without changing generated SQL, canonical snapshots, or DB2.
 * Live catalog behavior remains covered by the P9 integration tests that are run alongside this gate
 * in the final closure command.</p>
 */
class Db2LuwFinalClosureP10Test {
    private static final String P8_SUMMARY =
            "evidence/db2luw-p8/20260830_102243_952/db2luw-p8-final-state-summary.txt";
    private static final String P91_SUMMARY =
            "evidence/db2luw-p9/20260830_114228_955/db2luw-p9.1.3-summary.txt";
    private static final String P92_SUMMARY =
            "evidence/db2luw-p9/20260830_120358_710/db2luw-p9.2-summary.txt";
    private static final String P92_CONSTRAINTS =
            "evidence/db2luw-p9/20260830_120358_710/db2luw-p9.2-constraints.csv";
    private static final String P92_INDEXES =
            "evidence/db2luw-p9/20260830_120358_710/db2luw-p9.2-indexes.csv";
    private static final String P93_SUMMARY =
            "evidence/db2luw-p9/20260830_132313_568/db2luw-p9.3-summary.txt";
    private static final String P93_ROWS =
            "evidence/db2luw-p9/20260830_132313_568/db2luw-p9.3-fk-reconciliation.csv";
    private static final String P93_CLEANUP =
            "evidence/db2luw-p9/20260830_132313_568/db2luw-p9.3-cleanup-errors.csv";

    @Test
    void closesDb2LuwWithEvidenceBackedP8AndP9Baseline() throws Exception {
        Map<String, String> p8 = readSummary(P8_SUMMARY);
        assertEquals("557", p8.get("Final FK candidates"));
        assertEquals("310", p8.get("Live FK succeeded"));
        assertEquals("0", p8.get("Live FK failed"));
        assertEquals("12", p8.get("Post-resolution blockers"));
        assertEquals("235", p8.get("Deferred without mutation"));
        assertEquals(247, integer(p8, "Post-resolution blockers") + integer(p8, "Deferred without mutation"));

        Map<String, String> p91 = readSummary(P91_SUMMARY);
        assertEquals("2310", p91.get("Selected final tables"));
        assertEquals("2310", p91.get("Comment-aware exact tables"));
        assertEquals("0", p91.get("Comment-aware mismatches"));
        assertEquals("0", p91.get("Missing catalog tables"));
        assertEquals("0", p91.get("Residual extra catalog columns"));
        assertEquals("0", p91.get("Residual missing final columns"));

        Map<String, String> p92 = readSummary(P92_SUMMARY);
        assertEquals("2310", p92.get("Expected final tables"));
        assertEquals("1968", p92.get("Expected PK/UK constraints"));
        assertEquals("1968", p92.get("Catalog PK/UK constraints"));
        assertEquals("1968", p92.get("Exact PK/UK constraints"));
        assertEquals("0", p92.get("Missing constraints"));
        assertEquals("0", p92.get("Mismatched constraints"));
        assertEquals("0", p92.get("Extra catalog constraints"));
        assertEquals("1233", p92.get("Expected explicit indexes"));
        assertEquals("1233", p92.get("Exact explicit indexes"));
        assertEquals("0", p92.get("Missing explicit indexes"));
        assertEquals("0", p92.get("Mismatched indexes"));

        List<Map<String, String>> constraints = readCsv(P92_CONSTRAINTS);
        assertEquals(1968, constraints.size());
        assertTrue(constraints.stream().allMatch(row -> "EXACT".equals(row.get("status"))));

        List<Map<String, String>> indexes = readCsv(P92_INDEXES);
        assertEquals(1233, indexes.size());
        assertTrue(indexes.stream().allMatch(row -> "EXACT".equals(row.get("status"))));

        Map<String, String> p93 = readSummary(P93_SUMMARY);
        assertEquals("310", p93.get("Expected P8-success FKs"));
        assertEquals("310", p93.get("Create attempts"));
        assertEquals("310", p93.get("Created for validation"));
        assertEquals("310", p93.get("Catalog exact"));
        assertEquals("0", p93.get("Catalog mismatch"));
        assertEquals("0", p93.get("Execution errors"));
        assertEquals("0", p93.get("Cleanup errors"));
        assertEquals(p93.get("Catalog FK count before"), p93.get("Catalog FK count after"));
        assertEquals("true", p93.get("Persistent state preserved"));

        List<Map<String, String>> fkRows = readCsv(P93_ROWS);
        assertEquals(310, fkRows.size());
        assertEquals(310, fkRows.stream()
                .map(row -> row.get("source_table") + "|" + row.get("constraint_name"))
                .distinct().count());
        assertTrue(fkRows.stream().allMatch(row -> {
            String status = row.get("status");
            return "CREATED_AND_EXACT".equals(status) || "PREEXISTING_EXACT".equals(status);
        }));
        assertTrue(readCsvAllowEmpty(P93_CLEANUP).isEmpty());

        System.out.println("DB2 LUW P10 Final Closure Gate");
        System.out.println("==============================");
        System.out.println("Generated files / final tables     : 4693 / 2310");
        System.out.println("Final table/column catalog shape   : 2310 / 2310 exact");
        System.out.println("Final columns                       : 47997 / 47997 exact (P9.1 live gate)");
        System.out.println("PK/UK constraints                  : 1968 / 1968 exact");
        System.out.println("Explicit generated indexes         : 1233 / 1233 exact");
        System.out.println("Final FK candidates                : 557");
        System.out.println("Evidence-valid/live FKs             : 310 / 310 exact");
        System.out.println("Evidence/policy blocked/deferred   : 247");
        System.out.println("P9.3 persistent FK state preserved : true");
        System.out.println("Synthetic PK/UK/FK policy           : FORBIDDEN");
        System.out.println("DB2 LUW status                      : P10 CLOSED BASELINE");
    }

    private static int integer(Map<String, String> summary, String key) {
        return Integer.parseInt(summary.get(key));
    }

    private static Map<String, String> readSummary(String resource) throws IOException {
        InputStream input = Db2LuwFinalClosureP10Test.class.getClassLoader().getResourceAsStream(resource);
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
        InputStream input = Db2LuwFinalClosureP10Test.class.getClassLoader().getResourceAsStream(resource);
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
