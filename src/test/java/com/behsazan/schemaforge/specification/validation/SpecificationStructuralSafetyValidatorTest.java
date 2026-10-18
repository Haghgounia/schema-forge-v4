package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecificationStructuralSafetyValidatorTest {

    @Test
    void reportsExplicitNonNumericIdentityAsCanonicalError() {
        Column status = new Column(
                Identifier.of("REGISTRATION_STATUS_CODE"),
                DataType.varchar("VARCHAR2", 20),
                false, null, Description.empty(), true, 1);
        DatabaseSchema schema = DatabaseSchema.builder("DPS")
                .addTable(Table.builder("DPS", "DEPOSIT_ACCOUNT_EXT_REGISTRY")
                        .addColumn(status)
                        .build())
                .build();

        ValidationReport report = new SpecificationValidator().validate(schema);

        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                "NON_NUMERIC_IDENTITY_COLUMN".equals(issue.code())
                        && "tables.DEPOSIT_ACCOUNT_EXT_REGISTRY.columns.REGISTRATION_STATUS_CODE"
                        .equals(issue.path())));
    }

    @Test
    void keepsNumericIdentityOutsideTheNonNumericGuard() {
        Column id = new Column(
                Identifier.of("REGISTRY_ID"),
                DataType.numeric("NUMBER", 19, 0),
                false, null, Description.empty(), true, 1);
        DatabaseSchema schema = DatabaseSchema.builder("DPS")
                .addTable(Table.builder("DPS", "REGISTRY")
                        .addColumn(id)
                        .build())
                .build();

        ValidationReport report = new SpecificationValidator().validate(schema);

        assertTrue(report.issues().stream().noneMatch(issue ->
                "NON_NUMERIC_IDENTITY_COLUMN".equals(issue.code())));
    }

    @Test
    void reportsUnbalancedCheckParenthesesWithoutGuessingARepair() {
        Column amount = new Column(
                Identifier.of("ANNUAL_INCOME"),
                DataType.numeric("NUMBER", 20, 2),
                true, null, Description.empty(), false, 1);
        DatabaseSchema schema = DatabaseSchema.builder("COL")
                .addTable(Table.builder("COL", "FINANCIAL_PROFILE")
                        .addColumn(amount)
                        .addCheck(new CheckConstraint(
                                Identifier.of("CK_FINANCIAL_VALUES"),
                                "(ANNUAL_INCOME IS NULL OR ANNUAL_INCOME >= 0"))
                        .build())
                .build();

        ValidationReport report = new SpecificationValidator().validate(schema);

        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                "CHECK_EXPRESSION_UNBALANCED_PARENTHESES".equals(issue.code())
                        && "tables.FINANCIAL_PROFILE.checks.CK_FINANCIAL_VALUES".equals(issue.path())));
    }


    @Test
    void reportsUnterminatedCheckStringLiteralWithoutGuessingARepair() {
        Column code = new Column(
                Identifier.of("CODE"),
                DataType.varchar("VARCHAR2", 20),
                true, null, Description.empty(), false, 1);
        DatabaseSchema schema = DatabaseSchema.builder("T")
                .addTable(Table.builder("T", "SAMPLE")
                        .addColumn(code)
                        .addCheck(new CheckConstraint(
                                Identifier.of("CK_SAMPLE_LITERAL"),
                                "CODE <> 'BROKEN"))
                        .build())
                .build();

        ValidationReport report = new SpecificationValidator().validate(schema);

        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                "CHECK_EXPRESSION_UNTERMINATED_STRING_LITERAL".equals(issue.code())
                        && "tables.SAMPLE.checks.CK_SAMPLE_LITERAL".equals(issue.path())));
    }

    @Test
    void ignoresParenthesesInsideQuotedContentAndComments() {
        Column code = new Column(
                Identifier.of("CODE"),
                DataType.varchar("VARCHAR2", 20),
                true, null, Description.empty(), false, 1);
        DatabaseSchema schema = DatabaseSchema.builder("T")
                .addTable(Table.builder("T", "SAMPLE")
                        .addColumn(code)
                        .addCheck(new CheckConstraint(
                                Identifier.of("CK_SAMPLE"),
                                "(CODE <> '(' AND CODE <> ')') /* ignored ( ) */"))
                        .build())
                .build();

        ValidationReport report = new SpecificationValidator().validate(schema);

        assertTrue(report.issues().stream().noneMatch(issue ->
                issue.code().startsWith("CHECK_EXPRESSION_")));
    }
}
