package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures EA/Word preparation selects the offline provider without external HTTP. */
class SchemaPreparationOfflineSpellingTest {
    @Test
    void offlineSpellCheckProducesAdvisoryWithoutChangingCanonicalNames() {
        var spelling = new SpellCheckProperties();
        spelling.setProvider("OFFLINE");
        spelling.setEnabled(true);
        // If the wrong provider is selected this endpoint must not be accessed.
        spelling.setEndpoint("http://127.0.0.1:1/v2/check");
        var schema = DatabaseSchema.builder("EXAMPLE")
                .addTable(Table.builder("APP", "CUSTOMER")
                        .addColumn(Column.nullable("CUSTMER_NAME", DataType.varchar("VARCHAR2", 80)))
                        .build())
                .build();

        var output = new SchemaPreparationService(AuditProperties.defaults(), spelling, new ObjectMapper())
                .prepare(schema);

        assertTrue(output.validationReport().issues().stream().anyMatch(issue ->
                issue.code().equals("SPELLING_WARNING")
                        && issue.message().contains("CUSTOMER")));
        assertEquals("CUSTMER_NAME", output.schema().tables().getFirst().columns().getFirst().name().value());
    }

    @Test
    void unknownEnabledProviderIsRejectedRatherThanSilentlyUsingNetwork() {
        var spelling = new SpellCheckProperties();
        spelling.setEnabled(true);
        spelling.setProvider("UNKNOWN");
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaPreparationService(AuditProperties.defaults(), spelling, new ObjectMapper()));
    }
}
