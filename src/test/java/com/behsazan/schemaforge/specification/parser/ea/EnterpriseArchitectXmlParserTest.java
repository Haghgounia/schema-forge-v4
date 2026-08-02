package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.TestSamplePaths;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

    @Test
    void shouldUseColAsDefaultAndReadDocumentationHtmlAndCodeChecks() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="SAMPLE_TABLE" xmi.id="TABLE_1">
                          <UML:ModelElement.stereotype>
                            <UML:Stereotype name="table"/>
                          </UML:ModelElement.stereotype>
                          <UML:ModelElement.taggedValue>
                            <UML:TaggedValue tag="documentation"
                              value="&lt;span dir=&quot;rtl&quot;&gt;شرح جدول&lt;/span&gt;"/>
                          </UML:ModelElement.taggedValue>
                          <UML:Classifier.feature>
                            <UML:Attribute name="ID">
                              <UML:ModelElement.stereotype>
                                <UML:Stereotype name="column"/>
                              </UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="description"
                                  value="&lt;span dir=&quot;rtl&quot;&gt;شناسه&lt;/span&gt;"/>
                                <UML:TaggedValue tag="type" value="NUMBER"/>
                                <UML:TaggedValue tag="precision" value="10"/>
                                <UML:TaggedValue tag="scale" value="0"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_SAMPLE_TABLE">
                              <UML:ModelElement.stereotype>
                                <UML:Stereotype name="PK"/>
                              </UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                            <UML:Operation name="CK_SAMPLE_TABLE_ID">
                              <UML:ModelElement.stereotype>
                                <UML:Stereotype name="check"/>
                              </UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="code" value="CHECK (ID &gt;= 1)"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser().parse(
                "sample.xml",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("COL", schema.name().normalized());
        var table = schema.findTable("SAMPLE_TABLE").orElseThrow();
        assertEquals("COL.SAMPLE_TABLE", table.qualifiedName().toString());
        assertEquals("شرح جدول", table.description().value());
        assertEquals("شناسه", table.findColumn("ID").orElseThrow().description().value());
        assertEquals(1, table.checkConstraints().size());
        assertEquals("ID >= 1", table.checkConstraints().getFirst().expression());
    }

    @Test
    void shouldHonorExplicitSchemaOverrideAndInferPrimaryKeyIdentityWhenEnabled() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;
        try (var input = Files.newInputStream(source)) {
            var schema = new EnterpriseArchitectXmlParser("FEE", true)
                    .parse(source.getFileName().toString(), input, "API_SCHEMA");

            assertEquals("API_SCHEMA", schema.name().normalized());
            assertEquals("API_PARAMETER", schema.metadata().get("source.eaSchemaResolution"));
            assertEquals("API_SCHEMA", schema.metadata().get("source.eaRequestedSchema"));

            var rule = schema.findTable("REGULATORY_RULE").orElseThrow();
            assertEquals("API_SCHEMA.REGULATORY_RULE", rule.qualifiedName().toString());
            assertTrue(rule.findColumn("REGULATORY_RULE_ID").orElseThrow().identity());
            assertFalse(rule.findColumn("FEE_VERSION_ID").orElseThrow().identity());
        }
    }

}
