package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for Oracle SQL*Plus directives in integrated deployment scripts. */
class OracleStatementSplitterTest {

    @Test
    void skipsPromptAfterCommentOnlyHeaderWithoutMergingItIntoCreateTable() {
        String script = """
                -- SchemaForge Integrated Deployment
                -- =================================
                -- PRE-TABLE
                -- =================================
                PROMPT [SCHEMA BOOTSTRAP] Oracle schema TSTSHMA is created by CREATE USER.
                -- Secure provisioning template; intentionally not executed by SchemaForge:
                -- CREATE USER TSTSHMA IDENTIFIED BY \"<SECURE_PASSWORD>\";
                -- PHASE 1 - TABLES
                CREATE TABLE TSTSHMA.PARENT_TABLE (
                    ID NUMBER(10) NOT NULL,
                    CONSTRAINT PK_PARENT_TABLE PRIMARY KEY (ID)
                );

                -- second table
                CREATE TABLE TSTSHMA.CHILD_TABLE (
                    ID NUMBER(10) NOT NULL,
                    PARENT_ID NUMBER(10),
                    CONSTRAINT PK_CHILD_TABLE PRIMARY KEY (ID)
                );

                ALTER TABLE TSTSHMA.CHILD_TABLE
                    ADD CONSTRAINT FK_CHILD_PARENT
                    FOREIGN KEY (PARENT_ID)
                    REFERENCES TSTSHMA.PARENT_TABLE(ID);
                """;

        List<OracleSqlDirectoryExecutionTest.SqlUnit> units =
                new OracleSqlDirectoryExecutionTest.OracleStatementSplitter().split(script);

        assertEquals(3, units.size());
        assertTrue(stripLeadingComments(units.get(0).sql()).startsWith("CREATE TABLE TSTSHMA.PARENT_TABLE"));
        assertTrue(stripLeadingComments(units.get(1).sql()).startsWith("CREATE TABLE TSTSHMA.CHILD_TABLE"));
        assertTrue(stripLeadingComments(units.get(2).sql()).startsWith("ALTER TABLE TSTSHMA.CHILD_TABLE"));
        assertTrue(units.stream().noneMatch(unit -> unit.sql().toUpperCase().contains("PROMPT [SCHEMA BOOTSTRAP]")));
    }

    @Test
    void doesNotSkipPromptTextInsideAQuotedSqlLiteral() {
        String script = """
                COMMENT ON TABLE TSTSHMA.T1 IS 'PROMPT is text here';
                """;

        List<OracleSqlDirectoryExecutionTest.SqlUnit> units =
                new OracleSqlDirectoryExecutionTest.OracleStatementSplitter().split(script);

        assertEquals(1, units.size());
        assertFalse(units.get(0).sql().isBlank());
        assertTrue(units.get(0).sql().contains("PROMPT is text here"));
    }

    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql;
        boolean changed;
        do {
            changed = false;
            String trimmed = value.stripLeading();
            if (trimmed.startsWith("--")) {
                int newline = trimmed.indexOf('\n');
                value = newline < 0 ? "" : trimmed.substring(newline + 1);
                changed = true;
            } else if (trimmed.startsWith("/*")) {
                int end = trimmed.indexOf("*/", 2);
                value = end < 0 ? "" : trimmed.substring(end + 2);
                changed = true;
            }
        } while (changed);
        return value.stripLeading();
    }
}
