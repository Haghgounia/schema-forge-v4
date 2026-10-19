package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

/** Ensures the runtime driver contains the upstream fix for H2 #4247. */
class GlossaryH2VersionContractTest {
    @TempDir Path temp;

    @Test
    void runtimeH2IsPinnedToPatchedEngineAndSupportsFileReopen() throws Exception {
        Path database = temp.resolve("glossary-version").toAbsolutePath();
        var ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:file:" + database.toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        ds.setUsername("sa");
        ds.setPassword("");
        String engineVersion;
        try (Connection c = ds.getConnection()) {
            engineVersion = c.getMetaData().getDatabaseProductVersion();
            System.out.println("GLOSSARY_H2_VERSION=" + engineVersion);
            assertTrue(engineVersion.startsWith("2.4.240"),
                    "Expected H2 2.4.240 containing the #4247 compaction fix");
            try (var statement = c.createStatement()) {
                statement.execute("CREATE TABLE T_HF8 (ID INTEGER PRIMARY KEY, VAL VARCHAR(40))");
                statement.executeUpdate("INSERT INTO T_HF8 VALUES (1, 'persisted')");
            }
        }
        try (Connection reopened = ds.getConnection();
             var statement = reopened.createStatement();
             var rows = statement.executeQuery("SELECT VAL FROM T_HF8 WHERE ID=1")) {
            assertTrue(rows.next(), "H2 file must retain committed rows across connection close");
            assertEquals("persisted", rows.getString(1));
            assertFalse(rows.next());
        }
        assertTrue(Files.isRegularFile(Path.of(database + ".mv.db")));
    }
}
