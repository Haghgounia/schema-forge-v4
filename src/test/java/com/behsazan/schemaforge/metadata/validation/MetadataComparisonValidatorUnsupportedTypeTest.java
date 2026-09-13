package com.behsazan.schemaforge.metadata.validation;

import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataTypeFrequency;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataComparisonValidatorUnsupportedTypeTest {

    @Test
    void unsupportedLiveMariaDbTypeDoesNotAbortMetadataComparison() {
        Column mediumInt = new Column(
                Identifier.of("LEGACY_CODE"),
                DataType.simple("MEDIUMINT"),
                true,
                new DefaultValue(null),
                Description.empty(),
                false,
                1,
                null,
                Map.of("MARIADB_NATIVE_COLUMN_TYPE", "mediumint(9)"));
        DatabaseSchema schema = DatabaseSchema.builder("SFORGE_M11_AUDIT")
                .addTable(Table.builder("SFORGE_M11_AUDIT", "M11_NO_PK")
                        .addColumn(mediumInt)
                        .build())
                .build();

        MetadataRepository repository = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of("LEGACY_CODE", new MetadataColumnProfile(
                        "LEGACY_CODE", 1,
                        java.util.List.of(new MetadataTypeFrequency("MEDIUMINT(9)", 1))));
            }

            @Override
            public boolean schemaExistenceAuthoritative() {
                return false;
            }
        };

        MetadataComparisonResult result = assertDoesNotThrow(() ->
                new MetadataComparisonValidator(new MariaDbDialect(), repository).validate(schema));

        assertTrue(result.issues().stream().noneMatch(issue ->
                "METADATA_DATATYPE_MISMATCH".equals(issue.code())));
    }
}
