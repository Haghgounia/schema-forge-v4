package com.behsazan.schemaforge.dialect.mysql;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlTypeMapperTest {
    private final MySqlTypeMapper mapper = new MySqlTypeMapper();

    @Test
    void shouldMapEvidenceBackedLogicalTypesWithoutGuessing() {
        assertEquals("VARCHAR(200)", mapper.map(DataType.varchar("VARCHAR2", 200)));
        assertEquals("DECIMAL(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
        assertEquals("DECIMAL(18)", mapper.map(DataType.numeric("DEC", 18, 0)));
        assertEquals("BIGINT", mapper.map(DataType.simple("BIGINT")));
        assertEquals("DATETIME(6)", mapper.map(DataType.numeric("TIMESTAMP", 6, null)));
        assertEquals("JSON", mapper.map(DataType.simple("JSON")));
    }


    @Test
    void shouldLosslesslyOptimizeScaleZeroExactNumbersWhenRequested() {
        MySqlTypeMapper optimized = new MySqlTypeMapper(NumericMappingStrategy.OPTIMIZED);

        assertEquals("SMALLINT", optimized.map(DataType.numeric("NUMBER", 4, 0)));
        assertEquals("INT", optimized.map(DataType.numeric("NUMBER", 9, 0)));
        assertEquals("BIGINT", optimized.map(DataType.numeric("NUMBER", 18, 0)));
        assertEquals("DECIMAL(19)", optimized.map(DataType.numeric("NUMBER", 19, 0)));
        assertEquals("DECIMAL(12,2)", optimized.map(DataType.numeric("NUMBER", 12, 2)));
    }

    @Test
    void shouldRoundTripNativeMysqlLobTypes() {
        assertEquals("TINYTEXT", mapper.map(DataType.simple("TINYTEXT")));
        assertEquals("MEDIUMTEXT", mapper.map(DataType.simple("MEDIUMTEXT")));
        assertEquals("LONGTEXT", mapper.map(DataType.simple("LONGTEXT")));
        assertEquals("TINYBLOB", mapper.map(DataType.simple("TINYBLOB")));
        assertEquals("MEDIUMBLOB", mapper.map(DataType.simple("MEDIUMBLOB")));
        assertEquals("LONGBLOB", mapper.map(DataType.simple("LONGBLOB")));
    }

    @Test
    void shouldUseNonBlockingFallbackForUnspecifiedNumberAndRejectUnsupportedMappings() {
        assertEquals("DECIMAL(65,0)", mapper.map(DataType.simple("NUMBER")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("NUMBER", 66, 0)));
    }
}
