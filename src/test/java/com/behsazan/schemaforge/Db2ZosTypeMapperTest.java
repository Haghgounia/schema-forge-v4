package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosTypeMapper;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the lossless NUMBER mapping policy for Db2 for z/OS. */
class Db2ZosTypeMapperTest {

    @Test
    void safeStrategyShouldKeepExactNumbersAsDecimal() {
        Db2ZosTypeMapper mapper = new Db2ZosTypeMapper();

        assertEquals("DECIMAL(4,0)", mapper.map(DataType.numeric("NUMBER", 4, null)));
        assertEquals("DECIMAL(18,0)", mapper.map(DataType.numeric("NUMBER", 18, 0)));
        assertEquals("DECIMAL(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
        assertEquals("DECIMAL(31,0)", mapper.map(DataType.numeric("NUMBER", 31, 0)));
    }

    @Test
    void optimizedStrategyShouldUseLosslessNativeIntegerTypes() {
        Db2ZosTypeMapper mapper = new Db2ZosTypeMapper(NumericMappingStrategy.OPTIMIZED);

        assertEquals("SMALLINT", mapper.map(DataType.numeric("NUMBER", 4, 0)));
        assertEquals("INTEGER", mapper.map(DataType.numeric("NUMBER", 9, 0)));
        assertEquals("BIGINT", mapper.map(DataType.numeric("NUMBER", 18, 0)));
        assertEquals("DECIMAL(19,0)", mapper.map(DataType.numeric("NUMBER", 19, 0)));
    }

    @Test
    void optimizedStrategyShouldNotConvertFractionalNumbersToIntegers() {
        Db2ZosTypeMapper mapper = new Db2ZosTypeMapper(NumericMappingStrategy.OPTIMIZED);

        assertEquals("DECIMAL(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
    }

    @Test
    void shouldUseNonBlockingFallbackForUnspecifiedNumberAndRejectOutOfRangeValues() {
        Db2ZosTypeMapper mapper = new Db2ZosTypeMapper();

        assertEquals("DECIMAL(31,0)", mapper.map(DataType.simple("NUMBER")));

        IllegalArgumentException excessivePrecision = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("NUMBER", 32, 0)));
        assertTrue(excessivePrecision.getMessage().contains("exceeds 31"));

        IllegalArgumentException excessiveTimestampPrecision = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("TIMESTAMP", 13, null)));
        assertTrue(excessiveTimestampPrecision.getMessage().contains("TIMESTAMP precision exceeds 12"));
    }

    @Test
    void shouldMapCommonOracleTypesToDb2ZosTypes() {
        Db2ZosTypeMapper mapper = new Db2ZosTypeMapper();

        assertEquals("VARCHAR(50) FOR MIXED DATA", mapper.map(DataType.varchar("VARCHAR2", 50)));
        assertEquals("VARGRAPHIC(50)", mapper.map(DataType.varchar("NVARCHAR2", 50)));
        assertEquals("TIMESTAMP(0)", mapper.map(DataType.simple("DATE")));
        assertEquals("DATE", mapper.map(DataType.simple("DB2_DATE")));
        assertEquals("TIMESTAMP WITH TIME ZONE", mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertEquals("TIMESTAMP(12)", mapper.map(DataType.numeric("TIMESTAMP", 12, null)));
        assertEquals("VARBINARY(100)", mapper.map(DataType.varchar("RAW", 100)));
        assertEquals("BLOB", mapper.map(DataType.simple("LONG_RAW")));
        assertEquals("DBCLOB", mapper.map(DataType.simple("NCLOB")));
        assertEquals("CLOB", mapper.map(DataType.simple("LONGTEXT")));
        assertEquals("BLOB", mapper.map(DataType.simple("LONGBLOB")));
        assertEquals("SMALLINT", mapper.map(DataType.simple("BOOLEAN")));
        assertEquals("ROWID", mapper.map(DataType.simple("DB2_ROWID")));
    }
}
