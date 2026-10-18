package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimestampSemanticsPreparationTest {

    @Test
    void requestSemanticsApplyBeforeAuditEnrichmentAndDoNotRewriteSynthesizedAuditTimestamps() {
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
        assertEquals("TIMESTAMP", auditTimestamp.dataType().name().normalized());
        assertEquals(6, auditTimestamp.dataType().precision());
    }
}
