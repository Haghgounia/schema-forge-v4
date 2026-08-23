package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

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
}
