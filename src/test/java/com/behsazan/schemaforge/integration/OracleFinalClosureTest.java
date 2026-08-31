package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence-only Oracle final closure gate.
 *
 * <p>The gate freezes already-retained Oracle V4 evidence. It does not connect to Oracle,
 * mutate a database, reparse Legacy Word, regenerate canonical JSON, or regenerate DDL.
 * Structural/dependency FK blockers remain accepted evidence findings and are not repaired
 * by synthetic PK/UK/FK creation.</p>
 */
class OracleFinalClosureTest {

    private static final String CORPUS = "evidence/oracle-final/r7.2-corpus-summary.txt";
    private static final String HISTORICAL = "evidence/oracle-final/oracle-historical-baseline-summary.txt";
    private static final String FK = "evidence/oracle-final/oracle-fk-r2-summary.txt";
    private static final String M2 = "evidence/oracle-final/oracle-m2-live-pilot-summary.txt";

    @Test
    void closesOracleWithRetainedCorpusHistoricalFkAndMigrationEvidence() throws Exception {
        Map<String, String> corpus = readSummary(CORPUS);
        assertEquals("5321", corpus.get("Snapshots discovered"));
        assertEquals("5321", corpus.get("Processed snapshots"));
        assertEquals("0", corpus.get("Snapshot failures"));
        assertEquals("0", corpus.get("Canonical errors"));

        Map<String, String> oracle = readPlatformSection(CORPUS, "oracle");
        assertEquals("5294", oracle.get("Generated"));
        assertEquals("2", oracle.get("With warnings"));
        assertEquals("0", oracle.get("With errors"));
        assertEquals("25", oracle.get("Blocked mapping"));
        assertEquals("0", oracle.get("Failed"));
        assertEquals(5321,
                integer(oracle, "Generated") + integer(oracle, "With warnings")
                        + integer(oracle, "Blocked mapping") + integer(oracle, "Failed"));

        Map<String, String> historical = readSummary(HISTORICAL);
        assertEquals("4766", historical.get("Main historical files"));
        assertEquals("115804", historical.get("Main statements executed"));
        assertEquals("115804", historical.get("Main statements succeeded"));
        assertEquals("0", historical.get("Main statements failed"));
        assertEquals("4766", historical.get("Main cleanup succeeded"));
        assertEquals("4", historical.get("Collision coverage files"));
        assertEquals("86", historical.get("Collision statements executed"));
        assertEquals("86", historical.get("Collision statements succeeded"));
        assertEquals("0", historical.get("Collision statements failed"));
        assertEquals("4768", historical.get("Historical definitions covered"));

        Map<String, String> fk = readSummary(FK);
        assertEquals("5296", fk.get("Final replay tables"));
        assertEquals("242", fk.get("FK attempted"));
        assertEquals("242", fk.get("FK succeeded"));
        assertEquals("0", fk.get("FK failed"));
        assertEquals("142", fk.get("Structural blocked"));
        assertEquals("169", fk.get("Dependency skipped"));
        assertEquals("0", fk.get("Cleanup failed"));
        assertEquals("FORBIDDEN", fk.get("Synthetic key policy"));
        assertEquals(553,
                integer(fk, "FK succeeded") + integer(fk, "Structural blocked")
                        + integer(fk, "Dependency skipped"));

        Map<String, String> m2 = readSummary(M2);
        assertTrue(m2.get("Server").startsWith("Oracle "));
        assertEquals("true", m2.get("CREATE generated"));
        assertEquals("6", m2.get("Column changes"));
        assertEquals("6", m2.get("Object changes"));
        assertEquals("16", m2.get("Statements executed"));
        assertEquals("0", m2.get("Residual changes"));
        assertEquals("true", m2.get("Data preserved"));
        assertEquals("true", m2.get("Cleanup"));

        System.out.println("Oracle Final Closure Gate");
        System.out.println("=========================");
        System.out.println("Canonical snapshots                 : 5321");
        System.out.println("Accepted Oracle scripts             : 5296 (5294 clean + 2 warning-bearing)");
        System.out.println("Evidence-blocked mappings           : 25");
        System.out.println("Generation failures                 : 0");
        System.out.println("Historical retained live baseline   : 4768 / 4768 definitions covered");
        System.out.println("Historical main SQL                 : 115804 / 115804 succeeded");
        System.out.println("Final-state FK live                 : 242 / 242 succeeded");
        System.out.println("FK structural/dependency findings   : 142 / 169 (reported; no guessing)");
        System.out.println("M2 migration live                   : 16 statements; residual 0; data preserved");
        System.out.println("Synthetic PK/UK/FK policy           : FORBIDDEN");
        System.out.println("Oracle status                       : CLOSED BASELINE");
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(values.get(key));
    }

    private static Map<String, String> readSummary(String resource) throws IOException {
        InputStream input = OracleFinalClosureTest.class.getClassLoader().getResourceAsStream(resource);
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

    private static Map<String, String> readPlatformSection(String resource, String platform) throws IOException {
        InputStream input = OracleFinalClosureTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(input, "Missing retained evidence resource: " + resource);
        Map<String, String> values = new LinkedHashMap<>();
        boolean inSection = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!inSection) {
                    if (trimmed.equalsIgnoreCase(platform)) inSection = true;
                    continue;
                }
                if (trimmed.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                values.put(key, value);
            }
        }
        assertFalse(values.isEmpty(), "Missing platform section " + platform + " in " + resource);
        return values;
    }
}
