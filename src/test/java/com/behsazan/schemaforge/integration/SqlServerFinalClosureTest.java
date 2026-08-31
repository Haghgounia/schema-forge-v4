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
 * Evidence-only SQL Server final closure gate.
 *
 * <p>The gate freezes the current R7.2 generated-corpus live replay together with the retained
 * integrated FULL FK pilot and M2 migration evidence. It does not connect to SQL Server, mutate a
 * database, reparse Legacy Word, regenerate canonical JSON, or regenerate DDL.</p>
 *
 * <p>Historical execution intentionally skips cross-table FK statements because historical table
 * versions are validated independently. FK runtime behavior is retained separately by the FULL
 * integrated pilot. SchemaForge does not synthesize PK/UK/FK objects to hide source-model gaps.</p>
 */
class SqlServerFinalClosureTest {

    private static final String CORPUS = "evidence/sqlserver-final/r7.2-corpus-summary.txt";
    private static final String HISTORICAL = "evidence/sqlserver-final/sqlserver-r7.2-historical-live-summary.txt";
    private static final String INTEGRATED = "evidence/sqlserver-final/sqlserver-integrated-full-summary.txt";
    private static final String M2 = "evidence/sqlserver-final/sqlserver-m2-live-pilot-summary.txt";

    @Test
    void closesSqlServerWithCurrentCorpusHistoricalFullFkAndMigrationEvidence() throws Exception {
        Map<String, String> corpus = readSummary(CORPUS);
        assertEquals("5321", corpus.get("Snapshots discovered"));
        assertEquals("5321", corpus.get("Processed snapshots"));
        assertEquals("0", corpus.get("Snapshot failures"));
        assertEquals("0", corpus.get("Canonical errors"));

        Map<String, String> sqlserver = readPlatformSection(CORPUS, "sqlserver");
        assertEquals("4703", sqlserver.get("Generated"));
        assertEquals("0", sqlserver.get("With warnings"));
        assertEquals("0", sqlserver.get("With errors"));
        assertEquals("618", sqlserver.get("Blocked mapping"));
        assertEquals("0", sqlserver.get("Failed"));
        assertEquals(5321,
                integer(sqlserver, "Generated") + integer(sqlserver, "With warnings")
                        + integer(sqlserver, "Blocked mapping") + integer(sqlserver, "Failed"));

        Map<String, String> historical = readSummary(HISTORICAL);
        assertEquals("Microsoft SQL Server", historical.get("Database product"));
        assertEquals("16.00.4265", historical.get("Database version"));
        assertEquals("TSTSHMA", historical.get("Expected schema"));
        assertEquals("4703", historical.get("Files discovered"));
        assertEquals("4703", historical.get("Files selected"));
        assertEquals("128865", historical.get("Statements executed"));
        assertEquals("128865", historical.get("Statements succeeded"));
        assertEquals("0", historical.get("Statements failed"));
        assertEquals("0", historical.get("Actionable failures"));
        assertEquals("0", historical.get("Ignored failures"));
        assertEquals("4703", historical.get("Cleanup attempted"));
        assertEquals("4703", historical.get("Cleanup succeeded"));
        assertEquals("0", historical.get("Cleanup failed"));
        assertEquals("HISTORICAL", historical.get("Execution mode"));
        assertEquals("true", historical.get("Drop before CREATE"));

        Map<String, String> integrated = readSummary(INTEGRATED);
        assertEquals("15", integrated.get("Pilot tables"));
        assertEquals("13", integrated.get("Resolved physical FKs"));
        assertEquals("0", integrated.get("FK blockers"));
        assertEquals("274", integrated.get("Statements executed"));
        assertEquals("0", integrated.get("Statements failed"));
        assertEquals("13", integrated.get("Cleanup existing FKs removed"));
        assertEquals("FULL", integrated.get("Execution mode"));

        Map<String, String> m2 = readSummary(M2);
        assertTrue(m2.get("Server").startsWith("Microsoft SQL Server "));
        assertEquals("true", m2.get("CREATE generated"));
        assertEquals("6", m2.get("Column changes"));
        assertEquals("6", m2.get("Object changes"));
        assertEquals("20", m2.get("Statements executed"));
        assertEquals("0", m2.get("Residual changes"));
        assertEquals("true", m2.get("Data preserved"));
        assertEquals("true", m2.get("Cleanup"));

        System.out.println("SQL Server Final Closure Gate");
        System.out.println("=============================");
        System.out.println("Canonical snapshots                 : 5321");
        System.out.println("Accepted SQL Server scripts         : 4703");
        System.out.println("Evidence-blocked mappings           : 618");
        System.out.println("Generation failures                 : 0");
        System.out.println("Current corpus live                 : 4703 / 4703 files succeeded");
        System.out.println("Historical SQL                      : 128865 / 128865 succeeded");
        System.out.println("Historical cleanup                  : 4703 / 4703 succeeded");
        System.out.println("Integrated FULL FK pilot            : 274 statements / 13 FK / 0 failures");
        System.out.println("M2 migration live                   : 20 statements; residual 0; data preserved");
        System.out.println("Synthetic PK/UK/FK policy           : FORBIDDEN");
        System.out.println("SQL Server status                   : CLOSED BASELINE");
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(values.get(key));
    }

    private static Map<String, String> readSummary(String resource) throws IOException {
        InputStream input = SqlServerFinalClosureTest.class.getClassLoader().getResourceAsStream(resource);
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
        InputStream input = SqlServerFinalClosureTest.class.getClassLoader().getResourceAsStream(resource);
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
