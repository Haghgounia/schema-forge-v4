package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;

import java.util.Objects;

/**
 * Generates a Flyway-compatible ALTER migration by comparing one desired document table
 * with the current live table supplied by a metadata repository.
 */
public final class MigrationGenerationService {
    private final SchemaDiffEngine diffEngine;
    private final MigrationSqlRenderer renderer;
    private final FlywayMigrationNamer namer;

    public MigrationGenerationService() {
        this(DialectFactory.configuredNumericMappingStrategy());
    }

    public MigrationGenerationService(NumericMappingStrategy numericMappingStrategy) {
        this(new SchemaDiffEngine(numericMappingStrategy),
                new MigrationSqlRenderer(numericMappingStrategy),
                new FlywayMigrationNamer());
    }

    public MigrationGenerationService(
            SchemaDiffEngine diffEngine,
            MigrationSqlRenderer renderer,
            FlywayMigrationNamer namer) {
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.namer = Objects.requireNonNull(namer, "namer must not be null");
    }

    public MigrationArtifact generate(
            DatabasePlatform platform,
            MetadataRepository metadataRepository,
            Table desiredTable,
            MigrationRenderOptions options) {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(metadataRepository, "metadataRepository must not be null");
        Objects.requireNonNull(desiredTable, "desiredTable must not be null");

        String schema = desiredTable.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElseThrow(() -> new IllegalArgumentException(
                        "migration generation requires a schema-qualified table"));
        String table = desiredTable.qualifiedName().name().value();
        Table liveTable = metadataRepository.findTable(schema, table)
                .orElseThrow(() -> new IllegalStateException(
                        "live table was not found for migration comparison: " + desiredTable.qualifiedName()));

        return generate(platform, liveTable, desiredTable, options);
    }

    /**
     * Generates one migration from an already-resolved live table. This overload lets callers
     * resolve metadata once and still keep CREATE-DDL generation independent from migration output.
     */
    public MigrationArtifact generate(
            DatabasePlatform platform,
            Table liveTable,
            Table desiredTable,
            MigrationRenderOptions options) {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(liveTable, "liveTable must not be null");
        Objects.requireNonNull(desiredTable, "desiredTable must not be null");

        TableMigrationPlan plan = plan(platform, liveTable, desiredTable);
        return generate(plan, options);
    }

    public TableMigrationPlan plan(
            DatabasePlatform platform,
            Table liveTable,
            Table desiredTable) {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(liveTable, "liveTable must not be null");
        Objects.requireNonNull(desiredTable, "desiredTable must not be null");
        return diffEngine.diff(platform, liveTable, desiredTable);
    }

    public MigrationArtifact generate(
            TableMigrationPlan plan,
            MigrationRenderOptions options) {
        Objects.requireNonNull(plan, "plan must not be null");
        return new MigrationArtifact(
                namer.fileName(plan.desiredTable()),
                renderer.render(plan, options),
                plan);
    }
}
