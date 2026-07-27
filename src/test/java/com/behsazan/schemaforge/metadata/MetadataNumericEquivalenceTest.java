package com.behsazan.schemaforge.metadata;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.metadata.repository.InMemoryMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataTypeFrequency;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects metadata validation from false type warnings caused by numeric optimization. */
class MetadataNumericEquivalenceTest {

    @Test
    void optimizedPostgreSqlShouldNotWarnForEquivalentNumericMetadata() {
        var result = validate(
                new PostgreSqlDialect(NumericMappingStrategy.OPTIMIZED),
                DataType.numeric("NUMBER", 8, null),
                "NUMERIC(8,0)");

        assertFalse(hasTypeMismatch(result));
    }

    @Test
    void safePostgreSqlShouldPreserveExactTypeComparison() {
        var result = validate(
                new PostgreSqlDialect(NumericMappingStrategy.SAFE),
                DataType.numeric("NUMBER", 8, null),
                "INTEGER");

        assertTrue(hasTypeMismatch(result));
    }

    @Test
    void optimizedDb2ZosShouldNotWarnForEquivalentDecimalMetadata() {
        var result = validate(
                new Db2ZosDialect(NumericMappingStrategy.OPTIMIZED),
                DataType.numeric("NUMBER", 10, null),
                "DECIMAL(10,0)");

        assertFalse(hasTypeMismatch(result));
    }

    @Test
    void optimizedComparisonShouldKeepFractionalMismatch() {
        var result = validate(
                new PostgreSqlDialect(NumericMappingStrategy.OPTIMIZED),
                DataType.numeric("NUMBER", 5, 2),
                "INTEGER");

        assertTrue(hasTypeMismatch(result));
    }

    private static com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult validate(
            com.behsazan.schemaforge.dialect.Dialect dialect,
            DataType documentType,
            String metadataType) {

        Column column = Column.required("AMOUNT", documentType);
        Table table = Table.builder("BIM", "PAYMENTS").addColumn(column).build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();
        var repository = new InMemoryMetadataRepository(List.of(
                new MetadataColumnProfile("AMOUNT", 1,
                        List.of(new MetadataTypeFrequency(metadataType, 1)))));
        return new MetadataComparisonValidator(dialect, repository).validate(schema);
    }

    private static boolean hasTypeMismatch(
            com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult result) {
        return result.issues().stream()
                .anyMatch(issue -> "METADATA_DATATYPE_MISMATCH".equals(issue.code()));
    }
}
