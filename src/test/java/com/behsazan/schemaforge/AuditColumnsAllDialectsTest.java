package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Audit Columns All Dialects.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class AuditColumnsAllDialectsTest {

    @Test
    void everyRegisteredDialectReceivesConfiguredAuditColumns() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(new Column(
                        Identifier.of("ID"),
                        DataType.numeric("NUMBER", 19, 0),
                        false,
                        null,
                        Description.empty(),
                        false,
                        1))
                .build();

        DatabaseSchema parsed = DatabaseSchema.builder("APP")
                .addTable(table)
                .build();

        PreparedSchema prepared = new SchemaPreparationService().prepare(parsed);

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            String sql = new DdlGenerator(DialectFactory.create(platform))
                    .generate(prepared.schema());
            String normalizedSql = sql.toUpperCase(Locale.ROOT);

            assertTrue(normalizedSql.contains("CREATED_BY"), platform + " missing CREATED_BY");
            assertTrue(normalizedSql.contains("CREATED_DATE"), platform + " missing CREATED_DATE");
            assertTrue(normalizedSql.contains("LAST_MODIFIED_BY"), platform + " missing LAST_MODIFIED_BY");
            assertTrue(normalizedSql.contains("LAST_MODIFIED_DATE"), platform + " missing LAST_MODIFIED_DATE");
        }
    }
}
