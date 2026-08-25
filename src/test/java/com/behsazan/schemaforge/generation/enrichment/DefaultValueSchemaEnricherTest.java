package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultValueSchemaEnricherTest {

    @Test
    void quotesBareCharacterLiteralButPreservesSqlExpressionsAndAlreadyQuotedValues() {
        Table table = Table.builder("DPS", "DEFAULT_TEST")
                .addColumn(column("STATUS_CODE", DataType.varchar("VARCHAR2", 30), "ACTIVE", 1))
                .addColumn(column("STATE_CODE", DataType.varchar("VARCHAR2", 30), "'DRAFT'", 2))
                .addColumn(column("CREATED_BY", DataType.varchar("VARCHAR2", 100), "USER", 3))
                .addColumn(column("CREATED_AT", DataType.simple("TIMESTAMP"), "SYSTIMESTAMP", 4))
                .addColumn(column("RECORD_VERSION", DataType.numeric("NUMBER", 10, 0), "1", 5))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS").addTable(table).build();

        Table normalized = new DefaultValueSchemaEnricher().enrich(schema).tables().getFirst();

        assertEquals("'ACTIVE'", normalized.findColumn("STATUS_CODE").orElseThrow().defaultValue().expression());
        assertEquals("'DRAFT'", normalized.findColumn("STATE_CODE").orElseThrow().defaultValue().expression());
        assertEquals("USER", normalized.findColumn("CREATED_BY").orElseThrow().defaultValue().expression());
        assertEquals("SYSTIMESTAMP", normalized.findColumn("CREATED_AT").orElseThrow().defaultValue().expression());
        assertEquals("1", normalized.findColumn("RECORD_VERSION").orElseThrow().defaultValue().expression());
    }

    private static Column column(String name, DataType type, String defaultValue, int position) {
        return new Column(
                Identifier.of(name), type, false, new DefaultValue(defaultValue),
                Description.empty(), false, position);
    }
}
