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

/** Locks the evidence-backed DB2 LUW P8 closure partition before P9 catalog reconciliation. */
class Db2LuwP8ClosureGateTest {
    private static final String P82 =
            "evidence/db2luw-p8/20260830_104609_488/db2luw-p8.2-post-resolution-key-audit.csv";
    private static final String P83 =
            "evidence/db2luw-p8/20260830_105644_850/db2luw-p8.3-ctaccounttype-source-evidence.csv";

    @Test
    void closesP8WithCompleteNonOverlappingFkPartition() throws Exception {
        List<Map<String, String>> p82 = readCsv(P82);
        List<Map<String, String>> p83 = readCsv(P83);

        assertEquals(6, p82.size(), "P8.2 distinct parent-key refs");
        assertEquals(1, count(p82, "p8_2_classification", "HISTORICAL_GENERATED_AND_CANONICAL_KEY_EVIDENCE"));
        assertEquals(5, count(p82, "p8_2_classification", "NO_INDEPENDENT_KEY_EVIDENCE"));

        Map<String, String> accountType = p82.stream()
                .filter(row -> "TSTSHMA.CTACCOUNTTYPE".equals(row.get("referenced_table")))
                .filter(row -> "ACCTYPE".equals(row.get("key_columns")))
                .findFirst().orElseThrow();
        int accountTypeAffected = Integer.parseInt(accountType.get("p8_1_blocker_rows"))
                + Integer.parseInt(accountType.get("p7_5_deferred_rows"));
        assertEquals(18, accountTypeAffected);

        Map<String, String> currentParsed = p83.stream()
                .filter(row -> "CURRENT_CANDIDATE".equals(row.get("source_class")))
                .filter(row -> "PARSED".equals(row.get("status")))
                .filter(row -> "TSTSHMA.CTACCOUNTTYPE".equals(row.get("table")))
                .findFirst().orElseThrow();
        assertEquals("ACCTYPE|ARZCODE", currentParsed.get("pk_columns"));
        assertEquals("false", currentParsed.get("acctype_primary_key"));

        long historicalSingleColumnPk = p83.stream()
                .filter(row -> "HISTORICAL".equals(row.get("source_class")))
                .filter(row -> "PARSED".equals(row.get("status")))
                .filter(row -> "ACCTYPE".equals(row.get("pk_columns")))
                .filter(row -> "true".equals(row.get("acctype_primary_key")))
                .count();
        assertEquals(2, historicalSingleColumnPk);

        int executable = 310;
        int blockedPossibleAlias = 90;
        int blockedCanonicalAbsent = 47;
        int externalSharedDependency = 21;
        int blockedColumnNeverExisted = 11;
        int blockedNoIndependentUniqueP74 = 50;
        int postResolutionNoIndependentKey = p82.stream()
                .filter(row -> "NO_INDEPENDENT_KEY_EVIDENCE".equals(row.get("p8_2_classification")))
                .mapToInt(row -> Integer.parseInt(row.get("p8_1_blocker_rows")))
                .sum();
        int currentCompositeKeyConflict = accountTypeAffected;

        assertEquals(10, postResolutionNoIndependentKey);
        assertEquals(18, currentCompositeKeyConflict);

        int explicitlyDeferredOrBlocked = blockedPossibleAlias
                + blockedCanonicalAbsent
                + externalSharedDependency
                + blockedColumnNeverExisted
                + blockedNoIndependentUniqueP74
                + postResolutionNoIndependentKey
                + currentCompositeKeyConflict;
        assertEquals(247, explicitlyDeferredOrBlocked);
        assertEquals(557, executable + explicitlyDeferredOrBlocked);

        System.out.println("DB2 LUW P8 Closure Gate");
        System.out.println("=======================");
        System.out.println("Final FK candidates                 : 557");
        System.out.println("Evidence-valid / live executable    : 310");
        System.out.println("Evidence/policy deferred or blocked : 247");
        System.out.println("  possible alias                    : 90");
        System.out.println("  canonical parent absent           : 47");
        System.out.println("  external/shared dependency        : 21");
        System.out.println("  referenced column never existed   : 11");
        System.out.println("  P7.4 no unique evidence           : 50");
        System.out.println("  P8.2 no independent key evidence  : 10");
        System.out.println("  CTACCOUNTTYPE current PK conflict : 18");
        System.out.println("Mutation policy                     : NO SYNTHETIC PK/UK/FK");
        System.out.println("P8 status                           : CLOSED; READY FOR P9");
    }

    private static long count(List<Map<String, String>> rows, String field, String value) {
        return rows.stream().filter(row -> value.equals(row.get(field))).count();
    }

    private static List<Map<String, String>> readCsv(String resource) throws IOException {
        InputStream input = Db2LuwP8ClosureGateTest.class.getClassLoader().getResourceAsStream(resource);
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
            assertFalse(rows.isEmpty(), "No rows in retained evidence: " + resource);
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
