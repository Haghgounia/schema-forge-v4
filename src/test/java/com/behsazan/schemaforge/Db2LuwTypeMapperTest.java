package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwTypeMapper;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R7.10 P1 lossless datatype contract for Db2 LUW. */
class Db2LuwTypeMapperTest {

    @Test
    void safeStrategyKeepsExactNumbersAsDecimal() {
        Db2LuwTypeMapper mapper = new Db2LuwTypeMapper();

        assertEquals("DECIMAL(4,0)", mapper.map(DataType.numeric("NUMBER", 4, null)));
        assertEquals("DECIMAL(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
        assertEquals("DECIMAL(31,0)", mapper.map(DataType.numeric("DECIMAL", 31, 0)));
    }

    @Test
    void optimizedStrategyUsesLosslessNativeIntegerTypes() {
        Db2LuwTypeMapper mapper = new Db2LuwTypeMapper(NumericMappingStrategy.OPTIMIZED);

        assertEquals("SMALLINT", mapper.map(DataType.numeric("NUMBER", 4, 0)));
        assertEquals("INTEGER", mapper.map(DataType.numeric("NUMBER", 9, 0)));
        assertEquals("BIGINT", mapper.map(DataType.numeric("NUMBER", 18, 0)));
        assertEquals("DECIMAL(19,0)", mapper.map(DataType.numeric("NUMBER", 19, 0)));
    }

    @Test
    void mapsCommonCanonicalAndOracleTypesWithoutZosSpecificStringClauses() {
        Db2LuwTypeMapper mapper = new Db2LuwTypeMapper();

        assertEquals("VARCHAR(50)", mapper.map(DataType.varchar("VARCHAR2", 50)));
        assertEquals("VARGRAPHIC(50)", mapper.map(DataType.varchar("NVARCHAR2", 50)));
        assertEquals("TIMESTAMP(0)", mapper.map(DataType.simple("DATE")));
        assertEquals("DATE", mapper.map(DataType.simple("DB2_DATE")));
        assertEquals("TIMESTAMP(12)", mapper.map(DataType.numeric("TIMESTAMP", 12, null)));
        assertEquals("VARBINARY(100)", mapper.map(DataType.varchar("RAW", 100)));
        assertEquals("BLOB", mapper.map(DataType.simple("LONG_RAW")));
        assertEquals("DBCLOB", mapper.map(DataType.simple("NCLOB")));
        assertEquals("CLOB", mapper.map(DataType.simple("LONGTEXT")));
        assertEquals("BOOLEAN", mapper.map(DataType.simple("BOOLEAN")));
    }

    @Test
    void rejectsMappingsThatWouldInventOrDiscardSemantics() {
        Db2LuwTypeMapper mapper = new Db2LuwTypeMapper();

        IllegalArgumentException unbounded = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("NUMBER")));
        assertTrue(unbounded.getMessage().contains("explicit precision"));

        IllegalArgumentException excessivePrecision = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("NUMBER", 32, 0)));
        assertTrue(excessivePrecision.getMessage().contains("exceeds 31"));

        IllegalArgumentException excessiveTimestamp = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("TIMESTAMP", 13, null)));
        assertTrue(excessiveTimestamp.getMessage().contains("precision exceeds 12"));

        IllegalArgumentException timezone = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertTrue(timezone.getMessage().contains("lossless TIMESTAMP WITH TIME ZONE"));
    }
}
