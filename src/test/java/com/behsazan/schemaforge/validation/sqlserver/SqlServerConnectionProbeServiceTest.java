package com.behsazan.schemaforge.validation.sqlserver;

import com.behsazan.schemaforge.validation.JdbcConnectionSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Verifies failure reporting of the SQL Server JDBC connection probe.
 *
 * <p>A deliberately missing driver confirms that diagnostics are returned without attempting
 * database DDL or metadata operations.</p>
 */
class SqlServerConnectionProbeServiceTest {

    @Test
    void reportsMissingDriverWithoutAttemptingDdl() {
        SqlServerConnectionProbeResult result = new SqlServerConnectionProbeService().probe(
                new JdbcConnectionSettings("jdbc:sqlserver://invalid-host:1433", "user", "password"),
                "example.missing.SqlServerDriver");

        assertFalse(result.successful());
        assertFalse(result.catalogAccessible());
        assertTrue(result.message().contains("ClassNotFoundException"));
    }
}
