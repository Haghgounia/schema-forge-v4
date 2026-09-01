package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudPackageGenerator;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudProcedureGenerator;
import com.behsazan.schemaforge.metadata.repository.FailureIsolatingMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Produces metadata-based Oracle and SQL Server CRUD artifacts for prepared schemas.
 *
 * <p>This class owns CRUD artifact orchestration only. It preserves the existing
 * metadata lookup, skip/failure summary, generators, grant-derived options, naming,
 * and Artifact Ledger semantics.</p>
 */
public final class CrudArtifactProducer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrudArtifactProducer.class);
    private static final String ORCHESTRATION_PRODUCER = "SchemaForgeApiService";

    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final MetadataRepositoryResolver metadataRepositoryResolver;
    private final OracleCrudPackageGenerator oracleCrudGenerator;
    private final SqlServerCrudProcedureGenerator sqlServerCrudGenerator;
    private final OracleCrudGenerationOptions oracleCrudOptions;
    private final SqlServerCrudGenerationOptions sqlServerCrudOptions;

    public CrudArtifactProducer(
            ArtifactNamingPolicy artifactNamingPolicy,
            MetadataRepositoryResolver metadataRepositoryResolver,
            GrantProperties grantProperties) {
        this(
                artifactNamingPolicy,
                metadataRepositoryResolver,
                new OracleCrudPackageGenerator(),
                new SqlServerCrudProcedureGenerator(),
                OracleCrudGenerationOptions.ofGrantees(crudGrantees(grantProperties)),
                SqlServerCrudGenerationOptions.ofGrantees(crudGrantees(grantProperties)));
    }

    CrudArtifactProducer(
            ArtifactNamingPolicy artifactNamingPolicy,
            MetadataRepositoryResolver metadataRepositoryResolver,
            OracleCrudPackageGenerator oracleCrudGenerator,
            SqlServerCrudProcedureGenerator sqlServerCrudGenerator,
            OracleCrudGenerationOptions oracleCrudOptions,
            SqlServerCrudGenerationOptions sqlServerCrudOptions) {
        this.artifactNamingPolicy = Objects.requireNonNull(
                artifactNamingPolicy, "artifactNamingPolicy must not be null");
        this.metadataRepositoryResolver = Objects.requireNonNull(
                metadataRepositoryResolver, "metadataRepositoryResolver must not be null");
        this.oracleCrudGenerator = Objects.requireNonNull(
                oracleCrudGenerator, "oracleCrudGenerator must not be null");
        this.sqlServerCrudGenerator = Objects.requireNonNull(
                sqlServerCrudGenerator, "sqlServerCrudGenerator must not be null");
        this.oracleCrudOptions = Objects.requireNonNull(
                oracleCrudOptions, "oracleCrudOptions must not be null");
        this.sqlServerCrudOptions = Objects.requireNonNull(
                sqlServerCrudOptions, "sqlServerCrudOptions must not be null");
    }

    /** Writes Oracle/SQL Server metadata CRUD artifacts plus the per-document summary CSV. */
    public void writeMetadataCrudArtifacts(
            DatabaseSchema documentSchema,
            Path output,
            String baseName,
            String timestamp,
            ArtifactGenerationContext context) throws IOException {
        writeMetadataCrudArtifacts(documentSchema, output, baseName, timestamp, context,
                Set.of(DatabasePlatform.values()));
    }

    public void writeMetadataCrudArtifacts(
            DatabaseSchema documentSchema,
            Path output,
            String baseName,
            String timestamp,
            ArtifactGenerationContext context,
            Set<DatabasePlatform> platforms) throws IOException {
        writeMetadataCrudArtifacts(documentSchema, output, baseName, timestamp, context, platforms, Map.of());
    }

    /**
     * Writes CRUD artifacts while reusing request-scoped repositories already used by comparison/migration.
     * This avoids re-reading the same live tables and preserves schema/table existence caches.
     */
    public void writeMetadataCrudArtifacts(
            DatabaseSchema documentSchema,
            Path output,
            String baseName,
            String timestamp,
            ArtifactGenerationContext context,
            Set<DatabasePlatform> platforms,
            Map<DatabasePlatform, MetadataRepository> requestRepositories) throws IOException {

        boolean oracleSelected = platforms.contains(DatabasePlatform.ORACLE);
        boolean sqlServerSelected = platforms.contains(DatabasePlatform.SQLSERVER);
        if (!oracleSelected && !sqlServerSelected) {
            return;
        }

        List<String> summary = new ArrayList<>();
        summary.add("platform,schema,table,status,file,error");

        if (oracleSelected) {
            writeForPlatform(documentSchema, output, timestamp, DatabasePlatform.ORACLE, summary, context,
                    requestRepositories.get(DatabasePlatform.ORACLE));
        }
        if (sqlServerSelected) {
            writeForPlatform(documentSchema, output, timestamp, DatabasePlatform.SQLSERVER, summary, context,
                    requestRepositories.get(DatabasePlatform.SQLSERVER));
        }

        Path summaryPath = output.resolve(
                artifactNamingPolicy.metadataCrudSummaryRelativePath(baseName, timestamp));
        Files.createDirectories(summaryPath.getParent());
        Files.writeString(summaryPath, String.join("\n", summary) + "\n", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                baseName + ":metadata-crud", ArtifactPaths.relative(output, summaryPath),
                "text/csv", ORCHESTRATION_PRODUCER);
    }

    private void writeForPlatform(
            DatabaseSchema documentSchema,
            Path output,
            String timestamp,
            DatabasePlatform platform,
            List<String> summary,
            ArtifactGenerationContext context,
            MetadataRepository requestRepository) {

        MetadataRepository repository = requestRepository != null
                ? requestRepository
                : FailureIsolatingMetadataRepository.wrap(platform, metadataRepositoryResolver.resolve(platform));

        Map<String, Boolean> schemaAvailability = new java.util.LinkedHashMap<>();
        boolean useSchemaFastPath = requestRepository != null && repository.schemaExistenceAuthoritative();
        for (Table documentTable : documentSchema.tables()) {
            String schemaName = tableSchema(documentSchema, documentTable);
            String tableName = documentTable.qualifiedName().name().value();

            if (documentTable.primaryKey().isEmpty()) {
                summary.add(csvLine(platform.name(), schemaName, tableName,
                        "SKIPPED_NO_PRIMARY_KEY", "",
                        "Document table has no primary key"));
                LOGGER.info("[{}] REST CRUD artifact skipped; document table has no primary key: {}.{}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.CRUD, platform,
                        schemaName + "." + tableName, ORCHESTRATION_PRODUCER,
                        "DOCUMENT_NO_PRIMARY_KEY: Document table has no primary key");
                continue;
            }

            if (!repository.available()) {
                summary.add(csvLine(platform.name(), schemaName, tableName,
                        "SKIPPED_METADATA_UNAVAILABLE", "",
                        "Metadata repository is disabled or unavailable"));
                context.ledger().skipped(context, ArtifactType.CRUD, platform,
                        schemaName + "." + tableName, ORCHESTRATION_PRODUCER,
                        "METADATA_UNAVAILABLE: Metadata repository is disabled or unavailable");
                continue;
            }

            if (useSchemaFastPath) {
                String normalizedSchema = schemaName.trim().toUpperCase(Locale.ROOT);
                Boolean exists = schemaAvailability.get(normalizedSchema);
                if (exists == null) {
                    exists = repository.schemaExists(schemaName);
                    schemaAvailability.put(normalizedSchema, exists);
                }
                if (!repository.available()) {
                    summary.add(csvLine(platform.name(), schemaName, tableName,
                            "SKIPPED_METADATA_UNAVAILABLE", "",
                            "Metadata connection became unavailable"));
                    context.ledger().skipped(context, ArtifactType.CRUD, platform,
                            schemaName + "." + tableName, ORCHESTRATION_PRODUCER,
                            "METADATA_UNAVAILABLE: Metadata connection became unavailable");
                    continue;
                }
                if (Boolean.FALSE.equals(exists)) {
                    summary.add(csvLine(platform.name(), schemaName, tableName,
                            "SKIPPED_TABLE_NOT_FOUND", "",
                            "Live schema was not found"));
                    LOGGER.info("[{}] REST CRUD artifact skipped; schema not found: {}",
                            platform.name(), schemaName);
                    context.ledger().skipped(context, ArtifactType.CRUD, platform,
                            schemaName + "." + tableName, ORCHESTRATION_PRODUCER,
                            "LIVE_SCHEMA_NOT_FOUND: Live schema was not found");
                    continue;
                }
            }

            try {
                Optional<Table> liveTable = findMetadataTable(repository, schemaName, tableName);
                if (!repository.available()) {
                    summary.add(csvLine(platform.name(), schemaName, tableName,
                            "SKIPPED_METADATA_UNAVAILABLE", "",
                            "Metadata connection became unavailable"));
                    LOGGER.warn("[{}] REST CRUD artifact skipped; metadata connection unavailable: {}.{}",
                            platform.name(), schemaName, tableName);
                    context.ledger().skipped(context, ArtifactType.CRUD, platform,
                            schemaName + "." + tableName, ORCHESTRATION_PRODUCER,
                            "METADATA_UNAVAILABLE: Metadata connection became unavailable");
                    continue;
                }
                if (liveTable.isEmpty()) {
                    summary.add(csvLine(platform.name(), schemaName, tableName,
                            "SKIPPED_TABLE_NOT_FOUND", "",
                            "Live table was not found"));
                    LOGGER.warn("[{}] REST CRUD artifact skipped; live table not found: {}.{}",
                            platform.name(), schemaName, tableName);
                    context.ledger().skipped(context, ArtifactType.CRUD, platform,
                            schemaName + "." + tableName, ORCHESTRATION_PRODUCER,
                            "LIVE_TABLE_NOT_FOUND: Live table was not found");
                    continue;
                }
                if (liveTable.get().primaryKey().isEmpty()) {
                    summary.add(csvLine(platform.name(), schemaName, tableName,
                            "SKIPPED_NO_PRIMARY_KEY", "",
                            "Live table has no primary key"));
                    LOGGER.info("[{}] REST CRUD artifact skipped; live table has no primary key: {}.{}",
                            platform.name(), schemaName, tableName);
                    context.ledger().skipped(context, ArtifactType.CRUD, platform,
                            schemaName + "." + tableName, ORCHESTRATION_PRODUCER,
                            "LIVE_TABLE_NO_PRIMARY_KEY: Live table has no primary key");
                    continue;
                }

                String logicalName = schemaName.toUpperCase(Locale.ROOT) + "."
                        + tableName.toUpperCase(Locale.ROOT);
                // Preserve the existing naming-policy validation call before rendering/writing.
                artifactNamingPolicy.crudFileName(logicalName, platform, timestamp);
                String sql = platform == DatabasePlatform.ORACLE
                        ? oracleCrudGenerator.generate(liveTable.get(), oracleCrudOptions)
                        : sqlServerCrudGenerator.generate(liveTable.get(), sqlServerCrudOptions);

                Path crudRelativePath = artifactNamingPolicy.crudRelativePath(
                        logicalName, platform, timestamp);
                Path crudPath = output.resolve(crudRelativePath);
                Files.createDirectories(crudPath.getParent());
                String relativeFileName = ArtifactPaths.relative(output, crudPath);
                Files.writeString(crudPath, sql, StandardCharsets.UTF_8);
                context.ledger().generated(context, ArtifactType.CRUD, platform, logicalName,
                        ArtifactPaths.relative(output, crudPath), "application/sql",
                        platform == DatabasePlatform.ORACLE
                                ? "OracleCrudPackageGenerator" : "SqlServerCrudProcedureGenerator");
                summary.add(csvLine(platform.name(), schemaName, tableName,
                        "GENERATED", relativeFileName, ""));
                LOGGER.info("[{}] REST CRUD artifact generated: {}",
                        platform.name(), relativeFileName);
            } catch (Exception exception) {
                String message = safeMessage(exception);
                summary.add(csvLine(platform.name(), schemaName, tableName,
                        "FAILED", "", exception.getClass().getSimpleName() + ": " + message));
                LOGGER.warn("[{}] REST CRUD artifact generation failed for {}.{}: {}",
                        platform.name(), schemaName, tableName, message);
                context.ledger().failed(context, ArtifactType.CRUD, platform,
                        schemaName + "." + tableName, ORCHESTRATION_PRODUCER);
            }
        }
    }

    private static Optional<Table> findMetadataTable(
            MetadataRepository repository, String schemaName, String tableName) {
        Optional<Table> table = repository.findTable(schemaName, tableName);
        if (table.isPresent()) {
            return table;
        }
        String matchedSchema = repository.findTableSchemas(tableName).stream()
                .filter(candidate -> candidate.equalsIgnoreCase(schemaName))
                .findFirst()
                .orElse(null);
        return matchedSchema == null
                ? Optional.empty()
                : repository.findTable(matchedSchema, tableName);
    }

    private static List<String> crudGrantees(GrantProperties grantProperties) {
        Objects.requireNonNull(grantProperties, "grantProperties must not be null");
        return grantProperties.getGrants().stream()
                .filter(CrudArtifactProducer::hasWritePrivilege)
                .map(GrantProperties.GrantRule::getGrantee)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static boolean hasWritePrivilege(GrantProperties.GrantRule rule) {
        if (rule == null || rule.getPrivileges() == null) {
            return false;
        }
        return rule.getPrivileges().stream()
                .filter(value -> value != null)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.equals("INSERT")
                        || value.equals("UPDATE")
                        || value.equals("DELETE"));
    }

    private static String tableSchema(DatabaseSchema schema, Table table) {
        return table.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema.name().value());
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
