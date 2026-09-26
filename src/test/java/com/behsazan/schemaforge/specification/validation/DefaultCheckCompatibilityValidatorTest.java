package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCheckCompatibilityValidatorTest {

    @Test
    void reportsLiteralDefaultOutsideExactInDomain() {
        Column status = new Column(
                Identifier.of("REGISTRATION_STATUS_CODE"),
                DataType.varchar("VARCHAR2", 20),
                false,
                new DefaultValue("0"),
                Description.empty(),
                true,
                1);
        Table table = Table.builder("DPS2", "DEPOSIT_ACCOUNT_EXT_REGISTRY")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_DEP_ACC_REG_ST"),
                        "REGISTRATION_STATUS_CODE IN ('NOT_SENT','REQUESTED','CONFIRMED','FAILED','REVOKED')"))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS2").addTable(table).build();

        List<ValidationIssue> issues = new DefaultCheckCompatibilityValidator().validate(schema);

        assertEquals(1, issues.size());
        assertEquals("DEFAULT_CHECK_INCOMPATIBLE", issues.getFirst().code());
        assertTrue(issues.getFirst().path().endsWith("REGISTRATION_STATUS_CODE"));
    }

    @Test
    void acceptsDefaultInsideExactInDomain() {
        Column status = new Column(
                Identifier.of("REGISTRATION_STATUS_CODE"),
                DataType.varchar("VARCHAR2", 20),
                false,
                new DefaultValue("'NOT_SENT'"),
                Description.empty(),
                false,
                1);
        Table table = Table.builder("DPS2", "DEPOSIT_ACCOUNT_EXT_REGISTRY")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_DEP_ACC_REG_ST"),
                        "(REGISTRATION_STATUS_CODE IN ('NOT_SENT','REQUESTED'))"))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS2").addTable(table).build();

        assertTrue(new DefaultCheckCompatibilityValidator().validate(schema).isEmpty());
    }

    @Test
    void skipsCompoundPredicatesRatherThanGuessing() {
        Column status = new Column(
                Identifier.of("REGISTRATION_STATUS_CODE"),
                DataType.varchar("VARCHAR2", 20),
                false,
                new DefaultValue("0"),
                Description.empty(),
                false,
                1);
        Table table = Table.builder("DPS2", "DEPOSIT_ACCOUNT_EXT_REGISTRY")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_DEP_ACC_REG_ST"),
                        "REGISTRATION_STATUS_CODE IN ('NOT_SENT','REQUESTED') OR REGISTRATION_STATUS_CODE IS NULL"))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("DPS2").addTable(table).build();

        assertTrue(new DefaultCheckCompatibilityValidator().validate(schema).isEmpty());
    }
}
