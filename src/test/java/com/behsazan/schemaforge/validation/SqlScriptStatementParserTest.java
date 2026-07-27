package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the behavior and regression expectations of SQL Script Statement Parser.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class SqlScriptStatementParserTest {
    private final SqlScriptStatementParser parser = new SqlScriptStatementParser();

    @Test
    void shouldRemovePostgreSqlClientCommandsAndPreserveQuotedSemicolon() {
        String script = "\\set ON_ERROR_STOP on\n"
                + "CREATE TABLE test_table(id NUMERIC);\n"
                + "COMMENT ON TABLE test_table IS 'value;still literal';\n";

        List<String> statements = parser.parse(script, DatabasePlatform.POSTGRESQL);

        assertEquals(2, statements.size());
        assertFalse(statements.getFirst().contains("\\set"));
        assertEquals("COMMENT ON TABLE test_table IS 'value;still literal'", statements.get(1));
    }

    @Test
    void shouldRemoveOracleSqlPlusCommands() {
        String script = "PROMPT Header\nSET DEFINE OFF;\nWHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK;\n"
                + "CREATE TABLE TEST_TABLE(ID NUMBER);\n";

        List<String> statements = parser.parse(script, DatabasePlatform.ORACLE);

        assertEquals(List.of("CREATE TABLE TEST_TABLE(ID NUMBER)"), statements);
    }
    @Test
    void shouldRemoveSqlServerSqlCmdCommandsAndBatchSeparators() {
        String script = ":r child.sql\n"
                + "CREATE TABLE TEST_TABLE(ID INT);\n"
                + "GO\n"
                + "INSERT INTO TEST_TABLE VALUES (1);\n";

        List<String> statements = parser.parse(script, DatabasePlatform.SQLSERVER);

        assertEquals(List.of(
                "CREATE TABLE TEST_TABLE(ID INT)",
                "INSERT INTO TEST_TABLE VALUES (1)"), statements);
    }

}
