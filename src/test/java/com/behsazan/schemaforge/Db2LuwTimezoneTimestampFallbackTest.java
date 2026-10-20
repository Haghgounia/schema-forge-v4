package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Db2LuwTimezoneTimestampFallbackTest {
    @Test
    void bothTimezoneSemanticsFallbackToTimestampWithCompatibilityWarnings() {
        Table table = Table.builder("APP", "EVENTS")
                .addColumn(Column.required("CREATED_DATE", DataType.simple("TIMESTAMP_WITH_TIME_ZONE")))
                .addColumn(Column.nullable("MODIFIED_DATE", DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")))
                .build();
        String sql = new DdlGenerator(new Db2LuwDialect()).generate(
                DatabaseSchema.builder("APP").addTable(table).build());
        assertTrue(sql.contains("CREATED_DATE TIMESTAMP NOT NULL"), sql);
        assertTrue(sql.contains("MODIFIED_DATE TIMESTAMP"), sql);
        assertTrue(sql.contains("[WARNING] DB2_LUW_TIMEZONE_TIMESTAMP_FALLBACK"), sql);
        assertFalse(sql.contains("WITH TIME ZONE NOT NULL"), sql);
    }
}
