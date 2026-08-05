package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Grant Schema Enricher.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class GrantSchemaEnricherTest {

    @Test
    void shouldApplyConfiguredRoleGrantsAndRenderThemAtTheEndForBothDialects() {
        Table table = Table.builder("DPS", "DEPOSITS")
                .persianName("سپرده‌ها")
                .description("Deposit master")
                .addColumn(new Column(
                        Identifier.of("DEPOSIT_ID"),
                        DataType.numeric("NUMBER", 9, 0),
                        false,
                        null,
                        new Description("Deposit identifier"),
                        false,
                        1))
                .build();

        DatabaseSchema source = DatabaseSchema.builder("DPS")
                .addTable(table)
                .build();

        DatabaseSchema enriched = new GrantSchemaEnricher(GrantProperties.defaults()).enrich(source);
        assertEquals("سپرده‌ها", enriched.tables().getFirst().persianName().value());
        String grants = enriched.tables().getFirst().physicalOptions().get("GRANTS");

        assertTrue(grants.contains("SELECT, INSERT, UPDATE, DELETE TO U_DEVELOPER"));
        assertTrue(grants.contains("SELECT, INSERT, UPDATE, DELETE TO U_DESIGNER"));

        String oracle = new DdlGenerator(new OracleDialect()).generate(enriched);
        String postgresql = new DdlGenerator(new PostgreSqlDialect()).generate(enriched);

        assertTrue(oracle.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON DPS.DEPOSITS TO U_DEVELOPER;"));
        assertTrue(oracle.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON DPS.DEPOSITS TO U_DESIGNER;"));
        assertTrue(postgresql.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON dps.deposits TO U_DEVELOPER;"));
        assertTrue(postgresql.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON dps.deposits TO U_DESIGNER;"));

        assertTrue(oracle.lastIndexOf("GRANT ") > oracle.lastIndexOf("COMMENT ON COLUMN"));
        assertTrue(postgresql.lastIndexOf("GRANT ") > postgresql.lastIndexOf("COMMENT ON COLUMN"));
    }

    @Test
    void shouldMergeExplicitAndConfiguredRoleGrantsWithoutDuplicates() {
        GrantProperties properties = new GrantProperties();
        properties.setGrants(List.of(
                new GrantProperties.GrantRule("U_DEVELOPER", List.of("SELECT")),
                new GrantProperties.GrantRule("U_DESIGNER", List.of("SELECT"))));

        Table table = Table.builder("BIM", "CUSTOMERS")
                .addColumn(new Column(
                        Identifier.of("CUSTOMER_ID"),
                        DataType.numeric("NUMBER", 10, 0),
                        false,
                        null,
                        Description.empty(),
                        false,
                        1))
                .physicalOption("GRANTS", "SELECT TO U_DEVELOPER")
                .build();

        DatabaseSchema enriched = new GrantSchemaEnricher(properties).enrich(
                DatabaseSchema.builder("BIM").addTable(table).build());
        String grants = enriched.tables().getFirst().physicalOptions().get("GRANTS");

        assertEquals(1, grants.lines()
                .filter(line -> line.equalsIgnoreCase("SELECT TO U_DEVELOPER"))
                .count());
        assertTrue(grants.contains("SELECT TO U_DESIGNER"));
    }
}
