package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonArtifactProducerTest {
    private static final String TIMESTAMP = "20260823_033000_000";

    @TempDir
    Path tempDir;

    @Test
    void skipsAllTablesWhenRepositoryIsUnavailable() throws Exception {
        DatabaseSchema schema = schema();
        ArtifactGenerationContext context = context();

        new ComparisonArtifactProducer(new ArtifactNamingPolicy()).writeComparisonWorkbooks(
                schema,
                MetadataRepository.empty(),
                metadata(schema),
                tempDir,
                TIMESTAMP,
                DatabasePlatform.ORACLE,
                dialect(DatabasePlatform.ORACLE),
                context);

        assertEquals(1, context.ledger().snapshot().size());
        ArtifactDescriptor descriptor = context.ledger().snapshot().get(0);
        assertEquals(ArtifactType.COMPARISON_WORKBOOK, descriptor.type());
        assertEquals(DatabasePlatform.ORACLE, descriptor.platform());
        assertEquals(ArtifactStatus.SKIPPED, descriptor.status());
        assertEquals("APP.CUSTOMERS", descriptor.logicalName());
        assertEquals("SchemaCompareExcelWriter", descriptor.provenance().producer());
        assertEquals("METADATA_UNAVAILABLE: Metadata repository is disabled or unavailable",
                descriptor.outcomeReason());
        try (var paths = Files.walk(tempDir)) {
            assertTrue(paths.noneMatch(Files::isRegularFile));
        }
    }

    @Test
    void writesCanonicalWorkbookAndLedgerForDocumentFlow() throws Exception {
        DatabaseSchema schema = schema();
        Table live = liveTable("APP", "CUSTOMERS");
        ArtifactGenerationContext context = context();

        new ComparisonArtifactProducer(new ArtifactNamingPolicy()).writeComparisonWorkbooks(
                schema,
                repository(live, List.of("APP")),
                metadata(schema),
                tempDir,
                TIMESTAMP,
                DatabasePlatform.ORACLE,
                dialect(DatabasePlatform.ORACLE),
                context);

        String expected = "comparison/oracle/APP.CUSTOMERS_" + TIMESTAMP + ".oracle.compare.xlsx";
        Path workbook = tempDir.resolve(expected);
        assertTrue(Files.isRegularFile(workbook));
        verifyWorkbook(workbook);

        assertEquals(1, context.ledger().snapshot().size());
        ArtifactDescriptor descriptor = context.ledger().snapshot().get(0);
        assertEquals(ArtifactStatus.GENERATED, descriptor.status());
        assertEquals(expected, descriptor.relativePath());
        assertEquals("APP.CUSTOMERS", descriptor.logicalName());
        assertEquals("SchemaCompareExcelWriter", descriptor.provenance().producer());
    }

    @Test
    void writesEaWorkbookWithPostgreSqlLowercasePathAndCaseInsensitiveSchemaFallback() throws Exception {
        DatabaseSchema schema = schema();
        Table live = liveTable("app", "CUSTOMERS");
        ArtifactGenerationContext context = context();
        Table document = schema.tables().get(0);

        String relative = new ComparisonArtifactProducer(new ArtifactNamingPolicy()).writeEaComparisonWorkbook(
                schema,
                document,
                repository(live, List.of("app")),
                metadata(schema),
                tempDir,
                DatabasePlatform.POSTGRESQL,
                dialect(DatabasePlatform.POSTGRESQL),
                context,
                TIMESTAMP);

        String expected = "comparison/postgresql/app.customers_" + TIMESTAMP
                + ".postgresql.compare.xlsx";
        assertEquals(expected, relative);
        assertTrue(Files.isRegularFile(tempDir.resolve(expected)));
        verifyWorkbook(tempDir.resolve(expected));

        ArtifactDescriptor descriptor = context.ledger().snapshot().get(0);
        assertEquals(ArtifactStatus.GENERATED, descriptor.status());
        assertEquals(expected, descriptor.relativePath());
        assertEquals("APP.CUSTOMERS", descriptor.logicalName());
        assertEquals(DatabasePlatform.POSTGRESQL, descriptor.platform());
    }

    @Test
    void missingKnownSchemaSkipsEaComparisonWithoutTableLookup() throws Exception {
        DatabaseSchema schema = schema();
        Table document = schema.tables().get(0);
        AtomicInteger tableLookups = new AtomicInteger();
        MetadataRepository repository = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                tableLookups.incrementAndGet();
                return Optional.empty();
            }
        };
        MetadataComparisonResult metadata = new MetadataComparisonResult(
                List.of(), Map.of(), Map.of(), Map.of("APP", false), true);
        ArtifactGenerationContext context = context();

        String relative = new ComparisonArtifactProducer(new ArtifactNamingPolicy()).writeEaComparisonWorkbook(
                schema, document, repository, metadata, tempDir, DatabasePlatform.DB2_LUW,
                dialect(DatabasePlatform.DB2_LUW), context, TIMESTAMP);

        assertNull(relative);
        assertEquals(0, tableLookups.get(), "known-missing schema must skip live table lookup");
        assertEquals(ArtifactStatus.SKIPPED, context.ledger().snapshot().get(0).status());
        assertEquals("LIVE_SCHEMA_NOT_FOUND: Live schema was not found",
                context.ledger().snapshot().get(0).outcomeReason());
    }

    private static DatabaseSchema schema() {
        Table table = Table.builder("APP", "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 100)))
                .build();
        return DatabaseSchema.builder("APP").addTable(table).build();
    }

    private static Table liveTable(String schema, String table) {
        return Table.builder(schema, table)
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 80)))
                .build();
    }

    private static MetadataComparisonResult metadata(DatabaseSchema schema) {
        Table table = schema.tables().get(0);
        String path = MetadataComparisonValidator.path(table, table.columns().get(0));
        return new MetadataComparisonResult(List.of(), Map.of(path, 7L), true);
    }

    private static MetadataRepository repository(Table live, List<String> schemas) {
        return new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                return live.qualifiedName().name().value().equalsIgnoreCase(tableName)
                        && live.qualifiedName().schemaName()
                        .map(identifier -> identifier.value().equals(schemaName))
                        .orElse(false)
                        ? Optional.of(live)
                        : Optional.empty();
            }

            @Override
            public List<String> findTableSchemas(String tableName) {
                return schemas;
            }
        };
    }

    private static Dialect dialect(DatabasePlatform platform) {
        return DialectFactory.create(platform);
    }

    private static ArtifactGenerationContext context() {
        return ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD,
                "customers.docx",
                TIMESTAMP,
                OffsetDateTime.parse("2026-08-23T03:30:00-07:00"));
    }

    private static void verifyWorkbook(Path workbookPath) throws Exception {
        assertTrue(Files.size(workbookPath) > 0);
        try (InputStream in = Files.newInputStream(workbookPath);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            assertNotNull(workbook.getSheet("CUSTOMERS"));
            assertFalse(workbook.getSheet("CUSTOMERS").getPhysicalNumberOfRows() == 0);
        }
    }
}
