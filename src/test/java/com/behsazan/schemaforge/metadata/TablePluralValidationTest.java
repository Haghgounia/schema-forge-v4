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

class TablePluralValidationTest {

    @Test
    void shouldOnlyHintForSingularTableNamesWithoutChangingNames() {
        ForeignKey language = new ForeignKey(
                Identifier.of("FK_PROVINCES_LANGUAGE_ID"),
                List.of(Identifier.of("LANGUAGE_ID")),
                QualifiedName.of("BIM", "LANGUAGE"),
                List.of(Identifier.of("LANGUAGE_ID")),
                ReferentialAction.NO_ACTION,
                ReferentialAction.NO_ACTION,
                false,
                false,
                true,
                true);
        Table table = Table.builder("BIM", "PROVINCES")
                .addColumn(Column.nullable("LANGUAGE_ID", DataType.numeric("NUMBER", 2, null)))
                .addForeignKey(language)
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();

        var result = new MetadataComparisonValidator(new OracleDialect(), MetadataRepository.empty()).validate(schema);

        assertEquals("LANGUAGE", table.foreignKeys().getFirst().referencedTable().name().value());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("TABLE_NAME_NOT_PLURAL")
                        && issue.path().contains("FK_PROVINCES_LANGUAGE_ID")));
        assertTrue(result.issues().stream().noneMatch(issue ->
                issue.code().equals("TABLE_NAME_NOT_PLURAL")
                        && issue.path().equals("tables.PROVINCES")));
    }
}
