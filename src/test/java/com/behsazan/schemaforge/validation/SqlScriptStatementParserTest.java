package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
