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

    @Test
    void dropForeignKeyUsesSqlServerAlterTableSyntax() {
        String sql = SqlServerDirectoryExecutionTest.dropForeignKeySql(
                "TSTSHMA.CHILD_TABLE", "FK_CHILD_PARENT");

        assertEquals(
                "ALTER TABLE TSTSHMA.CHILD_TABLE DROP CONSTRAINT FK_CHILD_PARENT",
                sql);
        assertFalse(sql.toUpperCase().contains("CASCADE"));
    }
}
