package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.metadata.repository.FailureIsolatingMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CrudArtifactProducerTest {
    private static final String TIMESTAMP = "20260823_034000_000";

    @TempDir
    Path tempDir;

    @Test
    void writesSummaryAndSkipsBothPlatformsWhenDocumentTableHasNoPrimaryKey() throws Exception {
        MetadataRepositoryResolver resolver = resolver(MetadataRepository.empty(), MetadataRepository.empty());
        ArtifactGenerationContext context = context();

        new CrudArtifactProducer(new ArtifactNamingPolicy(), resolver, GrantProperties.defaults())
                .writeMetadataCrudArtifacts(schema(false), tempDir, "customers", TIMESTAMP, context);

        Path summary = tempDir.resolve("reports/customers_" + TIMESTAMP + ".metadata-crud-summary.csv");
        assertTrue(Files.isRegularFile(summary));
        String csv = Files.readString(summary);
        assertTrue(csv.contains("\"ORACLE\",\"APP\",\"CUSTOMERS\",\"SKIPPED_NO_PRIMARY_KEY\",\"\",\"Document table has no primary key\""));
        assertTrue(csv.contains("\"SQLSERVER\",\"APP\",\"CUSTOMERS\",\"SKIPPED_NO_PRIMARY_KEY\",\"\",\"Document table has no primary key\""));

        List<ArtifactDescriptor> artifacts = context.ledger().snapshot();
        assertEquals(3, artifacts.size());
        List<ArtifactDescriptor> skippedCrud = artifacts.stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.CRUD)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.SKIPPED)
                .toList();
        assertEquals(2, skippedCrud.size());
        assertTrue(skippedCrud.stream().allMatch(descriptor ->
                "DOCUMENT_NO_PRIMARY_KEY: Document table has no primary key"
                        .equals(descriptor.outcomeReason())));
        ArtifactDescriptor summaryDescriptor = artifacts.get(2);
        assertEquals(ArtifactType.SUMMARY_REPORT, summaryDescriptor.type());
        assertEquals("SchemaForgeApiService", summaryDescriptor.provenance().producer());
    }

    @Test
    void writesOracleAndSqlServerCrudWithCanonicalPathsLedgerAndWriteGrants() throws Exception {
        MetadataRepository oracle = repository(oracleLiveTable("app"), List.of("app"));
        MetadataRepository sqlServer = repository(sqlServerLiveTable("APP"), List.of("APP"));
        MetadataRepositoryResolver resolver = resolver(oracle, sqlServer);
        ArtifactGenerationContext context = context();
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();

        GrantProperties grants = new GrantProperties();
        grants.setGrants(List.of(new GrantProperties.GrantRule(
                "U_DEVELOPER", List.of("SELECT", "INSERT", "UPDATE", "DELETE"))));
        new CrudArtifactProducer(naming, resolver, grants)
                .writeMetadataCrudArtifacts(schema(true), tempDir, "customers", TIMESTAMP, context);

        Path oraclePath = tempDir.resolve(naming.crudRelativePath(
                "APP.CUSTOMERS", DatabasePlatform.ORACLE, TIMESTAMP));
        Path sqlServerPath = tempDir.resolve(naming.crudRelativePath(
                "APP.CUSTOMERS", DatabasePlatform.SQLSERVER, TIMESTAMP));
        assertTrue(Files.isRegularFile(oraclePath));
        assertTrue(Files.isRegularFile(sqlServerPath));

        String oracleSql = Files.readString(oraclePath);
        String sqlServerSql = Files.readString(sqlServerPath);
        assertTrue(oracleSql.contains("GRANT EXECUTE ON APP.PKG_CUSTOMERS TO U_DEVELOPER;"));
        assertTrue(sqlServerSql.contains("GRANT EXECUTE ON OBJECT::[APP].[CUSTOMERS_CREATE] TO [U_DEVELOPER];"));

        List<ArtifactDescriptor> generatedCrud = context.ledger().snapshot().stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.CRUD)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.GENERATED)
                .toList();
        assertEquals(2, generatedCrud.size());
        assertEquals("OracleCrudPackageGenerator", generatedCrud.get(0).provenance().producer());
        assertEquals("SqlServerCrudProcedureGenerator", generatedCrud.get(1).provenance().producer());
        assertTrue(generatedCrud.stream().allMatch(descriptor -> "APP.CUSTOMERS".equals(descriptor.logicalName())));

        String summary = Files.readString(tempDir.resolve(
                "reports/customers_" + TIMESTAMP + ".metadata-crud-summary.csv"));
        assertTrue(summary.contains("\"ORACLE\",\"APP\",\"CUSTOMERS\",\"GENERATED\",\"crud/oracle/"));
        assertTrue(summary.contains("\"SQLSERVER\",\"APP\",\"CUSTOMERS\",\"GENERATED\",\"crud/sqlserver/"));
    }

    @Test
    void reusesRequestRepositoryCacheAcrossComparisonAndCrud() throws Exception {
        Table live = oracleLiveTable("APP");
        AtomicInteger schemaCalls = new AtomicInteger();
        AtomicInteger tableCalls = new AtomicInteger();
        MetadataRepository delegate = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public boolean schemaExists(String schemaName) {
                schemaCalls.incrementAndGet();
                return true;
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                tableCalls.incrementAndGet();
                return Optional.of(live);
            }
        };
        MetadataRepository requestRepository = FailureIsolatingMetadataRepository.wrap(
                DatabasePlatform.ORACLE, delegate);

        // Simulate metadata validation + comparison having already populated request-local caches.
        assertTrue(requestRepository.schemaExists("APP"));
        assertTrue(requestRepository.findTable("APP", "CUSTOMERS").isPresent());

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        ArtifactGenerationContext context = context();
        new CrudArtifactProducer(new ArtifactNamingPolicy(), resolver, GrantProperties.defaults())
                .writeMetadataCrudArtifacts(
                        schema(true), tempDir, "customers", TIMESTAMP, context,
                        Set.of(DatabasePlatform.ORACLE),
                        Map.of(DatabasePlatform.ORACLE, requestRepository));

        assertEquals(1, schemaCalls.get());
        assertEquals(1, tableCalls.get());
        verifyNoInteractions(resolver);
        assertTrue(Files.isRegularFile(tempDir.resolve(new ArtifactNamingPolicy().crudRelativePath(
                "APP.CUSTOMERS", DatabasePlatform.ORACLE, TIMESTAMP))));
    }

    @Test
    void skipsMissingSchemaWithoutPerTableCrudLookup() throws Exception {
        AtomicInteger schemaCalls = new AtomicInteger();
        AtomicInteger tableCalls = new AtomicInteger();
        MetadataRepository repository = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public boolean schemaExists(String schemaName) {
                schemaCalls.incrementAndGet();
                return false;
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                tableCalls.incrementAndGet();
                return Optional.empty();
            }
        };
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        ArtifactGenerationContext context = context();

        new CrudArtifactProducer(new ArtifactNamingPolicy(), resolver, GrantProperties.defaults())
                .writeMetadataCrudArtifacts(
                        schema(true), tempDir, "customers", TIMESTAMP, context,
                        Set.of(DatabasePlatform.SQLSERVER),
                        Map.of(DatabasePlatform.SQLSERVER, repository));

        assertEquals(1, schemaCalls.get());
        assertEquals(0, tableCalls.get());
        verifyNoInteractions(resolver);
        String summary = Files.readString(tempDir.resolve(
                "reports/customers_" + TIMESTAMP + ".metadata-crud-summary.csv"));
        assertTrue(summary.contains("\"SQLSERVER\",\"APP\",\"CUSTOMERS\",\"SKIPPED_TABLE_NOT_FOUND\""));
        assertTrue(summary.contains("Live schema was not found"));
        ArtifactDescriptor skipped = context.ledger().snapshot().stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.CRUD)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.SKIPPED)
                .findFirst().orElseThrow();
        assertEquals("LIVE_SCHEMA_NOT_FOUND: Live schema was not found", skipped.outcomeReason());
    }

    @Test
    void recordsGeneratorFailuresWithoutAbortingSummary() throws Exception {
        Table invalidOracle = pkOnlyTable("APP", DataType.numeric("NUMBER", 10, 0));
        Table invalidSqlServer = pkOnlyTable("APP", DataType.simple("BIGINT"));
        MetadataRepositoryResolver resolver = resolver(
                repository(invalidOracle, List.of("APP")),
                repository(invalidSqlServer, List.of("APP")));
        ArtifactGenerationContext context = context();

        new CrudArtifactProducer(new ArtifactNamingPolicy(), resolver, GrantProperties.defaults())
                .writeMetadataCrudArtifacts(schema(true), tempDir, "customers", TIMESTAMP, context);

        String summary = Files.readString(tempDir.resolve(
                "reports/customers_" + TIMESTAMP + ".metadata-crud-summary.csv"));
        assertTrue(summary.contains("\"ORACLE\",\"APP\",\"CUSTOMERS\",\"FAILED\""));
        assertTrue(summary.contains("\"SQLSERVER\",\"APP\",\"CUSTOMERS\",\"FAILED\""));
        assertEquals(2, context.ledger().snapshot().stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.CRUD)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.FAILED)
                .count());
        try (var paths = Files.walk(tempDir.resolve("crud"))) {
            assertFalse(paths.anyMatch(Files::isRegularFile));
        } catch (java.nio.file.NoSuchFileException ignored) {
            // No CRUD directory is also the expected result.
        }
    }

    private static DatabaseSchema schema(boolean primaryKey) {
        Table.Builder builder = Table.builder("APP", "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 100)));
        if (primaryKey) {
            builder.primaryKey(new PrimaryKey(
                    Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))));
        }
        return DatabaseSchema.builder("APP").addTable(builder.build()).build();
    }

    private static Table oracleLiveTable(String schema) {
        return Table.builder(schema, "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 100)))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))))
                .build();
    }

    private static Table sqlServerLiveTable(String schema) {
        return Table.builder(schema, "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", DataType.simple("BIGINT")))
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR", 100)))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))))
                .build();
    }

    private static Table pkOnlyTable(String schema, DataType type) {
        return Table.builder(schema, "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", type))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))))
                .build();
    }

    private static MetadataRepositoryResolver resolver(
            MetadataRepository oracle, MetadataRepository sqlServer) {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE)).thenReturn(oracle);
        when(resolver.resolve(DatabasePlatform.SQLSERVER)).thenReturn(sqlServer);
        return resolver;
    }

    private static MetadataRepository repository(Table live, List<String> schemas) {
        return new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                boolean nameMatches = live.qualifiedName().name().value().equalsIgnoreCase(tableName);
                boolean schemaMatches = live.qualifiedName().schemaName()
                        .map(identifier -> identifier.value().equals(schemaName))
                        .orElse(false);
                return nameMatches && schemaMatches ? Optional.of(live) : Optional.empty();
            }

            @Override
            public List<String> findTableSchemas(String tableName) {
                return schemas;
            }
        };
    }

    private static ArtifactGenerationContext context() {
        return ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD,
                "customers.docx",
                TIMESTAMP,
                OffsetDateTime.parse("2026-08-23T03:40:00-07:00"));
    }
}
