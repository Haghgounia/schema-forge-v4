package com.behsazan.schemaforge.metadata;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
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

/** R7.10 P2 logical metadata comparison acceptance for Db2 LUW. */
class Db2LuwMetadataComparisonTest {

    @Test
    void safeComparisonAcceptsExactLuwCatalogSignatures() {
        Table table = Table.builder("APP", "CUSTOMERS")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 9, 0)))
                .addColumn(Column.required("NAME", DataType.varchar("VARCHAR", 80)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(table).build();
        InMemoryMetadataRepository repository = new InMemoryMetadataRepository(List.of(
                new MetadataColumnProfile("ID", 1, List.of(new MetadataTypeFrequency("DECIMAL(9,0)", 1))),
                new MetadataColumnProfile("NAME", 1, List.of(new MetadataTypeFrequency("VARCHAR(80)", 1)))));

        var result = new MetadataComparisonValidator(new Db2LuwDialect(NumericMappingStrategy.SAFE), repository)
                .validate(schema);

        assertFalse(result.issues().stream().anyMatch(issue ->
                "METADATA_DATATYPE_MISMATCH".equals(issue.code())));
    }

    @Test
    void optimizedComparisonTreatsLosslessLuwIntegerMappingAsEquivalent() {
        Table table = Table.builder("APP", "CUSTOMERS")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 9, 0)))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(table).build();
        InMemoryMetadataRepository repository = new InMemoryMetadataRepository(List.of(
                new MetadataColumnProfile("ID", 1, List.of(new MetadataTypeFrequency("INTEGER", 1)))));

        var result = new MetadataComparisonValidator(new Db2LuwDialect(NumericMappingStrategy.OPTIMIZED), repository)
                .validate(schema);

        assertTrue(result.issues().stream().noneMatch(issue ->
                "METADATA_DATATYPE_MISMATCH".equals(issue.code())));
    }
}
