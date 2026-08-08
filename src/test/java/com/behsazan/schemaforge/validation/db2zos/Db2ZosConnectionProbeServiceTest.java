package com.behsazan.schemaforge.validation.db2zos;

import com.behsazan.schemaforge.validation.JdbcConnectionSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Verifies failure reporting of the DB2 for z/OS JDBC connection probe.
 *
 * <p>A deliberately missing driver confirms that connection diagnostics are returned without
 * attempting schema changes or DDL execution.</p>
 */
class Db2ZosConnectionProbeServiceTest {

    @Test
    void reportsMissingDriverWithoutAttemptingDdl() {
        Db2ZosConnectionProbeResult result = new Db2ZosConnectionProbeService().probe(
                new JdbcConnectionSettings("jdbc:db2://invalid-host:446/INVALID", "user", "password"),
                "example.missing.Db2Driver");

        assertFalse(result.successful());
        assertFalse(result.catalogAccessible());
        assertTrue(result.message().contains("ClassNotFoundException"));
    }
}
