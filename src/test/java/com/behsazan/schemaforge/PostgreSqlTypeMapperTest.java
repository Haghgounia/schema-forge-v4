package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlTypeMapper;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgreSqlTypeMapperTest {
    private final PostgreSqlTypeMapper mapper = new PostgreSqlTypeMapper();

    @Test
    void shouldMapOracleCharacterNumericAndLobTypes() {
        assertEquals("VARCHAR(120)", mapper.map(DataType.varchar("VARCHAR2", 120)));
        assertEquals("NUMERIC(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
        assertEquals("TEXT", mapper.map(DataType.simple("CLOB")));
        assertEquals("BYTEA", mapper.map(DataType.simple("RAW")));
        assertEquals("BYTEA", mapper.map(DataType.simple("LONG_RAW")));
        assertEquals("VARCHAR", mapper.map(DataType.simple("UROWID")));
    }

    @Test
    void shouldMapTemporalAndDocumentTypes() {
        assertEquals("TIMESTAMP", mapper.map(DataType.simple("DATE")));
        assertEquals("XML", mapper.map(DataType.simple("XMLTYPE")));
        assertEquals("JSONB", mapper.map(DataType.simple("JSON")));
        assertEquals("TIMESTAMP WITH TIME ZONE",
                mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertEquals("TIMESTAMP WITH TIME ZONE",
                mapper.map(DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")));
    }
}
