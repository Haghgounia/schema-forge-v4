package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlTypeMapper;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTimezoneAwareTimestampAdaptationTest {

    @Test
    void bothTimezoneSemanticsRenderNativeTimestampWithExplicitWarning() {
        Table table = Table.builder("COL", "PARTY_CONSENT")
                .addColumn(Column.required("GRANTED_AT", DataType.simple("TIMESTAMP_WITH_TIME_ZONE")))
                .addColumn(Column.nullable("LOCAL_AT", DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")))
                .build();

        String sql = new DdlGenerator(new MySqlDialect()).generate(
                DatabaseSchema.builder("COL").addTable(table).build());

        assertTrue(sql.contains("`GRANTED_AT` TIMESTAMP NOT NULL"), sql);
        assertTrue(sql.contains("`LOCAL_AT` TIMESTAMP"), sql);
        assertTrue(sql.contains("[WARNING] MYSQL_TIMEZONE_TIMESTAMP_FALLBACK"), sql);
        assertTrue(sql.contains("session time_zone"), sql);
        assertFalse(sql.contains("VARCHAR(128)"), sql);
        assertFalse(sql.contains("MYSQL-TSTZ-TEXT-001"), sql);
    }

    @Test
    void logicalMapperUsesRequestedTimestampFallbackAndPreservesExplicitPrecision() {
        MySqlTypeMapper mapper = new MySqlTypeMapper();
        assertEquals("TIMESTAMP", mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertEquals("TIMESTAMP", mapper.map(DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")));
        assertEquals("TIMESTAMP(6)", mapper.map(DataType.numeric("TIMESTAMP_WITH_TIME_ZONE", 6, null)));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("TIMESTAMP_WITH_TIME_ZONE", 7, null)));
    }

    @Test
    void ordinaryTimestampMappingRemainsUnchanged() {
        assertEquals("DATETIME", new MySqlDialect().sqlType(
                Column.nullable("CREATED_AT", DataType.simple("TIMESTAMP"))));
    }
}
