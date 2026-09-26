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

import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaPreparationNamingValidationTest {

    @Test
    void appliesConfiguredSingularNamingOnceDuringCanonicalPreparation() {
        NamingValidationProperties naming = new NamingValidationProperties();
        naming.setTableConvention(NamingValidationProperties.TableConvention.SINGULAR);

        SchemaPreparationService preparation = new SchemaPreparationService(
                AuditProperties.defaults(),
                GrantProperties.defaults(),
                SpellCheckProperties.defaults(),
                naming,
                new ObjectMapper());

        Table table = Table.builder("DPS2", "DEPOSIT_ACCOUNT")
                .addColumn(Column.nullable("EXPIRES_AT", DataType.varchar("VARCHAR2", 30)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS2").addTable(table).build();

        PreparedSchema prepared = preparation.prepare(schema);

        assertTrue(prepared.validationReport().issues().stream().noneMatch(issue ->
                issue.code().equals("TABLE_NAME_NOT_SINGULAR")
                        || issue.code().equals("TABLE_NAME_NOT_PLURAL")
                        || issue.code().equals("PLURAL_COLUMN_COMPONENT")));
    }
}
