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
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertEquals("قانون نظارتی", rule.persianName().value());
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
            assertEquals("FK_REGULATORY_RULE_FEE_VERSION_ID", foreignKey.name().value());
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
                            <UML:TaggedValue tag="alias" value="نام فارسی جدول"/>
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
                            <UML:Operation name="CHK_SAMPLE_TABLE_ID">
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
        assertEquals("نام فارسی جدول", table.persianName().value());
        assertEquals("شرح جدول", table.description().value());
        assertEquals("شناسه", table.findColumn("ID").orElseThrow().description().value());
        assertEquals(1, table.checkConstraints().size());
        assertEquals("ID >= 1", table.checkConstraints().getFirst().expression());
    }

    @Test
    void shouldIgnoreEaCheckPseudoColumnsAndRecoverCheckExpressionFromDocumentation() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="REF_SAMPLE" xmi.id="TABLE_1">
                          <UML:ModelElement.stereotype>
                            <UML:Stereotype name="table"/>
                          </UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="IS_ACTIVE">
                              <UML:ModelElement.stereotype>
                                <UML:Stereotype name="column"/>
                              </UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="NUMBER"/>
                                <UML:TaggedValue tag="precision" value="1"/>
                                <UML:TaggedValue tag="scale" value="0"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Attribute name="CK_REF_IS_ACTIVE">
                              <UML:ModelElement.stereotype>
                                <UML:Stereotype name="column"/>
                              </UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="CHECK"/>
                                <UML:TaggedValue tag="position" value="10"/>
                                <UML:TaggedValue tag="lowerBound" value="0"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Attribute name="CK_REF_VALID_DATES">
                              <UML:ModelElement.stereotype>
                                <UML:Stereotype name="column"/>
                              </UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="CHECK"/>
                                <UML:TaggedValue tag="position" value="11"/>
                                <UML:TaggedValue tag="lowerBound" value="0"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="CK_REF_SAMPLE_ACTIVE">
                              <UML:ModelElement.stereotype>
                                <UML:Stereotype name="check"/>
                              </UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="documentation" value="IS_ACTIVE IN (0,1)"/>
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
                "reference-data.xml",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var table = schema.findTable("REF_SAMPLE").orElseThrow();
        assertEquals(1, table.columns().size());
        assertTrue(table.findColumn("IS_ACTIVE").isPresent());
        assertFalse(table.findColumn("CK_REF_IS_ACTIVE").isPresent());
        assertFalse(table.findColumn("CK_REF_VALID_DATES").isPresent());
        assertEquals(1, table.checkConstraints().size());
        assertEquals("IS_ACTIVE IN (0,1)", table.checkConstraints().getFirst().expression());

        String warnings = schema.metadata().getOrDefault("recovery.warnings", "");
        assertTrue(warnings.contains("EA_CHECK_PSEUDO_COLUMN_IGNORED|table=REF_SAMPLE|attribute=CK_REF_IS_ACTIVE|type=CHECK"));
        assertTrue(warnings.contains("EA_CHECK_PSEUDO_COLUMN_IGNORED|table=REF_SAMPLE|attribute=CK_REF_VALID_DATES|type=CHECK"));
        assertTrue(warnings.contains("EA_CHECK_EXPRESSION_RECOVERED_FROM_DOCUMENTATION|table=REF_SAMPLE|check=CK_REF_SAMPLE_ACTIVE"));
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

    @Test
    void shouldRecoverForeignKeyFromPortableXmiParentChildAssociationTags() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Package name="FEE" xmi.id="PKG_1">
                          <UML:Namespace.ownedElement>
                            <UML:Class name="PARENT_TABLE" xmi.id="TABLE_PARENT">
                              <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                              <UML:Classifier.feature>
                                <UML:Attribute name="PARENT_ID">
                                  <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="type" value="NUMBER"/>
                                    <UML:TaggedValue tag="precision" value="10"/>
                                    <UML:TaggedValue tag="scale" value="0"/>
                                    <UML:TaggedValue tag="position" value="0"/>
                                    <UML:TaggedValue tag="lowerBound" value="1"/>
                                  </UML:ModelElement.taggedValue>
                                </UML:Attribute>
                                <UML:Operation name="PK_PARENT_TABLE">
                                  <UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype>
                                  <UML:BehavioralFeature.parameter>
                                    <UML:Parameter name="PARENT_ID" kind="in"/>
                                  </UML:BehavioralFeature.parameter>
                                </UML:Operation>
                              </UML:Classifier.feature>
                            </UML:Class>
                            <UML:Class name="CHILD_TABLE" xmi.id="TABLE_CHILD">
                              <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                              <UML:Classifier.feature>
                                <UML:Attribute name="CHILD_ID">
                                  <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="type" value="NUMBER"/>
                                    <UML:TaggedValue tag="precision" value="10"/>
                                    <UML:TaggedValue tag="scale" value="0"/>
                                    <UML:TaggedValue tag="position" value="0"/>
                                    <UML:TaggedValue tag="lowerBound" value="1"/>
                                  </UML:ModelElement.taggedValue>
                                </UML:Attribute>
                                <UML:Attribute name="PARENT_ID">
                                  <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="type" value="NUMBER"/>
                                    <UML:TaggedValue tag="precision" value="10"/>
                                    <UML:TaggedValue tag="scale" value="0"/>
                                    <UML:TaggedValue tag="position" value="1"/>
                                    <UML:TaggedValue tag="lowerBound" value="1"/>
                                  </UML:ModelElement.taggedValue>
                                </UML:Attribute>
                                <UML:Operation name="PK_CHILD_TABLE">
                                  <UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype>
                                  <UML:BehavioralFeature.parameter>
                                    <UML:Parameter name="CHILD_ID" kind="in"/>
                                  </UML:BehavioralFeature.parameter>
                                </UML:Operation>
                                <UML:Operation name="FK_CHILD_PARENT">
                                  <UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype>
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="targetTable" value="PARENT_TABLE"/>
                                    <UML:TaggedValue tag="targetColumns" value="PARENT_ID"/>
                                  </UML:ModelElement.taggedValue>
                                  <UML:BehavioralFeature.parameter>
                                    <UML:Parameter name="PARENT_ID" kind="in"/>
                                  </UML:BehavioralFeature.parameter>
                                </UML:Operation>
                              </UML:Classifier.feature>
                            </UML:Class>
                            <UML:Association name="FK_CHILD_PARENT" xmi.id="ASSOC_1">
                              <UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="constraint" value="FK_CHILD_PARENT"/>
                                <UML:TaggedValue tag="sourceTable" value="CHILD_TABLE"/>
                                <UML:TaggedValue tag="sourceColumns" value="PARENT_ID"/>
                                <UML:TaggedValue tag="targetTable" value="PARENT_TABLE"/>
                                <UML:TaggedValue tag="targetColumns" value="PARENT_ID"/>
                              </UML:ModelElement.taggedValue>
                              <UML:Association.connection>
                                <UML:AssociationEnd name="parent" type="TABLE_PARENT">
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="role" value="parent"/>
                                  </UML:ModelElement.taggedValue>
                                </UML:AssociationEnd>
                                <UML:AssociationEnd name="child" type="TABLE_CHILD">
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="role" value="child"/>
                                  </UML:ModelElement.taggedValue>
                                </UML:AssociationEnd>
                              </UML:Association.connection>
                            </UML:Association>
                          </UML:Namespace.ownedElement>
                        </UML:Package>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("FEE").parse(
                "portable-parent-child.xmi",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var child = schema.findTable("CHILD_TABLE").orElseThrow();
        assertFalse(child.findColumn("CHILD_ID").orElseThrow().identity());
        assertEquals(1, child.foreignKeys().size());
        var foreignKey = child.foreignKeys().getFirst();
        assertEquals("FK_CHILD_TABLE_PARENT_ID", foreignKey.name().value());
        assertEquals("PARENT_ID", foreignKey.columns().getFirst().value());
        assertEquals("FEE.PARENT_TABLE", foreignKey.referencedTable().toString());
        assertEquals("PARENT_ID", foreignKey.referencedColumns().getFirst().value());
        assertFalse(schema.metadata().containsKey("recovery.warningCount"));
    }

    @Test
    void shouldNotInferIdentityForCharacterPrimaryKeyWhenEnabled() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="REF_STATUS" xmi.id="TABLE_STATUS">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="STATUS_CODE">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="VARCHAR2"/>
                                <UML:TaggedValue tag="length" value="50"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_REF_STATUS">
                              <UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="STATUS_CODE" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("REF", true).parse(
                "reference.xml", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var statusCode = schema.findTable("REF_STATUS").orElseThrow()
                .findColumn("STATUS_CODE").orElseThrow();
        assertFalse(statusCode.identity());
        assertEquals("VARCHAR2", statusCode.dataType().name().normalized());
        assertEquals(50, statusCode.dataType().length());
    }

    @Test
    void shouldPreserveTimestampPrecisionFromEaTaggedValues() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="EVENT_LOG" xmi.id="TABLE_EVENT_LOG">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="EVENT_ID">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="NUMBER"/>
                                <UML:TaggedValue tag="precision" value="19"/>
                                <UML:TaggedValue tag="scale" value="0"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Attribute name="OCCURRED_AT">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="TIMESTAMP"/>
                                <UML:TaggedValue tag="precision" value="6"/>
                                <UML:TaggedValue tag="position" value="1"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Attribute name="SOURCE_OCCURRED_AT">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="TIMESTAMP WITH TIME ZONE"/>
                                <UML:TaggedValue tag="precision" value="6"/>
                                <UML:TaggedValue tag="position" value="2"/>
                                <UML:TaggedValue tag="lowerBound" value="0"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("FEE").parse(
                "timestamp-precision.xmi",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var table = schema.findTable("EVENT_LOG").orElseThrow();
        var timestamp = table.findColumn("OCCURRED_AT").orElseThrow().dataType();
        assertEquals("TIMESTAMP", timestamp.name().normalized());
        assertEquals(6, timestamp.precision());
        assertNull(timestamp.scale());

        var timestampWithTimeZone = table.findColumn("SOURCE_OCCURRED_AT").orElseThrow().dataType();
        assertEquals("TIMESTAMP_WITH_TIME_ZONE", timestampWithTimeZone.name().normalized());
        assertEquals(6, timestampWithTimeZone.precision());
        assertNull(timestampWithTimeZone.scale());
    }


    @Test
    void shouldIgnoreEaIndexNameAndApplyCrossServiceFormula() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="PATTERN_OPERATION_DETAIL" xmi.id="TABLE_1">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="SUB_OPERATION_ID">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="NUMBER"/>
                                <UML:TaggedValue tag="precision" value="19"/>
                                <UML:TaggedValue tag="scale" value="0"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="IX_PATTERN_OPERATION__2">
                              <UML:ModelElement.stereotype><UML:Stereotype name="index"/></UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="SUB_OPERATION_ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("PDL").parse(
                "repeated-underscore.xml",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var table = schema.findTable("PATTERN_OPERATION_DETAIL").orElseThrow();
        assertEquals(1, table.indexes().size());
        assertEquals("IX_PATTERN_OPERATION_DETAIL_SUB_OPERATION_ID", table.indexes().getFirst().name().value());
    }


    @Test
    void shouldNotInferIdentityForSharedPrimaryKeyForeignKeyWhenEnabled() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="LOAN_ELIGIBILITY_EXTENSION" xmi.id="TABLE_EXT">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="ELIGIBILITY_RULE_ID">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="NUMBER"/>
                                <UML:TaggedValue tag="precision" value="19"/>
                                <UML:TaggedValue tag="scale" value="0"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_LOAN_ELIGIBILITY_EXTENSION">
                              <UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="ELIGIBILITY_RULE_ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                            <UML:Operation name="FK_LOAN_ELIGIBILITY_EXTENSION">
                              <UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="ELIGIBILITY_RULE_ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("PDL", true).parse(
                "shared-pk-fk.xml", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var column = schema.findTable("LOAN_ELIGIBILITY_EXTENSION").orElseThrow()
                .findColumn("ELIGIBILITY_RULE_ID").orElseThrow();
        assertFalse(column.identity());
        assertFalse(column.nullable());
    }

    @Test
    void shouldRecoverLengthSemanticsFromStyleExWhenExplicitTagIsMissing() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="LENGTH_SEMANTICS_SAMPLE" xmi.id="TABLE_1">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="CHAR_VALUE">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="VARCHAR2"/>
                                <UML:TaggedValue tag="length" value="30"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="styleex" value="volatile=0;LengthType=Char;union=0;"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Attribute name="BYTE_VALUE">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="VARCHAR2"/>
                                <UML:TaggedValue tag="length" value="40"/>
                                <UML:TaggedValue tag="position" value="1"/>
                                <UML:TaggedValue tag="styleex" value="LengthType=Byte;VisibleAttributeDetail=0;"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("FEE").parse(
                "length-semantics-styleex.xmi",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var table = schema.findTable("LENGTH_SEMANTICS_SAMPLE").orElseThrow();
        assertEquals(LengthSemantics.CHAR,
                table.findColumn("CHAR_VALUE").orElseThrow().dataType().lengthSemantics());
        assertEquals(LengthSemantics.BYTE,
                table.findColumn("BYTE_VALUE").orElseThrow().dataType().lengthSemantics());
    }

    @Test
    void shouldPreferExplicitLengthTypeOverStyleExFallback() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="LENGTH_SEMANTICS_PRECEDENCE" xmi.id="TABLE_1">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="VALUE_COL">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="VARCHAR2"/>
                                <UML:TaggedValue tag="length" value="50"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="LengthType" value="BYTE"/>
                                <UML:TaggedValue tag="styleex" value="LengthType=Char;"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("FEE").parse(
                "length-semantics-precedence.xmi",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(LengthSemantics.BYTE,
                schema.findTable("LENGTH_SEMANTICS_PRECEDENCE").orElseThrow()
                        .findColumn("VALUE_COL").orElseThrow()
                        .dataType().lengthSemantics());
    }


    @Test
    void shouldParseCompatibleXmiWithDirectStereotypeAttributesAndExplicitFkTags() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="Compatible Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="PARENT_TABLE" xmi.id="TABLE_PARENT" stereotype="table">
                          <UML:Classifier.feature>
                            <UML:Attribute name="PARENT_ID" stereotype="column" type="NUMBER(8,0)">
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="notnull" value="true"/>
                                <UML:TaggedValue tag="position" value="0"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Attribute name="PARENT_CODE" stereotype="column" type="VARCHAR2(30)">
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="notnull" value="false"/>
                                <UML:TaggedValue tag="position" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_PARENT_TABLE" stereotype="PK">
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="PARENT_ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                        <UML:Class name="CHILD_TABLE" xmi.id="TABLE_CHILD" stereotype="table">
                          <UML:Classifier.feature>
                            <UML:Attribute name="CHILD_ID" stereotype="column" type="NUMBER(10,0)">
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="notnull" value="true"/>
                                <UML:TaggedValue tag="position" value="0"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Attribute name="PARENT_ID" stereotype="column" type="NUMBER(8,0)">
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="notnull" value="true"/>
                                <UML:TaggedValue tag="position" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_CHILD_TABLE" stereotype="PK">
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="CHILD_ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                            <UML:Operation name="FK_CHILD_PARENT" stereotype="FK">
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="PARENT_ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                        <UML:Association name="FK_CHILD_PARENT" xmi.id="ASSOC_1" stereotype="FK">
                          <UML:Association.connection>
                            <UML:AssociationEnd xmi.id="END_CHILD" type="TABLE_CHILD" name="CHILD_TABLE"/>
                            <UML:AssociationEnd xmi.id="END_PARENT" type="TABLE_PARENT" name="PARENT_TABLE"/>
                          </UML:Association.connection>
                          <UML:ModelElement.taggedValue>
                            <UML:TaggedValue tag="fk_child_column" value="PARENT_ID"/>
                            <UML:TaggedValue tag="fk_parent_column" value="PARENT_ID"/>
                          </UML:ModelElement.taggedValue>
                        </UML:Association>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("COL").parse(
                "compatible-direct-stereotype.xmi",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, schema.tables().size());
        assertEquals("EXPLICIT_ONLY", schema.metadata().get("source.eaPrimaryKeyIdentityPolicy"));
        var parent = schema.findTable("PARENT_TABLE").orElseThrow();
        assertFalse(parent.findColumn("PARENT_ID").orElseThrow().identity());
        assertFalse(parent.findColumn("PARENT_ID").orElseThrow().nullable());
        assertTrue(parent.findColumn("PARENT_CODE").orElseThrow().nullable());
        assertEquals(8, parent.findColumn("PARENT_ID").orElseThrow().dataType().precision());
        assertEquals(0, parent.findColumn("PARENT_ID").orElseThrow().dataType().scale());
        assertEquals(30, parent.findColumn("PARENT_CODE").orElseThrow().dataType().length());

        var child = schema.findTable("CHILD_TABLE").orElseThrow();
        assertFalse(child.findColumn("CHILD_ID").orElseThrow().identity());
        assertEquals(1, child.foreignKeys().size());
        var foreignKey = child.foreignKeys().getFirst();
        assertEquals("FK_CHILD_TABLE_PARENT_ID", foreignKey.name().value());
        assertEquals("COL.PARENT_TABLE", foreignKey.referencedTable().toString());
        assertEquals("PARENT_ID", foreignKey.columns().getFirst().value());
        assertEquals("PARENT_ID", foreignKey.referencedColumns().getFirst().value());
    }

    @Test
    void shouldParseNativeEaXmi21UsingExtensionMetadata() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://schema.omg.org/spec/XMI/2.1"
                         xmlns:uml="http://schema.omg.org/spec/UML/2.1"
                         xmi:version="2.1">
                  <xmi:Documentation exporter="Enterprise Architect" exporterVersion="6.5"/>
                  <uml:Model name="EA_Model" xmi:type="uml:Model" xmi:id="MODEL_1">
                    <packagedElement name="PST" xmi:type="uml:Package" xmi:id="PKG_PST">
                      <packagedElement name="PARENT_TABLE" xmi:type="uml:Class" xmi:id="T_PARENT">
                        <ownedAttribute name="PARENT_ID" xmi:type="uml:Property" xmi:id="C_PARENT_ID">
                          <lowerValue value="1" xmi:type="uml:LiteralInteger" xmi:id="L1"/>
                          <upperValue value="1" xmi:type="uml:LiteralUnlimitedNatural" xmi:id="U1"/>
                        </ownedAttribute>
                        <ownedAttribute name="PARENT_CODE" xmi:type="uml:Property" xmi:id="C_PARENT_CODE">
                          <lowerValue value="0" xmi:type="uml:LiteralInteger" xmi:id="L2"/>
                          <upperValue value="1" xmi:type="uml:LiteralUnlimitedNatural" xmi:id="U2"/>
                        </ownedAttribute>
                        <ownedOperation name="primary_key_for_parent_table" xmi:type="uml:Operation" xmi:id="O_PARENT_PK">
                          <ownedParameter name="PARENT_ID" direction="in" xmi:type="uml:Parameter" xmi:id="P_PARENT_PK"/>
                        </ownedOperation>
                      </packagedElement>
                      <packagedElement name="CHILD_TABLE" xmi:type="uml:Class" xmi:id="T_CHILD">
                        <ownedAttribute name="CHILD_ID" xmi:type="uml:Property" xmi:id="C_CHILD_ID">
                          <lowerValue value="1" xmi:type="uml:LiteralInteger" xmi:id="L3"/>
                          <upperValue value="1" xmi:type="uml:LiteralUnlimitedNatural" xmi:id="U3"/>
                        </ownedAttribute>
                        <ownedAttribute name="PARENT_ID" xmi:type="uml:Property" xmi:id="C_CHILD_PARENT">
                          <lowerValue value="0" xmi:type="uml:LiteralInteger" xmi:id="L4"/>
                          <upperValue value="1" xmi:type="uml:LiteralUnlimitedNatural" xmi:id="U4"/>
                        </ownedAttribute>
                        <ownedAttribute name="CREATED_AT" xmi:type="uml:Property" xmi:id="C_CREATED_AT">
                          <lowerValue value="0" xmi:type="uml:LiteralInteger" xmi:id="L5"/>
                          <upperValue value="1" xmi:type="uml:LiteralUnlimitedNatural" xmi:id="U5"/>
                        </ownedAttribute>
                        <ownedOperation name="primary_key_for_child_table" xmi:type="uml:Operation" xmi:id="O_CHILD_PK">
                          <ownedParameter name="CHILD_ID" direction="in" xmi:type="uml:Parameter" xmi:id="P_CHILD_PK"/>
                        </ownedOperation>
                        <ownedOperation name="foreign_key_for_child_table_parent_id" xmi:type="uml:Operation" xmi:id="O_CHILD_FK">
                          <ownedParameter name="PARENT_ID" direction="in" xmi:type="uml:Parameter" xmi:id="P_CHILD_FK"/>
                        </ownedOperation>
                      </packagedElement>
                      <packagedElement name="references" xmi:type="uml:Association" xmi:id="A_FK"/>
                    </packagedElement>
                  </uml:Model>
                  <xmi:Extension extender="Enterprise Architect">
                    <elements>
                      <element name="PARENT_TABLE" xmi:idref="T_PARENT" xmi:type="uml:Class">
                        <properties stereotype="table" documentation="Schema=PST; parent"/>
                        <tags><tag name="Owner" value="PST"/></tags>
                        <attributes>
                          <attribute name="PARENT_ID" xmi:idref="C_PARENT_ID">
                            <properties type="NUMBER(19,0)" precision="0" length="0"/>
                            <containment position="0"/><stereotype stereotype="column"/><bounds lower="1" upper="1"/>
                          </attribute>
                          <attribute name="PARENT_CODE" xmi:idref="C_PARENT_CODE">
                            <properties type="VARCHAR2(160 CHAR)" precision="0" length="0"/>
                            <containment position="1"/><stereotype stereotype="column"/><bounds lower="0" upper="1"/>
                          </attribute>
                        </attributes>
                        <methods>
                          <method name="primary_key_for_parent_table" xmi:idref="O_PARENT_PK">
                            <properties stereotype="PK"/><stereotype stereotype="PK"/>
                            <parameters><parameter name="PARENT_ID" xmi:idref="P_PARENT_PK"><properties pos="0"/></parameter></parameters>
                          </method>
                        </methods>
                      </element>
                      <element name="CHILD_TABLE" xmi:idref="T_CHILD" xmi:type="uml:Class">
                        <properties stereotype="table" documentation="Schema=PST; child"/>
                        <tags><tag name="Owner" value="PST"/></tags>
                        <attributes>
                          <attribute name="CHILD_ID" xmi:idref="C_CHILD_ID">
                            <properties type="NUMBER(19,0)" precision="0" length="0"/>
                            <containment position="0"/><stereotype stereotype="column"/><bounds lower="1" upper="1"/>
                          </attribute>
                          <attribute name="PARENT_ID" xmi:idref="C_CHILD_PARENT">
                            <properties type="NUMBER(19,0)" precision="0" length="0"/>
                            <containment position="1"/><stereotype stereotype="column"/><bounds lower="0" upper="1"/>
                          </attribute>
                          <attribute name="CREATED_AT" xmi:idref="C_CREATED_AT">
                            <properties type="TIMESTAMP(6) WITH TIME ZONE" precision="0" length="0"/>
                            <containment position="2"/><stereotype stereotype="column"/><bounds lower="0" upper="1"/>
                          </attribute>
                        </attributes>
                        <methods>
                          <method name="primary_key_for_child_table" xmi:idref="O_CHILD_PK">
                            <properties stereotype="PK"/><stereotype stereotype="PK"/>
                            <parameters><parameter name="CHILD_ID" xmi:idref="P_CHILD_PK"><properties pos="0"/></parameter></parameters>
                          </method>
                          <method name="foreign_key_for_child_table_parent_id" xmi:idref="O_CHILD_FK">
                            <properties stereotype="FK"/><stereotype stereotype="FK"/>
                            <tags><tag name="ReferencedTable" value="PST.PARENT_TABLE"/></tags>
                            <parameters><parameter name="PARENT_ID" xmi:idref="P_CHILD_FK"><properties pos="0"/></parameter></parameters>
                          </method>
                        </methods>
                      </element>
                    </elements>
                    <connectors>
                      <connector xmi:idref="A_FK">
                        <source xmi:idref="T_CHILD"><model type="Class" name="CHILD_TABLE"/></source>
                        <target xmi:idref="T_PARENT"><model type="Class" name="PARENT_TABLE"/></target>
                        <properties name="references" ea_type="Association" direction="Source -&gt; Destination" stereotype="FK"/>
                        <tags><tag name="ForeignKeyColumn" value="PARENT_ID"/></tags>
                      </connector>
                    </connectors>
                  </xmi:Extension>
                </xmi:XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("COL").parse(
                "native-ea-xmi21.xmi",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, schema.tables().size());
        assertEquals("Enterprise Architect", schema.metadata().get("source.eaExporter"));
        assertEquals("6.5", schema.metadata().get("source.eaExporterVersion"));
        assertEquals("XMI_2_1", schema.metadata().get("source.eaXmiDialect"));
        assertEquals("XML", schema.metadata().get("source.eaSchemaResolution"));

        var parent = schema.findTable("PARENT_TABLE").orElseThrow();
        assertEquals("PST.PARENT_TABLE", parent.qualifiedName().toString());
        assertEquals(160, parent.findColumn("PARENT_CODE").orElseThrow().dataType().length());
        assertEquals(LengthSemantics.CHAR,
                parent.findColumn("PARENT_CODE").orElseThrow().dataType().lengthSemantics());

        var child = schema.findTable("CHILD_TABLE").orElseThrow();
        assertEquals("TIMESTAMP_WITH_TIME_ZONE",
                child.findColumn("CREATED_AT").orElseThrow().dataType().name().normalized());
        assertEquals(6, child.findColumn("CREATED_AT").orElseThrow().dataType().precision());
        assertEquals(1, child.foreignKeys().size());
        var foreignKey = child.foreignKeys().getFirst();
        assertEquals("FK_CHILD_TABLE_PARENT_ID", foreignKey.name().value());
        assertEquals("PST.PARENT_TABLE", foreignKey.referencedTable().toString());
        assertEquals("PARENT_ID", foreignKey.columns().getFirst().value());
        assertEquals("PARENT_ID", foreignKey.referencedColumns().getFirst().value());
        assertTrue(schema.metadata().get("recovery.warnings")
                .contains("EA_XMI21_FK_REFERENCED_COLUMNS_RECOVERED_FROM_TARGET_PK"));
    }

    @Test
    void shouldHonorExplicitIdentityAttributeWithoutPrimaryKeyInference() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="Compatible Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="ACCOUNT" xmi.id="TABLE_1" stereotype="table">
                          <UML:Classifier.feature>
                            <UML:Attribute name="ACCOUNT_ID" stereotype="column" type="NUMBER(12,0)" identity="true">
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="notnull" value="true"/>
                                <UML:TaggedValue tag="position" value="0"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_ACCOUNT" stereotype="PK">
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="ACCOUNT_ID" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("COL").parse(
                "explicit-identity-attribute.xmi",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("EXPLICIT_ONLY", schema.metadata().get("source.eaPrimaryKeyIdentityPolicy"));
        assertTrue(schema.findTable("ACCOUNT").orElseThrow()
                .findColumn("ACCOUNT_ID").orElseThrow().identity());
    }

}
