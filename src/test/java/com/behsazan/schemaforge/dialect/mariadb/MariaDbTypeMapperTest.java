package com.behsazan.schemaforge.dialect.mariadb;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MariaDbTypeMapperTest {
    private final MariaDbTypeMapper mapper = new MariaDbTypeMapper();

    @Test
    void shouldMapCoreCanonicalTypesWithoutDependingOnMysqlDialect() {
        assertEquals("VARCHAR(200)", mapper.map(DataType.varchar("VARCHAR2", 200)));
        assertEquals("DECIMAL(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
        assertEquals("DECIMAL(18)", mapper.map(DataType.numeric("DEC", 18, 0)));
        assertEquals("BIGINT", mapper.map(DataType.simple("BIGINT")));
        assertEquals("DATETIME", mapper.map(DataType.simple("DATE")));
        assertEquals("DATETIME(6)", mapper.map(DataType.numeric("TIMESTAMP", 6, null)));
        assertEquals("JSON", mapper.map(DataType.simple("JSON")));
    }

    @Test
    void shouldRejectFixedCharacterLengthsBeyondMariaDbLimitWithoutGuessingVariableStorage() {
        assertEquals("CHAR(255)", mapper.map(DataType.varchar("CHAR", 255)));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.varchar("CHAR", 256)));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.varchar("NCHAR", 500)));
    }

    @Test
    void shouldUseMariaDbDecimalLimits() {
        assertEquals("DECIMAL(65,38)", mapper.map(DataType.numeric("NUMBER", 65, 38)));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("NUMBER", 66, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("NUMBER", 65, 39)));
    }

    @Test
    void shouldLosslesslyOptimizeScaleZeroExactNumbersWhenRequested() {
        MariaDbTypeMapper optimized = new MariaDbTypeMapper(NumericMappingStrategy.OPTIMIZED);

        assertEquals("SMALLINT", optimized.map(DataType.numeric("NUMBER", 4, 0)));
        assertEquals("INT", optimized.map(DataType.numeric("NUMBER", 9, 0)));
        assertEquals("BIGINT", optimized.map(DataType.numeric("NUMBER", 18, 0)));
        assertEquals("DECIMAL(19)", optimized.map(DataType.numeric("NUMBER", 19, 0)));
        assertEquals("DECIMAL(12,2)", optimized.map(DataType.numeric("NUMBER", 12, 2)));
    }

    @Test
    void shouldKeepMariaDbNativeLobAndJsonSurfaceExplicit() {
        assertEquals("TINYTEXT", mapper.map(DataType.simple("TINYTEXT")));
        assertEquals("MEDIUMTEXT", mapper.map(DataType.simple("MEDIUMTEXT")));
        assertEquals("LONGTEXT", mapper.map(DataType.simple("LONGTEXT")));
        assertEquals("TINYBLOB", mapper.map(DataType.simple("TINYBLOB")));
        assertEquals("MEDIUMBLOB", mapper.map(DataType.simple("MEDIUMBLOB")));
        assertEquals("LONGBLOB", mapper.map(DataType.simple("LONGBLOB")));
        assertEquals("JSON", mapper.map(DataType.simple("JSON")));
    }

    @Test
    void shouldRejectMappingsThatNeedASeparateMariaDbPolicy() {
        assertEquals("DECIMAL(65,0)", mapper.map(DataType.simple("NUMBER")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("ROWID")));
    }
}
