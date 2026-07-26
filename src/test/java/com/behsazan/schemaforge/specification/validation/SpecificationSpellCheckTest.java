package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.specification.validation.spelling.SpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.SpellingError;
import com.behsazan.schemaforge.specification.validation.spelling.SpellingSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecificationSpellCheckTest {

    @Test
    void reportsWarningWithoutChangingIdentifier() {
        SpellCheckService spellCheck = text -> text.equals("CUSTMER_NAME")
                ? List.of(new SpellingError(
                        "CUSTMER",
                        "Possible spelling mistake",
                        List.of(new SpellingSuggestion("CUSTOMER"))))
                : List.of();

        Table table = Table.builder("APP", "CUSTOMERS")
                .addColumn(Column.nullable("CUSTMER_NAME", DataType.varchar("VARCHAR2", 50)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("TEST_SCHEMA").addTable(table).build();

        ValidationReport report = new SpecificationValidator(spellCheck).validate(schema);

        assertTrue(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code().equals("SPELLING_WARNING")
                        && issue.path().endsWith("CUSTMER_NAME")
                        && issue.message().contains("CUSTOMER")));
        assertEquals("CUSTMER_NAME", schema.tables().getFirst().columns().getFirst().name().value());
    }

    @Test
    void reportsLanguageToolAvailabilityWhenFailOpenIsEnabled() {
        SpellCheckService spellCheck = text -> List.of(
                SpellingError.serviceFailure("LanguageTool returned HTTP 429"));

        Table table = Table.builder("APP", "CUSTOMERS")
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 50)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("TEST_SCHEMA").addTable(table).build();

        ValidationReport report = new SpecificationValidator(spellCheck).validate(schema);

        assertTrue(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code().equals("SPELL_CHECK_UNAVAILABLE")
                        && issue.path().equals("spell-check")));
    }
}
