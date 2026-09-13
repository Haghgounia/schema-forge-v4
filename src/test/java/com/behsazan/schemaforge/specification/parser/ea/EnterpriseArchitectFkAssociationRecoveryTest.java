package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.generation.issue.SqlIssueCatalog;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for real EA XMI exports where the source AssociationEnd
 * role name does not match the FK UML:Operation name even though table ids and
 * source/target column mappings are complete.
 */
class EnterpriseArchitectFkAssociationRecoveryTest {

    @Test
    void shouldRecoverForeignKeyWhenEaAssociationEndNameDiffersFromFkOperationName() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="DEPOSIT_PRODUCT_TERM_RULE" xmi.id="TABLE_PARENT">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="TERM_RULE_ID">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="NUMBER"/>
                                <UML:TaggedValue tag="precision" value="19"/>
                                <UML:TaggedValue tag="scale" value="0"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_DEPOSIT_PRODUCT_TERM_RULE">
                              <UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="TERM_RULE_ID" kind="in">
                                  <UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue>
                                </UML:Parameter>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                        <UML:Class name="DEPOSIT_PRODUCT_ALLOWED_TERM" xmi.id="TABLE_CHILD">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="TERM_RULE_ID">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="NUMBER"/>
                                <UML:TaggedValue tag="precision" value="19"/>
                                <UML:TaggedValue tag="scale" value="0"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="FK_DEPOSIT_PRODUCT_AL_1">
                              <UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="TERM_RULE_ID" kind="in">
                                  <UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue>
                                </UML:Parameter>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                        <UML:Association name="(TERM_RULE_ID = TERM_RULE_ID)" xmi.id="ASSOC_1">
                          <UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype>
                          <UML:ModelElement.taggedValue>
                            <UML:TaggedValue tag="ea_sourceName" value="DEPOSIT_PRODUCT_ALLOWED_TERM"/>
                            <UML:TaggedValue tag="ea_targetName" value="DEPOSIT_PRODUCT_TERM_RULE"/>
                          </UML:ModelElement.taggedValue>
                          <UML:Association.connection>
                            <UML:AssociationEnd name="FK_DEPOSIT_PRODUCT_AL_32" type="TABLE_CHILD">
                              <UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="source"/></UML:ModelElement.taggedValue>
                            </UML:AssociationEnd>
                            <UML:AssociationEnd name="PK_DEPOSIT_PRODUCT_TERM_RULE" type="TABLE_PARENT">
                              <UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="target"/></UML:ModelElement.taggedValue>
                            </UML:AssociationEnd>
                          </UML:Association.connection>
                        </UML:Association>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("PDL").parse(
                "ea-fk-name-mismatch.xml",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var child = schema.findTable("DEPOSIT_PRODUCT_ALLOWED_TERM").orElseThrow();
        assertEquals(1, child.foreignKeys().size());
        var foreignKey = child.foreignKeys().getFirst();
        assertEquals("FK_DEPOSIT_PRODUCT_ALLOWED_TERM_TERM_RULE_ID", foreignKey.name().value());
        assertEquals("TERM_RULE_ID", foreignKey.columns().getFirst().value());
        assertEquals("PDL.DEPOSIT_PRODUCT_TERM_RULE", foreignKey.referencedTable().toString());
        assertEquals("TERM_RULE_ID", foreignKey.referencedColumns().getFirst().value());
        assertTrue(schema.metadata().get("recovery.warnings")
                .contains("EA_FK_ASSOCIATION_OPERATION_MISMATCH_RECOVERED"));
    }
    @Test
    void shouldFailClosedWhenOneFkOperationHasMultipleDistinctAssociations() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="PARENT_A" xmi.id="PARENT_A">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="0"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                            <UML:Operation name="PK_PARENT_A"><UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                        <UML:Class name="PARENT_B" xmi.id="PARENT_B">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="0"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                            <UML:Operation name="PK_PARENT_B"><UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                        <UML:Class name="CHILD" xmi.id="CHILD">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="PARENT_ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="0"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                            <UML:Operation name="FK_CHILD_PARENT"><UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="PARENT_ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                        <UML:Association name="(PARENT_ID = ID)" xmi.id="ASSOC_A">
                          <UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype>
                          <UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_sourceName" value="CHILD"/><UML:TaggedValue tag="ea_targetName" value="PARENT_A"/></UML:ModelElement.taggedValue>
                          <UML:Association.connection><UML:AssociationEnd name="FK_CHILD_PARENT" type="CHILD"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="source"/></UML:ModelElement.taggedValue></UML:AssociationEnd><UML:AssociationEnd name="PK_PARENT_A" type="PARENT_A"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="target"/></UML:ModelElement.taggedValue></UML:AssociationEnd></UML:Association.connection>
                        </UML:Association>
                        <UML:Association name="(PARENT_ID = ID)" xmi.id="ASSOC_B">
                          <UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype>
                          <UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_sourceName" value="CHILD"/><UML:TaggedValue tag="ea_targetName" value="PARENT_B"/></UML:ModelElement.taggedValue>
                          <UML:Association.connection><UML:AssociationEnd name="FK_CHILD_PARENT" type="CHILD"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="source"/></UML:ModelElement.taggedValue></UML:AssociationEnd><UML:AssociationEnd name="PK_PARENT_B" type="PARENT_B"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="target"/></UML:ModelElement.taggedValue></UML:AssociationEnd></UML:Association.connection>
                        </UML:Association>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        var schema = new EnterpriseArchitectXmlParser("TST").parse(
                "ea-fk-conflict.xml",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var child = schema.findTable("CHILD").orElseThrow();
        assertEquals(0, child.foreignKeys().size());
        String warnings = schema.metadata().get("recovery.warnings");
        assertTrue(warnings.contains("EA_FK_ASSOCIATION_CONFLICT"));
        assertTrue(warnings.contains("ASSOC_A"));
        assertTrue(warnings.contains("ASSOC_B"));

        var issues = SqlIssueCatalog.from(schema, new ValidationReport(true, java.util.List.of())).all();
        assertTrue(issues.stream().anyMatch(issue ->
                "ERROR".equals(issue.severity())
                        && "EA_FK_ASSOCIATION_CONFLICT".equals(issue.code())));
    }

}
