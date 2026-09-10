package com.behsazan.schemaforge.dialect.mariadb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MariaDbExpressionMapperTest {
    private final MariaDbExpressionMapper mapper = new MariaDbExpressionMapper();

    @Test
    void shouldMapCommonOracleExpressionsToMariaDbSyntax() {
        assertEquals("CURRENT_TIMESTAMP", mapper.map("SYSDATE"));
        assertEquals("CURRENT_TIMESTAMP", mapper.map("SYSTIMESTAMP"));
        assertEquals("CURRENT_DATE", mapper.map("CURRENT_DATE()"));
        assertEquals("COALESCE(STATUS, 0)", mapper.map("NVL(STATUS, 0)"));
    }

    @Test
    void shouldTranslateOracleNextvalToAnsiMariaDbSequenceSyntax() {
        assertEquals("NEXT VALUE FOR APP.SEQ_ORDER", mapper.map("APP.SEQ_ORDER.NEXTVAL"));
        assertEquals("NEXT VALUE FOR SEQ_ORDER", mapper.map("SEQ_ORDER.NEXTVAL"));
    }
}
