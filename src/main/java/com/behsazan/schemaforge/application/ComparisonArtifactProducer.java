package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.reporting.SchemaCompareExcelWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;

/**
 * Produces schema-comparison workbooks from prepared document/EA tables and live metadata.
 *
 * <p>This class owns comparison artifact orchestration only. It does not change metadata
 * validation, logical/physical comparison rules, workbook rendering, DBMS behavior, or naming.</p>
 */
public final class ComparisonArtifactProducer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ComparisonArtifactProducer.class);
    private static final String MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PRODUCER = "SchemaCompareExcelWriter";

    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final SchemaCompareExcelWriter compareExcelWriter;

    public ComparisonArtifactProducer(ArtifactNamingPolicy artifactNamingPolicy) {
        this(artifactNamingPolicy, new SchemaCompareExcelWriter());
    }

    public ComparisonArtifactProducer(
            ArtifactNamingPolicy artifactNamingPolicy,
            SchemaCompareExcelWriter compareExcelWriter) {
        this.artifactNamingPolicy = Objects.requireNonNull(
                artifactNamingPolicy, "artifactNamingPolicy must not be null");
        this.compareExcelWriter = Objects.requireNonNull(
                compareExcelWriter, "compareExcelWriter must not be null");
    }

    /**
     * Bulk-preloads live tables into a request-scoped repository cache when the repository
     * has an optimized bulk implementation (currently Oracle). No-op for other repositories.
     */
    public void preloadLiveTables(
            DatabaseSchema schema,
            MetadataRepository repository,
            MetadataComparisonResult metadata) {
        if (!repository.available() || !repository.bulkTableReadOptimized()) {
            return;
        }
        Map<String, Set<String>> tablesBySchema = new LinkedHashMap<>();
        for (Table table : schema.tables()) {
            String schemaName = tableSchema(schema, table);
            if (metadata.schemaKnownToBeMissing(schemaName)) continue;
            tablesBySchema.computeIfAbsent(schemaName, ignored -> new LinkedHashSet<>())
                    .add(table.qualifiedName().name().value());
        }
        for (Map.Entry<String, Set<String>> entry : tablesBySchema.entrySet()) {
            repository.findTables(entry.getKey(), entry.getValue());
            if (!repository.available()) return;
        }
    }

    /** Writes comparison workbooks for all document tables in a prepared schema. */
    public void writeComparisonWorkbooks(
            DatabaseSchema schema,
            MetadataRepository repository,
            MetadataComparisonResult metadata,
            Path output,
            String timestamp,
            DatabasePlatform platform,
            Dialect dialect,
            ArtifactGenerationContext context) throws IOException {

        if (!repository.available()) {
            for (Table table : schema.tables()) {
                context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                        tableSchema(schema, table) + "." + table.qualifiedName().name().value(),
                        PRODUCER, "METADATA_UNAVAILABLE: Metadata repository is disabled or unavailable");
            }
            return;
        }

        for (Table documentTable : schema.tables()) {
            String schemaName = tableSchema(schema, documentTable);
            String tableName = documentTable.qualifiedName().name().value();
            if (metadata.schemaKnownToBeMissing(schemaName)) {
                LOGGER.info("[{}] Comparison workbook skipped; schema not found: {}",
                        platform.name(), schemaName);
                context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                        schemaName + "." + tableName, PRODUCER,
                        "LIVE_SCHEMA_NOT_FOUND: Live schema was not found");
                continue;
            }
            Optional<Table> databaseTable = findTable(repository, schemaName, tableName);
            if (!repository.available()) {
                LOGGER.warn("[{}] Comparison workbook skipped; metadata connection unavailable: {}.{}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                        schemaName + "." + tableName, PRODUCER,
                        "METADATA_UNAVAILABLE: Metadata connection became unavailable");
                continue;
            }
            if (databaseTable.isEmpty()) {
                LOGGER.warn("[{}] Comparison workbook skipped; table not found. requestedSchema={}, requestedTable={}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                        schemaName + "." + tableName, PRODUCER,
                        "LIVE_TABLE_NOT_FOUND: Live table was not found");
                continue;
            }
            LOGGER.info("[{}] Comparison table resolved. requested={}.{}, actual={}",
                    platform.name(), schemaName, tableName,
                    databaseTable.get().qualifiedName().toString());

            byte[] workbook = compareExcelWriter.write(
                    documentTable,
                    databaseTable.get(),
                    usageCounts(documentTable, metadata),
                    platform.name(),
                    dialect);
            String logicalName = schemaName + "." + tableName;
            Path workbookPath = output.resolve(
                    artifactNamingPolicy.comparisonRelativePath(logicalName, platform, timestamp));
            Files.createDirectories(workbookPath.getParent());
            Files.write(workbookPath, workbook);
            context.ledger().generated(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                    logicalName, ArtifactPaths.relative(output, workbookPath),
                    MEDIA_TYPE, PRODUCER);
            LOGGER.info("[{}] Comparison workbook generated: {}", platform.name(), workbookPath.getFileName());
        }
    }

    /** Writes one EA per-table comparison workbook and returns its package-relative path. */
    public String writeEaComparisonWorkbook(
            DatabaseSchema schema,
            Table documentTable,
            MetadataRepository repository,
            MetadataComparisonResult metadata,
            Path artifactRoot,
            DatabasePlatform platform,
            Dialect dialect,
            ArtifactGenerationContext context,
            String timestamp) throws IOException {

        if (!repository.available()) {
            context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                    tableSchema(schema, documentTable) + "." + documentTable.qualifiedName().name().value(),
                    PRODUCER, "METADATA_UNAVAILABLE: Metadata repository is disabled or unavailable");
            return null;
        }

        String schemaName = tableSchema(schema, documentTable);
        String tableName = documentTable.qualifiedName().name().value();
        if (metadata.schemaKnownToBeMissing(schemaName)) {
            LOGGER.info("[{}] EA comparison workbook skipped; schema not found: {}",
                    platform.name(), schemaName);
            context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                    schemaName + "." + tableName, PRODUCER,
                    "LIVE_SCHEMA_NOT_FOUND: Live schema was not found");
            return null;
        }
        Optional<Table> databaseTable = findTable(repository, schemaName, tableName);
        if (!repository.available()) {
            LOGGER.warn("[{}] EA comparison workbook skipped; metadata connection unavailable: {}.{}",
                    platform.name(), schemaName, tableName);
            context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                    schemaName + "." + tableName, PRODUCER,
                    "METADATA_UNAVAILABLE: Metadata connection became unavailable");
            return null;
        }
        if (databaseTable.isEmpty()) {
            LOGGER.warn("[{}] EA comparison workbook skipped; table not found. requestedSchema={}, requestedTable={}",
                    platform.name(), schemaName, tableName);
            context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                    schemaName + "." + tableName, PRODUCER,
                    "LIVE_TABLE_NOT_FOUND: Live table was not found");
            return null;
        }

        byte[] workbook = compareExcelWriter.write(
                documentTable,
                databaseTable.get(),
                usageCounts(documentTable, metadata),
                platform.name(),
                dialect);
        String logicalName = eaArtifactBaseName(schema, documentTable, platform);
        Path relativePath = artifactNamingPolicy.comparisonRelativePath(logicalName, platform, timestamp);
        Path workbookPath = artifactRoot.resolve(relativePath);
        Files.createDirectories(workbookPath.getParent());
        Files.write(workbookPath, workbook);
        context.ledger().generated(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                schemaName + "." + tableName, ArtifactPaths.relative(artifactRoot, workbookPath),
                MEDIA_TYPE, PRODUCER);
        String normalized = ArtifactPaths.relative(artifactRoot, workbookPath);
        LOGGER.info("[{}] EA comparison workbook generated: {}", platform.name(), normalized);
        return normalized;
    }

    private static Optional<Table> findTable(
            MetadataRepository repository, String schemaName, String tableName) {
        Optional<Table> databaseTable = repository.findTable(schemaName, tableName);
        if (databaseTable.isPresent()) {
            return databaseTable;
        }
        List<String> candidateSchemas = repository.findTableSchemas(tableName);
        String matchedSchema = candidateSchemas.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(schemaName))
                .findFirst()
                .orElse(null);
        return matchedSchema == null
                ? Optional.empty()
                : repository.findTable(matchedSchema, tableName);
    }

    private static Map<String, Long> usageCounts(
            Table documentTable, MetadataComparisonResult metadata) {
        Map<String, Long> usageCounts = new LinkedHashMap<>();
        documentTable.columns().forEach(column -> usageCounts.put(
                column.name().normalized(),
                metadata.frequency(MetadataComparisonValidator.path(documentTable, column))));
        return usageCounts;
    }

    private static String tableSchema(DatabaseSchema schema, Table table) {
        return table.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema.name().value());
    }

    private static String eaArtifactBaseName(
            DatabaseSchema schema, Table table, DatabasePlatform platform) {
        String value = tableSchema(schema, table) + "." + table.qualifiedName().name().value();
        return platform == DatabasePlatform.POSTGRESQL
                ? value.toLowerCase(java.util.Locale.ROOT)
                : value;
    }
}
