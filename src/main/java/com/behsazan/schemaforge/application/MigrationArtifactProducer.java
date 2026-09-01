package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.migration.MigrationArtifact;
import com.behsazan.schemaforge.migration.MigrationFileWriter;
import com.behsazan.schemaforge.migration.MigrationGenerationService;
import com.behsazan.schemaforge.migration.MigrationRenderOptions;
import com.behsazan.schemaforge.migration.TableMigrationPlan;
import com.behsazan.schemaforge.migration.TableObjectChangeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Produces Flyway-compatible migration artifacts from a prepared desired schema and live metadata.
 *
 * <p>This class owns migration artifact orchestration only. It does not change diff rules, SQL
 * rendering, Flyway naming, metadata lookup semantics, or migration safety options.</p>
 */
public final class MigrationArtifactProducer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationArtifactProducer.class);

    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final MigrationGenerationService migrationGenerationService;
    private final MigrationFileWriter migrationFileWriter;

    public MigrationArtifactProducer(ArtifactNamingPolicy artifactNamingPolicy) {
        this(artifactNamingPolicy, new MigrationGenerationService(), new MigrationFileWriter());
    }

    public MigrationArtifactProducer(
            ArtifactNamingPolicy artifactNamingPolicy,
            NumericMappingStrategy numericMappingStrategy) {
        this(artifactNamingPolicy, new MigrationGenerationService(numericMappingStrategy), new MigrationFileWriter());
    }

    public MigrationArtifactProducer(
            ArtifactNamingPolicy artifactNamingPolicy,
            MigrationGenerationService migrationGenerationService,
            MigrationFileWriter migrationFileWriter) {
        this.artifactNamingPolicy = Objects.requireNonNull(
                artifactNamingPolicy, "artifactNamingPolicy must not be null");
        this.migrationGenerationService = Objects.requireNonNull(
                migrationGenerationService, "migrationGenerationService must not be null");
        this.migrationFileWriter = Objects.requireNonNull(
                migrationFileWriter, "migrationFileWriter must not be null");
    }

    /**
     * Writes additional Flyway-compatible ALTER scripts for desired tables that already exist.
     *
     * <p>This remains deliberately additive: normal CREATE DDL is generated independently before
     * this method is called. A missing live table, an unavailable metadata repository, or an empty
     * diff never suppresses the normal CREATE artifact.</p>
     */
    public void writeMigrationArtifacts(
            DatabaseSchema schema,
            MetadataRepository repository,
            Path output,
            DatabasePlatform platform,
            ArtifactGenerationContext context) throws IOException {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(context, "context must not be null");

        if (!repository.available()) {
            for (Table table : schema.tables()) {
                context.ledger().skipped(context, ArtifactType.MIGRATION, platform,
                        tableSchema(schema, table) + "." + table.qualifiedName().name().value(),
                        "MigrationGenerationService",
                        "METADATA_UNAVAILABLE: Metadata repository is disabled or unavailable");
            }
            return;
        }

        Path migrationDirectory = output.resolve(artifactNamingPolicy.migrationDirectory(platform));
        List<PendingMigration> pending = new ArrayList<>();
        int sourceOrder = 0;

        for (Table desiredTable : schema.tables()) {
            String schemaName = desiredTable.qualifiedName().schemaName()
                    .map(identifier -> identifier.value())
                    .orElse(schema.name().value());
            String tableName = desiredTable.qualifiedName().name().value();

            var liveTable = repository.findTable(schemaName, tableName);
            if (!repository.available()) {
                LOGGER.warn("[{}] Migration metadata connection unavailable; remaining migration artifacts will be skipped.",
                        platform.name());
                markRemainingSkipped(schema, desiredTable, platform, context);
                break;
            }
            if (liveTable.isEmpty()) {
                LOGGER.debug("[{}] Migration skipped; live table not found: {}.{}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.MIGRATION, platform,
                        schemaName + "." + tableName, "MigrationGenerationService",
                        "LIVE_TABLE_NOT_FOUND: Live table was not found");
                sourceOrder++;
                continue;
            }

            TableMigrationPlan plan = migrationGenerationService.plan(platform, liveTable.get(), desiredTable);
            if (plan.empty()) {
                LOGGER.info("[{}] Migration not required; live table already matches desired columns: {}.{}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.MIGRATION, platform,
                        schemaName + "." + tableName, "MigrationGenerationService",
                        "NO_SCHEMA_DIFF: Live table already matches desired schema");
                sourceOrder++;
                continue;
            }
            pending.add(new PendingMigration(plan, schemaName, tableName, sourceOrder++));
        }

        // Flyway applies files by version, not by filesystem write order. Generate file names only
        // after sorting so Oracle name-only RENAME migrations release legacy/collapsed names before
        // later ADD migrations can claim those names in other tables. Stable source order is retained
        // within each priority bucket.
        pending.sort(Comparator
                .comparingInt((PendingMigration item) -> containsRename(item.plan()) ? 0 : 1)
                .thenComparingInt(PendingMigration::sourceOrder));

        for (PendingMigration item : pending) {
            MigrationArtifact artifact = migrationGenerationService.generate(
                    item.plan(), MigrationRenderOptions.safeDefaults());
            Path written = migrationFileWriter.write(migrationDirectory, artifact);
            context.ledger().generated(context, ArtifactType.MIGRATION, platform,
                    item.schemaName() + "." + item.tableName(), ArtifactPaths.relative(output, written),
                    "application/sql", "MigrationGenerationService");
            LOGGER.info("[{}] Flyway migration generated: {}",
                    platform.name(), output.relativize(written));
        }

    }



    private static boolean containsRename(TableMigrationPlan plan) {
        return plan.objectChanges().stream()
                .anyMatch(change -> change.kind() == TableObjectChangeKind.RENAME);
    }

    private record PendingMigration(
            TableMigrationPlan plan, String schemaName, String tableName, int sourceOrder) { }

    private static void markRemainingSkipped(
            DatabaseSchema schema,
            Table currentTable,
            DatabasePlatform platform,
            ArtifactGenerationContext context) {
        boolean currentReached = false;
        for (Table table : schema.tables()) {
            if (table == currentTable) {
                currentReached = true;
            }
            if (currentReached) {
                context.ledger().skipped(context, ArtifactType.MIGRATION, platform,
                        tableSchema(schema, table) + "." + table.qualifiedName().name().value(),
                        "MigrationGenerationService",
                        "METADATA_UNAVAILABLE: Metadata connection became unavailable");
            }
        }
    }

    private static String tableSchema(DatabaseSchema schema, Table table) {
        return table.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema.name().value());
    }
}
