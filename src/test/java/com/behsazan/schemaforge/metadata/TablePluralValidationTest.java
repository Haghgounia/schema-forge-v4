package com.behsazan.schemaforge.metadata;

import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of table plural validation.
 *
 * @since 4.1
 */
class TablePluralValidationTest {

    @Test
    void reportsCanonicalTableOnceWithoutRepeatingReferencedTableOnForeignKey() {
        ForeignKey country = new ForeignKey(
                Identifier.of("FK_LANGUAGE_COUNTRY_ID"),
                List.of(Identifier.of("COUNTRY_ID")),
                QualifiedName.of("BIM", "COUNTRY"),
                List.of(Identifier.of("COUNTRY_ID")),
                ReferentialAction.NO_ACTION,
                ReferentialAction.NO_ACTION,
                false,
                false,
                true,
                true);
        Table table = Table.builder("BIM", "LANGUAGE")
                .addColumn(Column.nullable("COUNTRY_ID", DataType.numeric("NUMBER", 2, null)))
                .addForeignKey(country)
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();

        var result = new MetadataComparisonValidator(new OracleDialect(), MetadataRepository.empty()).validate(schema);

        assertEquals("COUNTRY", table.foreignKeys().getFirst().referencedTable().name().value());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("TABLE_NAME_NOT_PLURAL")
                        && issue.path().equals("tables.LANGUAGE")));
        assertTrue(result.issues().stream().noneMatch(issue ->
                issue.code().equals("TABLE_NAME_NOT_PLURAL")
                        && issue.path().contains("foreignKeys")));
    }

    @Test
    void canSuppressDbmsIndependentNamingDuringPerDialectMetadataComparison() {
        Table table = Table.builder("BIM", "LANGUAGE")
                .addColumn(Column.nullable("CUSTOMERS_ID", DataType.numeric("NUMBER", 2, null)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();

        var result = new MetadataComparisonValidator(
                new OracleDialect(), MetadataRepository.empty(), false).validate(schema);

        assertTrue(result.issues().stream().noneMatch(issue ->
                issue.code().equals("TABLE_NAME_NOT_PLURAL")
                        || issue.code().equals("PLURAL_COLUMN_COMPONENT")));
    }
}
