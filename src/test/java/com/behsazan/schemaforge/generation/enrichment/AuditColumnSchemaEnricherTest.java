package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for request-level audit enrichment and profile detection. */
class AuditColumnSchemaEnricherTest {

    @Test
    void appendsMissingDefaultFamilyWhenSourceHasNoAuditConvention() {
        Table table = Table.builder("APP", "CUSTOMER")
                .persianName("مشتری")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .build();

        Table enriched = new AuditColumnSchemaEnricher().enrich(
                DatabaseSchema.builder("TEST").addTable(table).build()).tables().getFirst();

        assertEquals(List.of("ID", "CREATED_BY", "CREATED_DATE", "LAST_MODIFIED_BY", "LAST_MODIFIED_DATE"),
                enriched.columns().stream().map(column -> column.name().normalized()).toList());
        assertEquals(List.of(1, 2, 3, 4, 5),
                enriched.columns().stream().map(Column::ordinalPosition).toList());
    }

    @Test
    void preservesExistingAuditColumnSemanticsAndAddsOnlyMissingMembersOfDetectedFamily() {
        Column createdBy = new Column(
                Identifier.of("CREATED_BY"),
                DataType.varchar("VARCHAR2", 100),
                false,
                new DefaultValue("'SYSTEM'"),
                new Description("Source-defined creator"),
                false,
                2);
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .addColumn(createdBy)
                .addColumn(Column.nullable("LAST_MODIFIED_DATE", DataType.simple("DATE")))
                .build();

        Table enriched = new AuditColumnSchemaEnricher().enrich(
                DatabaseSchema.builder("TEST").addTable(table).build()).tables().getFirst();

        Column preserved = enriched.findColumn("CREATED_BY").orElseThrow();
        assertEquals(100, preserved.dataType().length());
        assertEquals("'SYSTEM'", preserved.defaultValue().expression());
        assertEquals("Source-defined creator", preserved.description().value());
        assertFalse(preserved.nullable());
        assertTrue(enriched.findColumn("CREATED_DATE").isPresent());
        assertTrue(enriched.findColumn("LAST_MODIFIED_BY").isPresent());
        assertTrue(enriched.findColumn("CREATED_AT").isEmpty());
        assertTrue(enriched.findColumn("UPDATED_AT").isEmpty());
        assertEquals(1, enriched.columns().stream()
                .filter(column -> column.name().normalized().equals("CREATED_BY")).count());
    }

    @Test
    void autoDetectsCreatedUpdatedAndNeverMixesSecondAuditFamily() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .addColumn(Column.required("CREATED_AT", DataType.numeric("TIMESTAMP", 6, null)))
                .addColumn(Column.required("CREATED_BY", DataType.varchar("VARCHAR2", 50)))
                .addColumn(Column.nullable("UPDATED_AT", DataType.numeric("TIMESTAMP", 6, null)))
                .build();

        Table enriched = new AuditColumnSchemaEnricher().enrich(
                DatabaseSchema.builder("TEST").addTable(table).build()).tables().getFirst();

        assertTrue(enriched.findColumn("UPDATED_BY").isPresent());
        assertEquals(100, enriched.findColumn("UPDATED_BY").orElseThrow().dataType().length());
        assertTrue(enriched.findColumn("CREATED_DATE").isEmpty());
        assertTrue(enriched.findColumn("LAST_MODIFIED_DATE").isEmpty());
        assertTrue(enriched.findColumn("LAST_MODIFIED_BY").isEmpty());
    }


    @Test
    void autoUsesDocumentFamilyForTablesThatHaveNoAuditEvidence() {
        Table withAudit = Table.builder("APP", "A")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .addColumn(Column.required("CREATED_AT", DataType.numeric("TIMESTAMP", 6, null)))
                .addColumn(Column.required("CREATED_BY", DataType.varchar("VARCHAR2", 50)))
                .addColumn(Column.nullable("UPDATED_AT", DataType.numeric("TIMESTAMP", 6, null)))
                .addColumn(Column.nullable("UPDATED_BY", DataType.varchar("VARCHAR2", 100)))
                .build();
        Table withoutAudit = Table.builder("APP", "B")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .build();

        DatabaseSchema enriched = new AuditColumnSchemaEnricher().enrich(
                DatabaseSchema.builder("TEST").addTable(withAudit).addTable(withoutAudit).build());
        Table b = enriched.tables().stream()
                .filter(table -> table.qualifiedName().name().normalized().equals("B"))
                .findFirst().orElseThrow();

        assertTrue(b.findColumn("CREATED_AT").isPresent());
        assertTrue(b.findColumn("UPDATED_AT").isPresent());
        assertTrue(b.findColumn("UPDATED_BY").isPresent());
        assertTrue(b.findColumn("CREATED_DATE").isEmpty());
        assertTrue(b.findColumn("LAST_MODIFIED_DATE").isEmpty());
    }

    @Test
    void explicitCreatedUpdatedProfileUsesRequestedPhysicalShapeForMissingColumns() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .build();

        AuditColumnSchemaEnricher enricher = new AuditColumnSchemaEnricher(
                AuditProperties.defaults(), true, AuditProfile.CREATED_UPDATED);
        Table enriched = enricher.enrich(
                DatabaseSchema.builder("TEST").addTable(table).build()).tables().getFirst();

        assertEquals(6, enriched.findColumn("CREATED_AT").orElseThrow().dataType().precision());
        assertEquals(50, enriched.findColumn("CREATED_BY").orElseThrow().dataType().length());
        assertEquals(6, enriched.findColumn("UPDATED_AT").orElseThrow().dataType().precision());
        assertEquals(100, enriched.findColumn("UPDATED_BY").orElseThrow().dataType().length());
    }

    @Test
    void mixedSourceFamiliesFailInsteadOfProducingSixAuditFields() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .addColumn(Column.required("CREATED_AT", DataType.simple("TIMESTAMP")))
                .addColumn(Column.required("LAST_MODIFIED_DATE", DataType.simple("TIMESTAMP")))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new AuditColumnSchemaEnricher().enrich(
                        DatabaseSchema.builder("TEST").addTable(table).build()));
        assertTrue(error.getMessage().contains("AUDIT_PROFILE_CONFLICT"));
    }

    @Test
    void disabledRequestLeavesColumnsUntouched() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("TEST").addTable(table).build();

        DatabaseSchema enriched = new AuditColumnSchemaEnricher(
                AuditProperties.defaults(), false, AuditProfile.AUTO).enrich(schema);
        assertSame(schema, enriched);
        assertEquals(List.of("ID"), enriched.tables().getFirst().columns().stream()
                .map(column -> column.name().normalized()).toList());
    }
}
