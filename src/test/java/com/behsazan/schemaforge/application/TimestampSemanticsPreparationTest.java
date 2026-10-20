package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.enrichment.AuditProfile;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampSemanticsPreparationTest {

    @Test
    void requestSemanticsAlsoRewriteNewlySynthesizedCreatedUpdatedTimestamps() {
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        AuditProperties auditProperties = AuditProperties.defaults();
        SchemaPreparationService service = new SchemaPreparationService(
                auditProperties,
                GrantProperties.defaults(),
                spellCheck,
                NamingValidationProperties.defaults(),
                new ObjectMapper());

        DatabaseSchema source = DatabaseSchema.builder("APP")
                .addTable(Table.builder("APP", "EVENT")
                        .addColumn(Column.required("EVENT_ID", DataType.numeric("NUMBER", 18, 0)))
                        .addColumn(Column.required("EVENT_AT", DataType.numeric("TIMESTAMP", 3, null)))
                        .build())
                .build();

        AuditGenerationOptions audit = AuditGenerationOptions.resolve(
                auditProperties, true, "CREATED_UPDATED");
        PreparedSchema prepared = service.prepare(source, audit, TimestampSemantics.WITH_TIME_ZONE);

        Column sourceTimestamp = prepared.schema().tables().getFirst().findColumn("EVENT_AT").orElseThrow();
        assertEquals("TIMESTAMP_WITH_TIME_ZONE", sourceTimestamp.dataType().name().normalized());
        assertEquals(3, sourceTimestamp.dataType().precision());

        Column auditTimestamp = prepared.schema().tables().getFirst().findColumn("CREATED_AT").orElseThrow();
        assertEquals("TIMESTAMP_WITH_TIME_ZONE", auditTimestamp.dataType().name().normalized());
        assertEquals(6, auditTimestamp.dataType().precision());
        Column updated = prepared.schema().tables().getFirst().findColumn("UPDATED_AT").orElseThrow();
        assertEquals("TIMESTAMP_WITH_TIME_ZONE", updated.dataType().name().normalized());
        assertEquals(6, updated.dataType().precision());
        assertTrue(updated.description().value().contains("آخرین به‌روزرسانی"));
        assertTrue(updated.description().value().contains("Last update timestamp"));
    }

    @Test
    void configuredCreatedLastModifiedAuditTimestampsRespectLocalTimezoneChoice() {
        SchemaPreparationService service = service(AuditProperties.defaults());
        DatabaseSchema source = DatabaseSchema.builder("APP")
                .addTable(Table.builder("APP", "EVENT")
                        .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                        .build())
                .build();
        PreparedSchema prepared = service.prepare(source,
                new AuditGenerationOptions(true, AuditProfile.CREATED_LAST_MODIFIED),
                TimestampSemantics.WITH_LOCAL_TIME_ZONE);
        Table result = prepared.schema().tables().getFirst();
        for (String name : new String[] {"CREATED_DATE", "LAST_MODIFIED_DATE"}) {
            Column timestamp = result.findColumn(name).orElseThrow();
            assertEquals("TIMESTAMP_WITH_LOCAL_TIME_ZONE", timestamp.dataType().name().normalized(), name);
            assertFalse(timestamp.description().isEmpty(), name);
            assertTrue(timestamp.description().value().contains("تاریخ و زمان"), name);
        }
        assertEquals("VARCHAR2", result.findColumn("CREATED_BY").orElseThrow()
                .dataType().name().normalized());
        assertTrue(result.findColumn("CREATED_BY").orElseThrow()
                .description().value().contains("کاربر ایجادکننده"));
        assertTrue(result.findColumn("LAST_MODIFIED_BY").orElseThrow()
                .description().value().contains("Last modification user"));
    }

    @Test
    void sourceOrWithoutTimezoneKeepConfiguredAuditTimestampUnqualified() {
        SchemaPreparationService service = service(AuditProperties.defaults());
        DatabaseSchema parsed = DatabaseSchema.builder("APP")
                .addTable(Table.builder("APP", "EVENT")
                        .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                        .build()).build();
        for (TimestampSemantics semantics : new TimestampSemantics[] {
                TimestampSemantics.SOURCE, TimestampSemantics.WITHOUT_TIME_ZONE}) {
            Table table = service.prepare(parsed,
                    new AuditGenerationOptions(true, AuditProfile.CREATED_LAST_MODIFIED), semantics)
                    .schema().tables().getFirst();
            assertEquals("TIMESTAMP", table.findColumn("CREATED_DATE").orElseThrow()
                    .dataType().name().normalized(), semantics.name());
            assertEquals("TIMESTAMP", table.findColumn("LAST_MODIFIED_DATE").orElseThrow()
                    .dataType().name().normalized(), semantics.name());
        }
    }

    @Test
    void sourceQualifiedAuditAndNonTimestampTypesArePreserved() {
        SchemaPreparationService service = service(AuditProperties.defaults());
        Column qualified = new Column(Identifier.of("CREATED_DATE"),
                DataType.numeric("TIMESTAMP_WITH_LOCAL_TIME_ZONE", 3, null),
                false, null, new Description("Source Persian label"), false, 2);
        Column nonTimestamp = new Column(Identifier.of("LAST_MODIFIED_DATE"),
                DataType.simple("DATE"), false, null,
                new Description("Existing DATE declaration"), false, 3);
        DatabaseSchema parsed = DatabaseSchema.builder("APP")
                .addTable(Table.builder("APP", "EVENT")
                        .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                        .addColumn(qualified)
                        .addColumn(nonTimestamp)
                        .build()).build();
        Table table = service.prepare(parsed,
                new AuditGenerationOptions(true, AuditProfile.CREATED_LAST_MODIFIED),
                TimestampSemantics.WITH_TIME_ZONE).schema().tables().getFirst();
        Column keptQualified = table.findColumn("CREATED_DATE").orElseThrow();
        assertEquals("TIMESTAMP_WITH_LOCAL_TIME_ZONE", keptQualified.dataType().name().normalized());
        assertEquals(3, keptQualified.dataType().precision());
        assertEquals("Source Persian label", keptQualified.description().value());
        Column keptDate = table.findColumn("LAST_MODIFIED_DATE").orElseThrow();
        assertEquals("DATE", keptDate.dataType().name().normalized());
        assertEquals("Existing DATE declaration", keptDate.description().value());
        assertTrue(table.findColumn("LAST_MODIFIED_BY").isPresent());
    }

    @Test
    void customPersianNameAndEnglishCommentAreBothVisibleAndOptional() {
        AuditProperties properties = AuditProperties.defaults();
        var custom = new java.util.ArrayList<>(properties.getColumns());
        var configured = new AuditProperties.AuditColumn(
                "CREATED_DATE", "TIMESTAMP", false, "Custom English title", "تاریخ ثبت");
        custom.set(1, configured);
        properties.setColumns(custom);
        Table table = service(properties).prepare(
                DatabaseSchema.builder("APP")
                        .addTable(Table.builder("APP", "EVENT")
                                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                                .build()).build(),
                new AuditGenerationOptions(true, AuditProfile.CREATED_LAST_MODIFIED),
                TimestampSemantics.WITH_TIME_ZONE).schema().tables().getFirst();
        assertEquals("تاریخ ثبت | Custom English title",
                table.findColumn("CREATED_DATE").orElseThrow().description().value());

        configured.setPersianName(null);
        Table withoutPersian = service(properties).prepare(
                DatabaseSchema.builder("APP")
                        .addTable(Table.builder("APP", "EVENT")
                                .addColumn(Column.required("ID", DataType.simple("NUMBER")))
                                .build()).build(),
                new AuditGenerationOptions(true, AuditProfile.CREATED_LAST_MODIFIED),
                TimestampSemantics.SOURCE).schema().tables().getFirst();
        assertEquals("Custom English title",
                withoutPersian.findColumn("CREATED_DATE").orElseThrow().description().value());
    }

    private static SchemaPreparationService service(AuditProperties properties) {
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        return new SchemaPreparationService(properties, GrantProperties.defaults(), spellCheck,
                NamingValidationProperties.defaults(), new ObjectMapper());
    }
}
