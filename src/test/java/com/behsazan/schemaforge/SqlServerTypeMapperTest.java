package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerTypeMapper;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Protects SQL Server datatype mapping in SAFE and OPTIMIZED modes. */
class SqlServerTypeMapperTest {

    @Test
    void shouldMapExactNumbersSafelyByDefault() {
        SqlServerTypeMapper mapper = new SqlServerTypeMapper();

        assertEquals("DECIMAL(2,0)", mapper.map(DataType.numeric("NUMBER", 2, null)));
        assertEquals("DECIMAL(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
        assertEquals("DECIMAL(38,0)", mapper.map(DataType.simple("NUMBER")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("NUMBER", 39, 0)));
    }

    @Test
    void shouldOptimizeScaleZeroNumbersWhenExplicitlyEnabled() {
        SqlServerTypeMapper mapper = new SqlServerTypeMapper(NumericMappingStrategy.OPTIMIZED);

        assertEquals("SMALLINT", mapper.map(DataType.numeric("NUMBER", 4, 0)));
        assertEquals("INT", mapper.map(DataType.numeric("NUMBER", 9, 0)));
        assertEquals("BIGINT", mapper.map(DataType.numeric("NUMBER", 18, 0)));
        assertEquals("DECIMAL(19,0)", mapper.map(DataType.numeric("NUMBER", 19, 0)));
        assertEquals("DECIMAL(18,2)", mapper.map(DataType.numeric("NUMBER", 18, 2)));
    }

    @Test
    void shouldMapCharacterLobBinaryAndDocumentTypes() {
        SqlServerTypeMapper mapper = new SqlServerTypeMapper();

        assertEquals("VARCHAR(50)", mapper.map(DataType.varchar("VARCHAR2", 50)));
        assertEquals("NVARCHAR(50)", mapper.map(DataType.varchar("NVARCHAR2", 50)));
        assertEquals("VARCHAR(MAX)", mapper.map(DataType.varchar("VARCHAR2", 9000)));
        assertEquals("VARBINARY(100)", mapper.map(DataType.varchar("RAW", 100)));
        assertEquals("VARBINARY(MAX)", mapper.map(DataType.simple("BLOB")));
        assertEquals("VARCHAR(MAX)", mapper.map(DataType.simple("CLOB")));
        assertEquals("NVARCHAR(MAX)", mapper.map(DataType.simple("NCLOB")));
        assertEquals("VARCHAR(MAX)", mapper.map(DataType.simple("VARCHAR_MAX")));
        assertEquals("NVARCHAR(MAX)", mapper.map(DataType.simple("NVARCHAR_MAX")));
        assertEquals("VARBINARY(MAX)", mapper.map(DataType.simple("VARBINARY_MAX")));
        assertEquals("XML", mapper.map(DataType.simple("XMLTYPE")));
        assertEquals("NVARCHAR(MAX)", mapper.map(DataType.simple("JSON")));
        assertEquals("BIT", mapper.map(DataType.simple("BOOLEAN")));
    }

    @Test
    void shouldMapOracleTemporalTypesWithoutUsingSqlServerRowversionTimestamp() {
        SqlServerTypeMapper mapper = new SqlServerTypeMapper();

        assertEquals("DATETIME2(0)", mapper.map(DataType.simple("DATE")));
        assertEquals("DATETIME2(6)", mapper.map(DataType.simple("TIMESTAMP")));
        assertEquals("DATETIME2(3)", mapper.map(DataType.numeric("TIMESTAMP", 3, null)));
        assertEquals("DATETIMEOFFSET(6)", mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertEquals("DATETIME2(0)", mapper.map(DataType.simple("DATETIME2_0")));
        assertEquals("DATETIMEOFFSET(0)", mapper.map(DataType.simple("DATETIMEOFFSET_0")));
        assertEquals("TIME(0)", mapper.map(DataType.simple("TIME_0")));
        assertEquals("ROWVERSION", mapper.map(DataType.simple("SQLSERVER_TIMESTAMP")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("TIMESTAMP", 8, null)));
    }
}
