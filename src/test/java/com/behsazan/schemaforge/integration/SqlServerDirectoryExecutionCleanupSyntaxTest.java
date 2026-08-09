package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression test for SQL Server destructive cleanup syntax. */
class SqlServerDirectoryExecutionCleanupSyntaxTest {

    @Test
    void dropTableUsesSqlServerSyntaxWithoutPostgreSqlCascadeClause() {
        String sql = SqlServerDirectoryExecutionTest.dropTableSql("TSTSHMA.CUSTOMER");

        assertEquals("DROP TABLE IF EXISTS TSTSHMA.CUSTOMER", sql);
        assertFalse(sql.toUpperCase().contains("CASCADE"));
    }
}
