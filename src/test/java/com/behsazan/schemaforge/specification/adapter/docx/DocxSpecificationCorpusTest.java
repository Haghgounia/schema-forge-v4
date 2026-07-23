package com.behsazan.schemaforge.specification.adapter.docx;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.spi.SpecificationSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocxSpecificationCorpusTest {
    private final DocxSpecificationParser parser = new DocxSpecificationParser();

    @ParameterizedTest(name = "parses {0}")
    @ValueSource(strings = {
            "BIM.TBL.CITIES.V1.1.docx",
            "MCB.BIM.TBL.PROVINCES.V1.1.docx",
            "MCB.BIM.TBL.PROVINCE_HISTORY.V1.1.docx",
            "MCB.DPS.TBL.DEPOSITS.docx",
            "MCB.LON.TBL.CONTRACTS.docx",
            "MCB.LON.TBL.PROPOSEDS.docx"
    })
    void parsesRealTableSpecifications(String fileName) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/samples/docx/" + fileName)) {
            assertThat(input).as("sample %s", fileName).isNotNull();

            DatabaseSchema schema = parser.parse(new SpecificationSource(fileName, input));

            assertThat(schema.name().value()).isNotBlank();
            assertThat(schema.tables()).hasSize(1);
            assertThat(schema.tables().getFirst().columns()).isNotEmpty();
        }
    }
}
