package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance coverage for metadata-aware schema bootstrap suppression. */
class SchemaBootstrapExistenceAwarenessTest {

    private static final ValidationReport VALID = new ValidationReport(true, List.of());

    @Test
    void existingDocumentSchemaIsNotRecreatedAcrossAllSixPlatforms() {
        DatabaseSchema schema = sampleSchema();
        MetadataComparisonResult metadata = new MetadataComparisonResult(
                List.of(), Map.of(), Map.of(), Map.of("FEE", true), true);

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            String sql = new DdlGenerator(DialectFactory.create(platform))
                    .generate(schema, VALID, metadata);

            assertFalse(containsSchemaBootstrap(platform, sql),
                    () -> platform + " must not emit schema bootstrap for an existing schema");
            assertTrue(sql.toUpperCase().contains("CREATE TABLE"),
                    () -> platform + " must still emit table DDL");
        }
    }

    @Test
    void verifiedMissingOrUnknownSchemaStillKeepsBootstrap() {
        DatabaseSchema schema = sampleSchema();
        MetadataComparisonResult missing = new MetadataComparisonResult(
                List.of(), Map.of(), Map.of(), Map.of("FEE", false), true);
        MetadataComparisonResult unknown = new MetadataComparisonResult(
                List.of(), Map.of(), Map.of(), Map.of(), false);

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            String missingSql = new DdlGenerator(DialectFactory.create(platform))
                    .generate(schema, VALID, missing);
            String unknownSql = new DdlGenerator(DialectFactory.create(platform))
                    .generate(schema, VALID, unknown);

            assertTrue(containsSchemaBootstrap(platform, missingSql),
                    () -> platform + " must emit bootstrap when metadata verifies the schema is missing");
            assertTrue(containsSchemaBootstrap(platform, unknownSql),
                    () -> platform + " must preserve offline bootstrap when schema existence is unknown");
        }
    }

    @Test
    void metadataValidationRetainsSchemaExistenceAndQueriesEachSchemaOnce() {
        DatabaseSchema schema = DatabaseSchema.builder("FEE")
                .addTable(table("FEE", "T1"))
                .addTable(table("FEE", "T2"))
                .build();
        AtomicInteger schemaChecks = new AtomicInteger();
        MetadataRepository repository = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public boolean schemaExists(String schemaName) {
                schemaChecks.incrementAndGet();
                return "FEE".equalsIgnoreCase(schemaName);
            }

            @Override
            public List<String> findTableSchemas(String tableName) {
                return List.of();
            }
        };

        MetadataComparisonResult result = new MetadataComparisonValidator(
                DialectFactory.create(DatabasePlatform.ORACLE), repository).validate(schema);

        assertTrue(result.schemaKnownToExist("FEE"));
        assertFalse(result.schemaKnownToBeMissing("FEE"));
        assertEquals(1, schemaChecks.get(), "schema existence must be queried once per distinct schema");
    }

    private static DatabaseSchema sampleSchema() {
        return DatabaseSchema.builder("FEE")
                .addTable(table("FEE", "T1"))
                .build();
    }

    private static Table table(String schema, String name) {
        return Table.builder(schema, name)
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .build();
    }

    private static boolean containsSchemaBootstrap(DatabasePlatform platform, String sql) {
        String upper = sql.toUpperCase();
        return switch (platform) {
            case ORACLE -> upper.contains("CREATE USER FEE");
            case POSTGRESQL -> upper.contains("CREATE SCHEMA IF NOT EXISTS FEE");
            case DB2_ZOS -> upper.contains("CREATE SCHEMA AUTHORIZATION FEE");
            case DB2_LUW -> upper.contains("CREATE SCHEMA FEE");
            case SQLSERVER -> upper.contains("CREATE SCHEMA FEE AUTHORIZATION");
            case MYSQL -> upper.contains("CREATE DATABASE IF NOT EXISTS `FEE`");
        };
    }
}
