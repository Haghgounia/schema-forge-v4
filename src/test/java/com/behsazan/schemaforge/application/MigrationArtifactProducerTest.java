package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.metadata.repository.InMemoryMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.migration.FlywayMigrationNamer;
import com.behsazan.schemaforge.migration.MigrationFileWriter;
import com.behsazan.schemaforge.migration.MigrationGenerationService;
import com.behsazan.schemaforge.migration.MigrationSqlRenderer;
import com.behsazan.schemaforge.migration.SchemaDiffEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationArtifactProducerTest {
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-23T02:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void skipsEveryDesiredTableWhenRepositoryIsUnavailable() throws Exception {
        Table desired = tableWithNameLength(100);
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(desired).build();
        ArtifactGenerationContext context = context();

        producer().writeMigrationArtifacts(
                schema, MetadataRepository.empty(), tempDir, DatabasePlatform.ORACLE, context);

        assertFalse(Files.exists(tempDir.resolve("migration/oracle")));
        assertEquals(1, context.ledger().snapshot().size());
        var descriptor = context.ledger().snapshot().get(0);
        assertEquals(ArtifactType.MIGRATION, descriptor.type());
        assertEquals(DatabasePlatform.ORACLE, descriptor.platform());
        assertEquals("APP.CUSTOMER", descriptor.logicalName());
        assertEquals(ArtifactStatus.SKIPPED, descriptor.status());
        assertEquals("MigrationGenerationService", descriptor.provenance().producer());
    }

    @Test
    void skipsWhenLiveTableAlreadyMatchesDesiredTable() throws Exception {
        Table desired = tableWithNameLength(100);
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(desired).build();
        MetadataRepository repository = new InMemoryMetadataRepository(List.of(), List.of(desired));
        ArtifactGenerationContext context = context();

        producer().writeMigrationArtifacts(
                schema, repository, tempDir, DatabasePlatform.ORACLE, context);

        assertFalse(Files.exists(tempDir.resolve("migration/oracle")));
        assertEquals(1, context.ledger().snapshot().size());
        assertEquals(ArtifactStatus.SKIPPED, context.ledger().snapshot().get(0).status());
    }

    @Test
    void writesFlywayArtifactAndLedgerEntryWhenDiffExists() throws Exception {
        Table live = tableWithNameLength(50);
        Table desired = tableWithNameLength(100);
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(desired).build();
        MetadataRepository repository = new InMemoryMetadataRepository(List.of(), List.of(live));
        ArtifactGenerationContext context = context();

        producer().writeMigrationArtifacts(
                schema, repository, tempDir, DatabasePlatform.ORACLE, context);

        Path expected = tempDir.resolve(
                "migration/oracle/V20260823020000000__APP_CUSTOMER_ALTER.sql");
        assertTrue(Files.isRegularFile(expected));
        assertTrue(Files.readString(expected).contains("MODIFY (NAME VARCHAR2(100 CHAR))"));

        assertEquals(1, context.ledger().snapshot().size());
        var descriptor = context.ledger().snapshot().get(0);
        assertEquals(ArtifactStatus.GENERATED, descriptor.status());
        assertEquals("migration/oracle/V20260823020000000__APP_CUSTOMER_ALTER.sql",
                descriptor.relativePath());
        assertEquals("application/sql", descriptor.mediaType());
        assertEquals("MigrationGenerationService", descriptor.provenance().producer());
    }

    private static MigrationArtifactProducer producer() {
        MigrationGenerationService generationService = new MigrationGenerationService(
                new SchemaDiffEngine(),
                new MigrationSqlRenderer(),
                new FlywayMigrationNamer(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)));
        return new MigrationArtifactProducer(
                new ArtifactNamingPolicy(), generationService, new MigrationFileWriter());
    }

    private static ArtifactGenerationContext context() {
        return ArtifactGenerationContext.create(
                ArtifactOrigin.INTERNAL,
                "migration-producer-test",
                "20260823_020000_000",
                OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
    }

    private static Table tableWithNameLength(int length) {
        return Table.builder("APP", "CUSTOMER")
                .addColumn(Column.nullable("NAME", DataType.varchar("VARCHAR2", length)))
                .build();
    }
}
