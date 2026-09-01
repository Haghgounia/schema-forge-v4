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
 * Evidence-only MySQL final closure gate.
 *
 * <p>The gate freezes the accepted R7.2 MySQL generation result, the current 4704-file
 * historical live replay on MySQL 8.4.11, and the retained M2 live migration pilot.
 * It does not connect to MySQL, mutate a database, reparse Legacy Word, regenerate
 * canonical JSON, or regenerate DDL.</p>
 *
 * <p>Historical execution intentionally skips cross-table foreign keys because historical
 * table versions are validated independently. MySQL FK/catalog migration behavior is retained
 * by the M2 live pilot, which exercises PK/FK/UK/CHECK/INDEX replacement and requires an empty
 * post-migration metadata diff. Evidence-blocked source mappings remain blocked; they are not
 * repaired by guessing or canonical-source mutation.</p>
 */
class MySqlFinalClosureTest {

    private static final String CORPUS = "evidence/mysql-final/r7.2-corpus-summary.txt";
    private static final String HISTORICAL = "evidence/mysql-final/mysql-r7.2-historical-live-summary.txt";
    private static final String M2 = "evidence/mysql-final/mysql-m2-live-pilot-summary.txt";

    @Test
    void closesMySqlWithCurrentCorpusHistoricalAndMigrationEvidence() throws Exception {
        Map<String, String> corpus = readSummary(CORPUS);
        assertEquals("5321", corpus.get("Snapshots discovered"));
        assertEquals("5321", corpus.get("Processed snapshots"));
        assertEquals("0", corpus.get("Snapshot failures"));
        assertEquals("0", corpus.get("Canonical errors"));

        Map<String, String> mysql = readPlatformSection(CORPUS, "mysql");
        assertEquals("4704", mysql.get("Generated"));
        assertEquals("0", mysql.get("With warnings"));
        assertEquals("0", mysql.get("With errors"));
        assertEquals("617", mysql.get("Blocked mapping"));
        assertEquals("0", mysql.get("Failed"));
        assertEquals(5321,
                integer(mysql, "Generated") + integer(mysql, "With warnings")
                        + integer(mysql, "Blocked mapping") + integer(mysql, "Failed"));

        Map<String, String> historical = readSummary(HISTORICAL);
        assertEquals("MySQL", historical.get("Database product"));
        assertEquals("8.4.11", historical.get("Database version"));
        assertEquals("TSTSHMA", historical.get("Current database"));
        assertEquals("TSTSHMA", historical.get("Expected database"));
        assertEquals("exists", historical.get("Expected db status"));
        assertEquals("4704", historical.get("Files discovered"));
        assertEquals("4704", historical.get("Files selected"));
        assertEquals("12354", historical.get("Statements executed"));
        assertEquals("12354", historical.get("Statements succeeded"));
        assertEquals("0", historical.get("Statements failed"));
        assertEquals("0", historical.get("Actionable failures"));
        assertEquals("1295", historical.get("Statements skipped"));
        assertEquals("4704", historical.get("Cleanup attempted"));
        assertEquals("4704", historical.get("Cleanup succeeded"));
        assertEquals("0", historical.get("Cleanup failed"));
        assertEquals("HISTORICAL", historical.get("Execution mode"));
        assertEquals("true", historical.get("Stop after CREATE err"));
        assertEquals("true", historical.get("Drop before CREATE"));

        Map<String, String> m2 = readSummary(M2);
        assertTrue(m2.get("Server").startsWith("MySQL 8.4.11"));
        assertEquals("SCHEMAFORGE_M2_PILOT", m2.get("Pilot database"));
        assertEquals("true", m2.get("CREATE generated"));
        assertEquals("6", m2.get("Column changes"));
        assertEquals("6", m2.get("Object changes"));
        assertEquals("14", m2.get("Statements executed"));
        assertEquals("0", m2.get("Residual changes"));
        assertEquals("true", m2.get("Data preserved"));
        assertEquals("true", m2.get("Cleanup"));

        System.out.println("MySQL Final Closure Gate");
        System.out.println("========================");
        System.out.println("Canonical snapshots                 : 5321");
        System.out.println("Accepted MySQL scripts              : 4704");
        System.out.println("Evidence-blocked mappings           : 617");
        System.out.println("Generation failures                 : 0");
        System.out.println("Current corpus live                 : 4704 / 4704 files succeeded");
        System.out.println("Historical SQL                      : 12354 / 12354 succeeded");
        System.out.println("Historical cleanup                  : 4704 / 4704 succeeded");
        System.out.println("Historical cross-table FK statements: 1295 skipped by design");
        System.out.println("M2 migration live                   : 14 statements; residual 0; data preserved");
        System.out.println("M2 structural coverage              : PK/FK/UK/CHECK/INDEX replacement");
        System.out.println("No-guess mapping policy             : PRESERVED");
        System.out.println("MySQL status                        : CLOSED BASELINE");
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(values.get(key));
    }

    private static Map<String, String> readSummary(String resource) throws IOException {
        InputStream input = MySqlFinalClosureTest.class.getClassLoader().getResourceAsStream(resource);
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
        InputStream input = MySqlFinalClosureTest.class.getClassLoader().getResourceAsStream(resource);
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
