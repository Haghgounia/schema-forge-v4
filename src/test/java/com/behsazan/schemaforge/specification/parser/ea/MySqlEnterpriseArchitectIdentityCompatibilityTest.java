package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects MySQL rendering of the representative EA NUMBER(19) AutoNum model. */
class MySqlEnterpriseArchitectIdentityCompatibilityTest {

    @Test
    void shouldRenderNumber19AutoNumAndRelatedInternalForeignKeyAsUnsignedBigInt() throws Exception {
        Path source = Path.of("src/test/resources/Party_14050514.xml");
        DatabaseSchema schema;
        try (var input = Files.newInputStream(source)) {
            schema = new EnterpriseArchitectXmlParser("DPS").parse(source.getFileName().toString(), input);
        }

        var classification = schema.findTable("PARTY_CLASSIFICATION").orElseThrow();
        assertTrue(classification.findColumn("PARTY_CLASSIFICATION_ID").orElseThrow().identity());
        assertTrue(schema.findTable("PARTY").orElseThrow().findColumn("PARTY_ID").orElseThrow().identity());

        DatabaseSchema singleTable = DatabaseSchema.builder(schema.name().value())
                .addTable(classification)
                .build();

        String sql = new DdlGenerator(new MySqlDialect(), schema).generate(singleTable);

        assertTrue(sql.contains("`PARTY_CLASSIFICATION_ID` BIGINT UNSIGNED AUTO_INCREMENT NOT NULL"));
        assertTrue(sql.contains("`PARTY_ID` BIGINT UNSIGNED NOT NULL"));
        assertTrue(sql.contains("NUMBER(19,0) identity -> BIGINT UNSIGNED"));
        assertTrue(sql.contains("FK type adaptation: NUMBER(19,0) -> BIGINT UNSIGNED"));
    }
    @Test
    void shouldNotTreatCharacterPrimaryKeyAsAutoIncrementWhenPrimaryKeyInferenceIsEnabled() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MODEL_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="REF_RECOMMENDATION_STATUS" xmi.id="TABLE_STATUS">
                          <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                          <UML:Classifier.feature>
                            <UML:Attribute name="RECOMMENDATION_STATUS_CODE">
                              <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="type" value="VARCHAR2"/>
                                <UML:TaggedValue tag="length" value="50"/>
                                <UML:TaggedValue tag="position" value="0"/>
                                <UML:TaggedValue tag="lowerBound" value="1"/>
                              </UML:ModelElement.taggedValue>
                            </UML:Attribute>
                            <UML:Operation name="PK_RECOMMENDATION_STATUS">
                              <UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype>
                              <UML:BehavioralFeature.parameter>
                                <UML:Parameter name="RECOMMENDATION_STATUS_CODE" kind="in"/>
                              </UML:BehavioralFeature.parameter>
                            </UML:Operation>
                          </UML:Classifier.feature>
                        </UML:Class>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                  </XMI.content>
                </XMI>
                """;

        DatabaseSchema schema = new EnterpriseArchitectXmlParser("DPS", true).parse(
                "reference.xml", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        String sql = new DdlGenerator(new MySqlDialect(), schema).generate(schema);

        assertTrue(sql.contains("`RECOMMENDATION_STATUS_CODE` VARCHAR(50) NOT NULL"));
        assertTrue(sql.contains("PRIMARY KEY (`RECOMMENDATION_STATUS_CODE`)"));
        assertTrue(!sql.contains("AUTO_INCREMENT"));
    }

}
