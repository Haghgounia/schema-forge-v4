package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.TestSamplePaths;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Enterprise Architect Xml Parser.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class EnterpriseArchitectXmlParserTest {

    @Test
    void shouldParseEaTablesColumnsKeysForeignKeysAndCompositeIndexes() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;
        try (var input = Files.newInputStream(source)) {
            var schema = new EnterpriseArchitectXmlParser("FEE").parse(source.getFileName().toString(), input);

            assertEquals("FEE", schema.name().normalized());
            assertEquals(2, schema.tables().size());
            assertEquals("CONFIG_DEFAULT", schema.metadata().get("source.eaSchemaResolution"));

            var rule = schema.findTable("REGULATORY_RULE").orElseThrow();
            assertEquals("FEE.REGULATORY_RULE", rule.qualifiedName().toString());
            assertEquals("قانون نظارتی", rule.description().value());
            assertEquals(3, rule.columns().size());
            assertFalse(rule.findColumn("REGULATORY_RULE_ID").orElseThrow().nullable());
            assertTrue(rule.findColumn("RULE_DESCRIPTION").orElseThrow().nullable());
            assertEquals(100, rule.findColumn("RULE_DESCRIPTION").orElseThrow().dataType().length());
            assertEquals(LengthSemantics.DEFAULT,
                    rule.findColumn("RULE_DESCRIPTION").orElseThrow().dataType().lengthSemantics());

            assertEquals("PK_REGULATORY_RULE", rule.primaryKey().orElseThrow().name().value());
            assertEquals(1, rule.foreignKeys().size());
            var foreignKey = rule.foreignKeys().get(0);
            assertEquals("FK_RULE_FEE_VERSION", foreignKey.name().value());
            assertEquals("FEE.FEE_VERSION", foreignKey.referencedTable().toString());
            assertEquals("FEE_VERSION_ID", foreignKey.referencedColumns().get(0).value());
            assertEquals(ReferentialAction.CASCADE, foreignKey.onDelete());

            assertEquals(1, rule.indexes().size());
            assertEquals(2, rule.indexes().get(0).columns().size());
            assertEquals("FEE_VERSION_ID", rule.indexes().get(0).columns().get(0).column().value());
            assertEquals("RULE_DESCRIPTION", rule.indexes().get(0).columns().get(1).column().value());
        }
    }
}
