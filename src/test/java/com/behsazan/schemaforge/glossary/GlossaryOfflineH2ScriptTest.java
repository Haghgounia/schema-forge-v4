package com.behsazan.schemaforge.glossary;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the standalone Windows H2 bootstrap contract and its UTF-8 SQL seed. */
class GlossaryOfflineH2ScriptTest {
    @TempDir Path temp;

    @Test
    void bootstrapBatchMustUseOnlySupportedRunScriptCliFlags() throws Exception {
        String batch = Files.readString(Path.of("distribution/bin/bootstrap-glossary-h2.cmd"),
                StandardCharsets.UTF_8);
        String command = batch.lines().filter(s -> s.stripLeading().startsWith("java -cp")
                        && s.contains("org.h2.tools.RunScript"))
                .findFirst().orElseThrow();
        assertTrue(command.contains("-url \"%SCHEMAFORGE_H2_URL%\""));
        assertTrue(command.contains("-script \"%SCHEMAFORGE_GLOSSARY_SQL%\""));
        assertFalse(command.contains("-charset"), "RunScript does not support -charset");
        assertFalse(command.contains("-options"), "The UTF-8 script needs no SQL RUNSCRIPT options");
    }

    @Test
    void offlineSqlImportsOnlyAtomicTermsInUtf8AndCanBeRerun() throws Exception {
        Path sql = Path.of("distribution/glossary/bootstrap-atomic-h2.sql").toAbsolutePath();
        assertTrue(Files.isRegularFile(sql));
        String url = "jdbc:h2:file:" + temp.resolve("glossary-seed").toAbsolutePath()
                .toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        // Use the EXACT supported command-line options as the Windows .cmd.
        for (int pass = 0; pass < 2; pass++) {
            RunScript.main("-url", url, "-user", "sa", "-password", "",
                    "-script", sql.toString());
            try (var connection = DriverManager.getConnection(url, "sa", "");
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM glossary_term WHERE source='ECOBANK_ATOMIC_XLSX'")) {
                    assertTrue(result.next());
                    assertEquals(804, result.getInt(1));
                }
                try (var result = statement.executeQuery(
                        "SELECT kind, COUNT(*) FROM glossary_term GROUP BY kind ORDER BY kind")) {
                    assertTrue(result.next());
                    assertEquals("ATOMIC", result.getString(1));
                    assertEquals(800, result.getInt(2));
                    assertTrue(result.next());
                    assertEquals("PHRASE", result.getString(1));
                    assertEquals(4, result.getInt(2));
                    assertFalse(result.next());
                }
                try (var result = statement.executeQuery(
                        "SELECT persian, status FROM glossary_term WHERE kind='ATOMIC' AND term_key='ACCOUNT'")) {
                    assertTrue(result.next());
                    assertEquals("حساب", result.getString(1), "Seed must be read as UTF-8");
                    assertEquals("APPROVED", result.getString(2));
                }
                if (pass == 0) statement.executeUpdate(
                        "UPDATE glossary_term SET status='APPROVED' WHERE kind='ATOMIC' AND term_key='ACCOUNT'");
            }
        }
        assertTrue(Files.isRegularFile(Path.of(temp.resolve("glossary-seed") + ".mv.db")));
    }
}
