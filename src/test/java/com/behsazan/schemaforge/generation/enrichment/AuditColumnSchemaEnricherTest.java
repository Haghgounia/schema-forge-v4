package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the behavior and regression expectations of Audit Column Schema Enricher.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
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
        assertEquals(List.of(1, 2, 3, 4, 5),
                enriched.columns().stream().map(Column::ordinalPosition).toList());
        assertFalse(enriched.findColumn("CREATED_BY").orElseThrow().nullable());
        assertFalse(enriched.findColumn("CREATED_DATE").orElseThrow().nullable());
        assertFalse(enriched.findColumn("LAST_MODIFIED_BY").orElseThrow().nullable());
        assertFalse(enriched.findColumn("LAST_MODIFIED_DATE").orElseThrow().nullable());
    }

    @Test
    void replacesDocumentAuditColumnsAndMovesAllFourToTheEnd() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .addColumn(Column.nullable("created_by", DataType.varchar("VARCHAR", 100)))
                .addColumn(Column.required("NAME", DataType.varchar("VARCHAR", 80)))
                .addColumn(Column.nullable("last_modified_date", DataType.simple("DATE")))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("TEST").addTable(table).build();

        Table enriched = enricher.enrich(schema).tables().getFirst();

        assertEquals(List.of(
                        "ID", "NAME",
                        "CREATED_BY", "CREATED_DATE", "LAST_MODIFIED_BY", "LAST_MODIFIED_DATE"),
                enriched.columns().stream().map(column -> column.name().normalized()).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6),
                enriched.columns().stream().map(Column::ordinalPosition).toList());
        assertEquals(1, enriched.columns().stream()
                .filter(column -> column.name().normalized().equals("CREATED_BY"))
                .count());
        assertEquals(50, enriched.findColumn("CREATED_BY").orElseThrow().dataType().length());
        assertFalse(enriched.findColumn("CREATED_BY").orElseThrow().nullable());
        assertEquals("TIMESTAMP",
                enriched.findColumn("LAST_MODIFIED_DATE")
                        .orElseThrow()
                        .dataType()
                        .name()
                        .normalized());
    }
}
