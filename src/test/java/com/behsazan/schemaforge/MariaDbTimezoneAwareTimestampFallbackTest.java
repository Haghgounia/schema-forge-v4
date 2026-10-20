package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
import com.behsazan.schemaforge.dialect.mariadb.MariaDbTypeMapper;
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

class MariaDbTimezoneAwareTimestampFallbackTest {
    @Test
    void bothTimezoneSemanticsRenderNativeTimestampWithWarnings() {
        Table table = Table.builder("APP", "EVENTS")
                .addColumn(Column.required("CREATED_DATE", DataType.simple("TIMESTAMP_WITH_TIME_ZONE")))
                .addColumn(Column.nullable("LOCAL_DATE", DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")))
                .build();
        String sql = new DdlGenerator(new MariaDbDialect()).generate(
                DatabaseSchema.builder("APP").addTable(table).build());
        assertTrue(sql.contains("`CREATED_DATE` TIMESTAMP NOT NULL"), sql);
        assertTrue(sql.contains("`LOCAL_DATE` TIMESTAMP"), sql);
        assertTrue(sql.contains("[WARNING] MARIADB_TIMEZONE_TIMESTAMP_FALLBACK"), sql);
        assertFalse(sql.contains("VARCHAR(128)"), sql);
        assertFalse(sql.contains("MARIADB-TSTZ-TEXT-001"), sql);
    }

    @Test
    void directMapperAcceptsBothVariantsAndBoundsPrecision() {
        MariaDbTypeMapper mapper = new MariaDbTypeMapper();
        assertEquals("TIMESTAMP", mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
        assertEquals("TIMESTAMP", mapper.map(DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")));
        assertEquals("TIMESTAMP(6)", mapper.map(DataType.numeric("TIMESTAMP_WITH_LOCAL_TIME_ZONE", 6, null)));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.numeric("TIMESTAMP_WITH_LOCAL_TIME_ZONE", 7, null)));
    }
}
