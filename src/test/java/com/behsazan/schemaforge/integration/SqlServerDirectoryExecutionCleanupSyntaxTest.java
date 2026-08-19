package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;

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
    @Test
    void quotesCatalogIdentifiersForSafeIncomingForeignKeyCleanup() {
        assertEquals("[TSTSHMA]", SqlServerDirectoryExecutionTest.quoteIdentifier("TSTSHMA"));
        assertEquals("[A]]B]", SqlServerDirectoryExecutionTest.quoteIdentifier("A]B"));
    }

    @Test
    void sparseFileNumberFilterPreservesOriginalDirectorySequence() {
        assertEquals(
                List.of(1384, 1638, 3346),
                SqlServerDirectoryExecutionTest.selectedFileNumbers(
                        3823, 1, Set.of(3346, 1384, 1638)));
    }

}
