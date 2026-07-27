package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.sqlserver.SqlServerExpressionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Protects Oracle-oriented default and generated-expression conversion for SQL Server. */
class SqlServerExpressionMapperTest {
    private final SqlServerExpressionMapper mapper = new SqlServerExpressionMapper();

    @Test
    void shouldMapDatesNullHandlingAndGuidExpressions() {
        assertEquals("SYSDATETIME()", mapper.map("SYSDATE"));
        assertEquals("SYSDATETIMEOFFSET()", mapper.map("SYSTIMESTAMP"));
        assertEquals("COALESCE(STATUS, 0)", mapper.map("NVL(STATUS, 0)"));
        assertEquals("NEWID()", mapper.map("SYS_GUID()"));
    }

    @Test
    void shouldMapOracleSequenceNextval() {
        assertEquals("NEXT VALUE FOR DPS.SEQ_PROVINCES",
                mapper.map("DPS.SEQ_PROVINCES.NEXTVAL"));
    }
}
