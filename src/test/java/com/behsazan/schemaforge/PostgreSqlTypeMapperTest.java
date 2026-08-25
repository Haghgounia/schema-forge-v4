package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlTypeMapper;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the behavior and regression expectations of Postgre SQL Type Mapper.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
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
        assertEquals("TEXT", mapper.map(DataType.simple("LONGTEXT")));
        assertEquals("BYTEA", mapper.map(DataType.simple("LONGBLOB")));
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
        assertEquals("TIMESTAMP(4)", mapper.map(DataType.numeric("TIMESTAMP", 4, null)));
        assertEquals("TIMESTAMP(6)", mapper.map(DataType.numeric("TIMESTAMP", 6, null)));
        assertEquals("TIMESTAMP(6)", mapper.map(DataType.numeric("TIMESTAMP", 10, null)));
        assertEquals("TIMESTAMP(4) WITH TIME ZONE",
                mapper.map(DataType.numeric("TIMESTAMP_WITH_TIME_ZONE", 4, null)));
        assertEquals("TIMESTAMP(6) WITH TIME ZONE",
                mapper.map(DataType.numeric("TIMESTAMP_WITH_TIME_ZONE", 10, null)));
    }
    @Test
    void shouldOptimizeScaleZeroNumbersWhenExplicitlyEnabled() {
        PostgreSqlTypeMapper optimized = new PostgreSqlTypeMapper(NumericMappingStrategy.OPTIMIZED);

        assertEquals("SMALLINT", optimized.map(DataType.numeric("NUMBER", 4, 0)));
        assertEquals("INTEGER", optimized.map(DataType.numeric("NUMBER", 9, 0)));
        assertEquals("BIGINT", optimized.map(DataType.numeric("NUMBER", 18, 0)));
        assertEquals("NUMERIC(19,0)", optimized.map(DataType.numeric("NUMBER", 19, 0)));
        assertEquals("NUMERIC(18,2)", optimized.map(DataType.numeric("NUMBER", 18, 2)));
    }

}
