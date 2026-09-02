package com.behsazan.schemaforge.metadata.validation;

import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects metadata profile reads from DBMS prepared-statement parameter limits. */
class MetadataComparisonValidatorBatchingTest {

    @Test
    void splitsLargeColumnProfileRequestsIntoSafeBatches() {
        Table.Builder table = Table.builder("BIM", "CUSTOMERS");
        for (int index = 0; index < 2101; index++) {
            table.addColumn(Column.nullable(
                    "COLUMN_" + index,
                    DataType.numeric("NUMBER", 10, 0)));
        }
        DatabaseSchema schema = DatabaseSchema.builder("BIM")
                .addTable(table.build())
                .build();
        RecordingRepository repository = new RecordingRepository();

        new MetadataComparisonValidator(new SqlServerDialect(), repository).validate(schema);

        assertEquals(List.of(500, 500, 500, 500, 101), repository.batchSizes);
        assertTrue(repository.batchSizes.stream().allMatch(size -> size <= 500));
    }

    private static final class RecordingRepository implements MetadataRepository {
        private final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
            batchSizes.add(columnNames.size());
            return Map.of();
        }

        @Override
        public boolean schemaExistenceAuthoritative() {
            return false;
        }
    }
}
