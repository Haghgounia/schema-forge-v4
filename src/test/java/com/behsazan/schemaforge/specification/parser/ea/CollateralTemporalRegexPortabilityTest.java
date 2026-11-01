package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlExpressionMapper;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerExpressionMapper;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression from the actual COL model exported by EA XMI 1.1 in Nov 2026. */
class CollateralTemporalRegexPortabilityTest {
    private static final String XMI = """
            <?xml version="1.0" encoding="UTF-8"?>
            <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
              <XMI.content>
                <UML:Model name="EA Model" xmi.id="M1">
                  <UML:Namespace.ownedElement>
                    <UML:Class name="REF_CURRENCY" xmi.id="T1">
                      <UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype>
                      <UML:Classifier.feature>
                        <UML:Attribute name="CURRENCY_CODE">
                          <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                          <UML:ModelElement.taggedValue>
                            <UML:TaggedValue tag="type" value="VARCHAR2"/>
                            <UML:TaggedValue tag="length" value="3"/>
                            <UML:TaggedValue tag="lowerBound" value="1"/>
                          </UML:ModelElement.taggedValue>
                        </UML:Attribute>
                        <UML:Attribute name="CREATED_AT">
                          <UML:Attribute.initialValue><UML:Expression body="SYSTIMESTAMP"/></UML:Attribute.initialValue>
                          <UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype>
                          <UML:ModelElement.taggedValue>
                            <UML:TaggedValue tag="type" value="TIMESTAMP"/>
                            <UML:TaggedValue tag="length" value="6"/>
                            <UML:TaggedValue tag="precision" value="0"/>
                            <UML:TaggedValue tag="scale" value="0"/>
                            <UML:TaggedValue tag="lowerBound" value="1"/>
                          </UML:ModelElement.taggedValue>
                        </UML:Attribute>
                        <UML:Operation name="CK_REF_CURRENCY_CODE">
                          <UML:ModelElement.stereotype><UML:Stereotype name="check"/></UML:ModelElement.stereotype>
                          <UML:ModelElement.taggedValue>
                            <UML:TaggedValue tag="code" value="CHECK (REGEXP_LIKE(CURRENCY_CODE, '^[A-Z]{3}$'))"/>
                          </UML:ModelElement.taggedValue>
                        </UML:Operation>
                      </UML:Classifier.feature>
                    </UML:Class>
                  </UML:Namespace.ownedElement>
                </UML:Model>
              </XMI.content>
            </XMI>
            """;

    private static com.behsazan.schemaforge.domain.model.DatabaseSchema parse(String input) {
        return new EnterpriseArchitectXmlParser("COL").parse("collateral-test.xmi",
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void ea11LengthSixIsTemporalPrecisionNotCharacterLength() {
        var column = parse(XMI).findTable("REF_CURRENCY").orElseThrow()
                .findColumn("CREATED_AT").orElseThrow();
        assertEquals("TIMESTAMP", column.dataType().name().value());
        assertEquals(6, column.dataType().precision());
        assertEquals(null, column.dataType().length());
        assertEquals("SYSTIMESTAMP", column.defaultValue().expression());
    }

    @Test
    void bothMysqlTargetsPreserveSixDigitColumnAndDefault() {
        var schema = parse(XMI);
        for (var dialect : java.util.List.of(new MySqlDialect(), new MariaDbDialect())) {
            String sql = new DdlGenerator(dialect).generate(schema);
            assertTrue(sql.contains("`CREATED_AT` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL"),
                    dialect.name());
        }
    }

    @Test
    void sqlServer2022AndPostgresqlLegacyUsePortableAsciiChecks() {
        var schema = parse(XMI);
        String sqlServer = new DdlGenerator(new SqlServerDialect()).generate(schema);
        assertTrue(sqlServer.contains("PATINDEX('%[^A-Z]%'"));
        assertTrue(sqlServer.contains("Latin1_General_100_BIN2"));
        assertTrue(sqlServer.contains("DATALENGTH(CONVERT(NVARCHAR(MAX), CURRENCY_CODE)) = 6"));
        assertFalse(sqlServer.contains("REGEXP_LIKE("));
        String postgres = new DdlGenerator(new PostgreSqlDialect()).generate(schema);
        assertTrue(postgres.contains("CURRENCY_CODE COLLATE \"C\" ~ '^[A-Z]{3}$'"));
        assertFalse(postgres.contains("REGEXP_LIKE("));
    }

    @Test
    void unreviewedRegexMustBeRejectedForBothTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlServerExpressionMapper().map("REGEXP_LIKE(CODE, '.*')"));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgreSqlExpressionMapper().map("REGEXP_LIKE(CODE, '.*')"));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlServerExpressionMapper().map("REGEXP_LIKE(CODE, '^.*$', 'i')"));
    }

    @Test
    void approvedRegexProfilesRemainStrictAndCaseSensitive() {
        var sqlServer = new SqlServerExpressionMapper();
        var postgres = new PostgreSqlExpressionMapper();
        for (String pattern : java.util.List.of(
                "^[A-Z]{3}$", "^[0-9]{3}$", "^[A-Z][A-Z0-9_]*$",
                "^[A-Z0-9_\\-]{1,30}$")) {
            String expression = "CODE IS NULL OR REGEXP_LIKE(CODE, '" + pattern + "')";
            String rewritten = sqlServer.map(expression);
            assertFalse(rewritten.contains("REGEXP_LIKE"));
            assertTrue(rewritten.contains("PATINDEX"));
            assertTrue(rewritten.contains("DATALENGTH"));
            assertTrue(rewritten.contains("Latin1_General_100_BIN2"));
            assertTrue(rewritten.startsWith("CODE IS NULL OR "));
            String pg = postgres.map(expression);
            assertFalse(pg.contains("REGEXP_LIKE"));
            assertTrue(pg.contains("COLLATE \"C\" ~"));
        }
    }

    @Test
    void directXmi21TimestampPrecisionStillTakesPrecedence() {
        var xml = XMI.replace("tag=\"type\" value=\"TIMESTAMP\"",
                "tag=\"type\" value=\"TIMESTAMP(6)\"")
                .replace("tag=\"length\" value=\"6\"", "tag=\"length\" value=\"0\"");
        var col = parse(xml).findTable("REF_CURRENCY").orElseThrow()
                .findColumn("CREATED_AT").orElseThrow();
        assertEquals(6, col.dataType().precision());
    }

    @Test
    void conflictingTemporalLengthAndPrecisionAreNotSilentlyDiscarded() {
        String conflicting = XMI.replace("tag=\"precision\" value=\"0\"",
                "tag=\"precision\" value=\"3\"");
        assertThrows(IllegalArgumentException.class, () -> parse(conflicting));
    }
}
