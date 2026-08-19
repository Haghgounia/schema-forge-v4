package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression tests for destructive replay cleanup SQL used by live directory runners. */
class DirectoryExecutionCleanupSyntaxTest {

    @Test
    void oracleCleanupUsesTableCascadePurgeAndExplicitSequenceDrop() {
        assertEquals(
                "DROP TABLE TSTSHMA.CUSTOMER CASCADE CONSTRAINTS PURGE",
                OracleSqlDirectoryExecutionTest.dropTableSql("TSTSHMA.CUSTOMER"));
        assertEquals(
                "DROP SEQUENCE TSTSHMA.SEQ_CUSTOMER",
                OracleSqlDirectoryExecutionTest.dropSequenceSql("TSTSHMA.SEQ_CUSTOMER"));
    }

    @Test
    void postgreSqlCleanupUsesIfExistsAndCascade() {
        assertEquals(
                "DROP TABLE IF EXISTS tstshma.customer CASCADE",
                PostgreSqlDirectoryExecutionTest.dropTableSql("tstshma.customer"));
        assertEquals(
                "DROP SEQUENCE IF EXISTS tstshma.seq_customer CASCADE",
                PostgreSqlDirectoryExecutionTest.dropSequenceSql("tstshma.seq_customer"));
    }

    @Test
    void sqlServerCleanupUsesNativeIfExistsWithoutCascade() {
        String tableSql = SqlServerDirectoryExecutionTest.dropTableSql("TSTSHMA.CUSTOMER");
        String sequenceSql = SqlServerDirectoryExecutionTest.dropSequenceSql("TSTSHMA.SEQ_CUSTOMER");

        assertEquals("DROP TABLE IF EXISTS TSTSHMA.CUSTOMER", tableSql);
        assertEquals("DROP SEQUENCE IF EXISTS TSTSHMA.SEQ_CUSTOMER", sequenceSql);
        assertFalse(tableSql.toUpperCase().contains("CASCADE"));
        assertFalse(sequenceSql.toUpperCase().contains("CASCADE"));
    }
}
