package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamingConventionValidatorTest {

    @Test
    void singularPolicyAcceptsDepositAccountAndKnownGrammarComponents() {
        NamingValidationProperties properties = new NamingValidationProperties();
        properties.setTableConvention(NamingValidationProperties.TableConvention.SINGULAR);

        Table table = Table.builder("DPS2", "DEPOSIT_ACCOUNT")
                .addColumn(Column.nullable("EXPIRES_AT", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("REQUIRES_COSIGN", DataType.varchar("VARCHAR2", 1)))
                .addColumn(Column.nullable("DETAILS", DataType.varchar("VARCHAR2", 100)))
                .addColumn(Column.nullable("SOURCE_OF_FUNDS_CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("MIN_ACTIVE_DAYS", DataType.numeric("NUMBER", 10, 0)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS2").addTable(table).build();

        List<ValidationIssue> issues = new NamingConventionValidator(properties).validate(schema);

        assertTrue(issues.stream().noneMatch(issue ->
                issue.code().equals("TABLE_NAME_NOT_SINGULAR")
                        || issue.code().equals("PLURAL_COLUMN_COMPONENT")));
    }

    @Test
    void pluralPolicyReportsOnlyTheCanonicalTableName() {
        NamingValidationProperties properties = new NamingValidationProperties();
        properties.setTableConvention(NamingValidationProperties.TableConvention.PLURAL);

        Table table = Table.builder("DPS2", "DEPOSIT_ACCOUNT")
                .addColumn(Column.nullable("ACCOUNT_ID", DataType.numeric("NUMBER", 19, 0)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS2").addTable(table).build();

        List<ValidationIssue> issues = new NamingConventionValidator(properties).validate(schema);

        assertEquals(1, issues.stream().filter(issue -> issue.code().equals("TABLE_NAME_NOT_PLURAL")).count());
        assertTrue(issues.stream().anyMatch(issue -> issue.path().equals("tables.DEPOSIT_ACCOUNT")));
    }

    @Test
    void pluralComponentCheckStillFindsUnambiguousPluralNoun() {
        NamingValidationProperties properties = new NamingValidationProperties();
        properties.setTableConvention(NamingValidationProperties.TableConvention.OFF);

        Table table = Table.builder("DPS2", "DEPOSIT_ACCOUNT")
                .addColumn(Column.nullable("CUSTOMERS_ID", DataType.numeric("NUMBER", 19, 0)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS2").addTable(table).build();

        List<ValidationIssue> issues = new NamingConventionValidator(properties).validate(schema);

        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("PLURAL_COLUMN_COMPONENT")
                        && issue.message().contains("CUSTOMERS")));
    }
}
