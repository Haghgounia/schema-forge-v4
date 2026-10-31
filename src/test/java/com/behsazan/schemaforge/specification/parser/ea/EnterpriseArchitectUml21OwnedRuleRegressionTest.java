package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XMI 2.1 physical columns can be carried in Extension/attributes, and SQL
 * constraints in core ownedRule. Neither may be dropped during import.
 */
class EnterpriseArchitectUml21OwnedRuleRegressionTest {
    private static final String PORTABLE_SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xmi:XMI xmlns:xmi="http://schema.omg.org/spec/XMI/2.1"
                     xmlns:uml="http://schema.omg.org/spec/UML/2.1" xmi:version="2.1">
              <uml:Model xmi:id="MODEL" name="IAM">
                <packagedElement xmi:type="uml:Class" xmi:id="T1" name="REF_DOMAIN_TYPES">
                  <ownedAttribute xmi:type="uml:Property" xmi:id="C1" name="DOMAIN_TYPE_CODE">
                    <lowerValue xmi:type="uml:LiteralInteger" value="1"/>
                  </ownedAttribute>
                  <ownedAttribute xmi:type="uml:Property" xmi:id="C2" name="IS_ACTIVE">
                    <lowerValue xmi:type="uml:LiteralInteger" value="1"/>
                    <defaultValue xmi:type="uml:LiteralString" value="'Y'"/>
                  </ownedAttribute>
                  <ownedRule xmi:type="uml:Constraint" name="PK_REF_DOMAIN_TYPES">
                    <specification xmi:type="uml:OpaqueExpression"><body>PK: DOMAIN_TYPE_CODE</body></specification>
                  </ownedRule>
                  <ownedRule xmi:type="uml:Constraint" name="CK_REF_ACTIVE">
                    <specification xmi:type="uml:OpaqueExpression"><body>CHECK: CHECK (IS_ACTIVE IN ('Y','N'))</body></specification>
                  </ownedRule>
                </packagedElement>
                <packagedElement xmi:type="uml:Class" xmi:id="T2" name="SECURITY_DOMAINS">
                  <ownedAttribute xmi:type="uml:Property" xmi:id="C3" name="SECURITY_DOMAIN_ID">
                    <lowerValue xmi:type="uml:LiteralInteger" value="1"/>
                    <defaultValue xmi:type="uml:LiteralString" value="IAM.SEQ_SECURITY_DOMAIN.NEXTVAL"/>
                  </ownedAttribute>
                  <ownedAttribute xmi:type="uml:Property" xmi:id="C4" name="DOMAIN_TYPE_CODE">
                    <lowerValue xmi:type="uml:LiteralInteger" value="1"/>
                  </ownedAttribute>
                  <ownedRule xmi:type="uml:Constraint" name="PK_SECURITY_DOMAINS">
                    <specification xmi:type="uml:OpaqueExpression"><body>PK: PRIMARY KEY (SECURITY_DOMAIN_ID)</body></specification>
                  </ownedRule>
                  <ownedRule xmi:type="uml:Constraint" name="UQ_SECURITY_DOMAINS_PAIR">
                    <specification xmi:type="uml:OpaqueExpression"><body>UQ: SECURITY_DOMAIN_ID, DOMAIN_TYPE_CODE</body></specification>
                  </ownedRule>
                  <ownedRule xmi:type="uml:Constraint" name="FK_SECURITY_DOM_CTX_">
                    <specification xmi:type="uml:OpaqueExpression"><body>FK: DOMAIN_TYPE_CODE</body></specification>
                  </ownedRule>
                </packagedElement>
                <packagedElement xmi:type="uml:Association" xmi:id="A1" name="FK_SECURITY_DOM_CTX_"/>
              </uml:Model>
              <xmi:Extension extender="Enterprise Architect">
                <elements>
                  <element xmi:idref="T1" name="REF_DOMAIN_TYPES">
                    <properties stereotype="table"/>
                    <tags><tag name="Schema" value="IAM"/></tags>
                  </element>
                  <element xmi:idref="T2" name="SECURITY_DOMAINS">
                    <properties stereotype="table"/>
                    <tags><tag name="Schema" value="IAM"/></tags>
                  </element>
                  <attribute xmi:idref="C1" name="DOMAIN_TYPE_CODE">
                    <properties type="VARCHAR2(40 CHAR)"/>
                    <tags><tag name="Nullable" value="No"/></tags>
                  </attribute>
                  <attribute xmi:idref="C2" name="IS_ACTIVE">
                    <properties type="CHAR(1 CHAR)"/>
                    <initial value="'Y'"/>
                  </attribute>
                  <attribute xmi:idref="C3" name="SECURITY_DOMAIN_ID">
                    <properties type="NUMBER(19,0)"/>
                  </attribute>
                  <attribute xmi:idref="C4" name="DOMAIN_TYPE_CODE">
                    <properties type="VARCHAR2(40 CHAR)"/>
                  </attribute>
                </elements>
                <connectors>
                  <connector xmi:idref="A1">
                    <source xmi:idref="T2"><model name="SECURITY_DOMAINS"/></source>
                    <target xmi:idref="T1"><model name="REF_DOMAIN_TYPES"/></target>
                    <properties name="FK_SECURITY_DOM_CTX_"/>
                    <documentation value="SECURITY_DOMAINS.DOMAIN_TYPE_CODE -&gt; REF_DOMAIN_TYPES.DOMAIN_TYPE_CODE"/>
                  </connector>
                </connectors>
              </xmi:Extension>
            </xmi:XMI>
            """;

    private static DatabaseSchema parse(String xml) {
        return new EnterpriseArchitectXmlParser("IAM").parse(
                "iam-mini.xmi", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "IAM");
    }

    @Test
    void importsExtensionColumnsOwnedRulesAndShortFormForeignKeyWithoutLoss() {
        var schema = parse(PORTABLE_SAMPLE);
        assertEquals(2, schema.tables().size());
        assertEquals(4, schema.tables().stream().mapToInt(t -> t.columns().size()).sum());
        assertEquals("XMI_2_1", schema.metadata().get("source.eaXmiDialect"));
        var parent = schema.findTable("REF_DOMAIN_TYPES").orElseThrow();
        assertEquals(2, parent.columns().size());
        assertEquals("PK_REF_DOMAIN_TYPES", parent.primaryKey().orElseThrow().name().value());
        assertEquals(1, parent.checkConstraints().size());
        assertEquals("IS_ACTIVE IN ('Y','N')", parent.checkConstraints().getFirst().expression());
        assertEquals(40, parent.findColumn("DOMAIN_TYPE_CODE").orElseThrow().dataType().length().intValue());
        assertEquals(LengthSemantics.CHAR,
                parent.findColumn("DOMAIN_TYPE_CODE").orElseThrow().dataType().lengthSemantics());
        assertFalse(parent.findColumn("DOMAIN_TYPE_CODE").orElseThrow().nullable());
        assertEquals("'Y'", parent.findColumn("IS_ACTIVE").orElseThrow().defaultValue().expression());
        var child = schema.findTable("SECURITY_DOMAINS").orElseThrow();
        assertEquals(1, child.uniqueKeys().size());
        assertEquals(1, child.foreignKeys().size());
        assertTrue(child.findColumn("SECURITY_DOMAIN_ID").orElseThrow().identity());
        assertEquals("IAM.SEQ_SECURITY_DOMAIN.NEXTVAL",
                child.findColumn("SECURITY_DOMAIN_ID").orElseThrow().defaultValue().expression());
        assertFalse(parent.findColumn("DOMAIN_TYPE_CODE").orElseThrow().identity());
        assertTrue(child.foreignKeys().getFirst().name().value().startsWith("FK_SECURITY_DOMAINS_"));
        assertEquals("IAM.REF_DOMAIN_TYPES", child.foreignKeys().getFirst().referencedTable().toString());
        assertEquals("DOMAIN_TYPE_CODE", child.foreignKeys().getFirst().referencedColumns().getFirst().value());
    }

    @Test
    void rejectsConflictingForeignKeyConnectorEvidence() {
        String corrupt = PORTABLE_SAMPLE.replace(
                "REF_DOMAIN_TYPES.DOMAIN_TYPE_CODE\"/>", "REF_DOMAIN_TYPES.IS_ACTIVE\"/>");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> parse(corrupt));
        assertTrue(exception.getMessage().contains("FK"));
    }

    @Test
    void rejectsMissingPhysicalTypeRatherThanGuessingVarchar() {
        String corrupt = PORTABLE_SAMPLE.replace(
                "<properties type=\"VARCHAR2(40 CHAR)\"/>", "<properties/>");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> parse(corrupt));
        assertTrue(exception.getMessage().contains("missing type"));
    }

    @Test
    void rejectsMissingConnectorRatherThanSilentlyDroppingFk() {
        String corrupt = PORTABLE_SAMPLE.replace(
                "<properties name=\"FK_SECURITY_DOM_CTX_\"/>",
                "<properties name=\"UNRELATED_CONNECTOR\"/>");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> parse(corrupt));
        assertTrue(exception.getMessage().contains("missing physical constraints"));
    }

    @Test
    void rendersNativeMySqlIdentityFromExplicitSequenceDefaultButKeepsOracleSource() {
        var schema = parse(PORTABLE_SAMPLE);
        String mysql = new DdlGenerator(DialectFactory.create(DatabasePlatform.MYSQL)).generate(schema);
        assertTrue(mysql.contains("AUTO_INCREMENT"));
        assertFalse(mysql.contains("DEFAULT IAM.SEQ_SECURITY_DOMAIN.NEXTVAL"));
        String oracle = new DdlGenerator(DialectFactory.create(DatabasePlatform.ORACLE)).generate(schema);
        assertTrue(oracle.contains("IAM.SEQ_SECURITY_DOMAIN.NEXTVAL"));
    }


}
