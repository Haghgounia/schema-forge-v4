package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-evidence regressions for fail-closed EA canonical validation. */
class EnterpriseArchitectSourceSafetyRegressionTest {

    @Test
    void partySourceMalformedCheckIsPreservedAndReportedInsteadOfRepaired() throws Exception {
        Path source = Path.of("src/test/resources/Party_14050514.xml");
        final com.behsazan.schemaforge.domain.model.DatabaseSchema schema;
        try (var input = Files.newInputStream(source)) {
            schema = new EnterpriseArchitectXmlParser("COL").parse(
                    source.getFileName().toString(), input);
        }

        var profile = schema.findTable("FINANCIAL_PROFILE").orElseThrow();
        var check = profile.checkConstraints().stream()
                .filter(candidate -> candidate.expression().contains("EXPECTED_MONTHLY_TURNOVER"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                "(ANNUAL_INCOME IS NULL OR ANNUAL_INCOME >= 0) AND "
                        + "(TOTAL_ASSETS IS NULL OR TOTAL_ASSETS >= 0) AND "
                        + "(TOTAL_LIABILITIES IS NULL OR TOTAL_LIABILITIES >= 0) AND "
                        + "(EXPECTED_MONTHLY_TURNOVER IS NULL OR EXPECTED_MONTHLY_TURNOVER >= 0",
                check.expression(),
                "EA parser must preserve the malformed source expression; it must not guess a repair");

        var report = new SpecificationValidator().validate(schema);
        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                "CHECK_EXPRESSION_UNBALANCED_PARENTHESES".equals(issue.code())
                        && issue.path().startsWith("tables.FINANCIAL_PROFILE.checks.")));
    }
}
