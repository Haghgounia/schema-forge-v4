package com.behsazan.schemaforge.dialect.mysql;

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
    void shouldRejectLossyOrUnsupportedMappings() {
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("NUMBER")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("NUMBER", 66, 0)));
    }
}
