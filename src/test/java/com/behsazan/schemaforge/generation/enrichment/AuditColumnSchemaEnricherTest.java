package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuditColumnSchemaEnricherTest {
    private final AuditColumnSchemaEnricher enricher = new AuditColumnSchemaEnricher();

    @Test
    void appendsMissingAuditColumnsInStandardOrder() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("TEST").addTable(table).build();

        Table enriched = enricher.enrich(schema).tables().getFirst();

        assertEquals(List.of("ID", "CREATED_BY", "CREATED_DATE", "LAST_MODIFIED_BY", "LAST_MODIFIED_DATE"),
                enriched.columns().stream().map(column -> column.name().normalized()).toList());
        assertFalse(enriched.findColumn("CREATED_BY").orElseThrow().nullable());
        assertFalse(enriched.findColumn("CREATED_DATE").orElseThrow().nullable());
        assertFalse(enriched.findColumn("LAST_MODIFIED_BY").orElseThrow().nullable());
        assertFalse(enriched.findColumn("LAST_MODIFIED_DATE").orElseThrow().nullable());
    }

    @Test
    void doesNotDuplicateAuditColumnsAlreadyDeclaredByDocument() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .addColumn(Column.nullable("created_by", DataType.varchar("VARCHAR", 100)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("TEST").addTable(table).build();

        Table enriched = enricher.enrich(schema).tables().getFirst();

        assertEquals(1, enriched.columns().stream()
                .filter(column -> column.name().normalized().equals("CREATED_BY"))
                .count());
        assertEquals(100, enriched.findColumn("CREATED_BY").orElseThrow().dataType().length());
    }
}
