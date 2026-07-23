package com.behsazan.schemaforge.specification.adapter.docx;

import com.behsazan.schemaforge.specification.spi.SpecificationSource;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingConfigurationsRangeEndToEndTest {

    @Test
    void convertsPersianAndEnglishRangeDescriptionsToNumericCheckExpressions() throws Exception {
        var parser = new DocxSpecificationParser();

        try (InputStream input = getClass().getResourceAsStream(
                "/samples/docx/MCB.ACC.TBL.ACCOUNTING_CONFIGURATIONS.V1.0.docx")) {
            assertThat(input).isNotNull();

            var schema = parser.parse(new SpecificationSource(
                    "MCB.ACC.TBL.ACCOUNTING_CONFIGURATIONS.V1.0.docx",
                    input));

            var expressions = schema.tables().getFirst().checkConstraints().stream()
                    .map(check -> check.expression())
                    .toList();

            assertThat(expressions)
                    .contains("EXCHANGE_PERIOD IN (1, 2, 3)")
                    .contains("EXCHANGE_DAY_OF_WEEK BETWEEN 1 AND 7")
                    .contains("EXCHANGE_DAY_OF_MONTH BETWEEN 1 AND 31")
                    .contains("ISSUANCE_INTERBRANCH_DOC IN (0, 1)")
                    .contains("AUTOMATIC_VOUCHER_TYPE IN (1, 2)")
                    .contains("IS_ACTIVE IN (0, 1)");
        }
    }
}
