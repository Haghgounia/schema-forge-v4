package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifest;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.parser.ea.EnterpriseArchitectXmlParser;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates Enterprise Architect XML/XMI preparation and multi-table artifact generation.
 *
 * <p>The EA parser, schema preparation, DBMS generators, metadata comparison, migration, CRUD,
 * diagram, naming, manifest and Ledger contracts are unchanged. This class only moves the existing
 * EA workflow out of the REST-facing facade.</p>
 */
public final class EaGenerationOrchestrator {
    private static final String LEGACY_RUN_SCRIPT_PRODUCER = "SchemaForgeApiService";

    private final SchemaPreparationService preparationService;
    private final MetadataRepositoryResolver metadataRepositoryResolver;
    private final EaImportProperties eaImportProperties;
    private final ArtifactManifestWriter artifactManifestWriter;
    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final ArtifactPackageBuilder artifactPackageBuilder;
    private final DiagramArtifactProducer diagramArtifactProducer;
    private final MigrationArtifactProducer migrationArtifactProducer;
    private final ComparisonArtifactProducer comparisonArtifactProducer;
    private final CrudArtifactProducer crudArtifactProducer;
    private final OracleDdlSanityChecker oracleDdlSanityChecker;
    private final NumericMappingStrategy numericMappingStrategy;

    public EaGenerationOrchestrator(
            SchemaPreparationService preparationService,
            MetadataRepositoryResolver metadataRepositoryResolver,
            EaImportProperties eaImportProperties,
            ArtifactManifestWriter artifactManifestWriter,
            ArtifactNamingPolicy artifactNamingPolicy,
            ArtifactPackageBuilder artifactPackageBuilder,
            DiagramArtifactProducer diagramArtifactProducer,
            MigrationArtifactProducer migrationArtifactProducer,
            ComparisonArtifactProducer comparisonArtifactProducer,
            CrudArtifactProducer crudArtifactProducer,
            OracleDdlSanityChecker oracleDdlSanityChecker) {
        this(preparationService, metadataRepositoryResolver, eaImportProperties, artifactManifestWriter,
                artifactNamingPolicy, artifactPackageBuilder, diagramArtifactProducer, migrationArtifactProducer,
                comparisonArtifactProducer, crudArtifactProducer, oracleDdlSanityChecker,
                DialectFactory.configuredNumericMappingStrategy());
    }

    public EaGenerationOrchestrator(
            SchemaPreparationService preparationService,
            MetadataRepositoryResolver metadataRepositoryResolver,
            EaImportProperties eaImportProperties,
            ArtifactManifestWriter artifactManifestWriter,
            ArtifactNamingPolicy artifactNamingPolicy,
            ArtifactPackageBuilder artifactPackageBuilder,
            DiagramArtifactProducer diagramArtifactProducer,
            MigrationArtifactProducer migrationArtifactProducer,
            ComparisonArtifactProducer comparisonArtifactProducer,
            CrudArtifactProducer crudArtifactProducer,
            OracleDdlSanityChecker oracleDdlSanityChecker,
            NumericMappingStrategy numericMappingStrategy) {
        this.preparationService = Objects.requireNonNull(preparationService, "preparationService must not be null");
        this.metadataRepositoryResolver = Objects.requireNonNull(
                metadataRepositoryResolver, "metadataRepositoryResolver must not be null");
        this.eaImportProperties = Objects.requireNonNull(eaImportProperties, "eaImportProperties must not be null");
        this.artifactManifestWriter = Objects.requireNonNull(
                artifactManifestWriter, "artifactManifestWriter must not be null");
        this.artifactNamingPolicy = Objects.requireNonNull(
                artifactNamingPolicy, "artifactNamingPolicy must not be null");
        this.artifactPackageBuilder = Objects.requireNonNull(
                artifactPackageBuilder, "artifactPackageBuilder must not be null");
        this.diagramArtifactProducer = Objects.requireNonNull(
                diagramArtifactProducer, "diagramArtifactProducer must not be null");
        this.migrationArtifactProducer = Objects.requireNonNull(
                migrationArtifactProducer, "migrationArtifactProducer must not be null");
        this.comparisonArtifactProducer = Objects.requireNonNull(
                comparisonArtifactProducer, "comparisonArtifactProducer must not be null");
        this.crudArtifactProducer = Objects.requireNonNull(
                crudArtifactProducer, "crudArtifactProducer must not be null");
        this.oracleDdlSanityChecker = Objects.requireNonNull(
                oracleDdlSanityChecker, "oracleDdlSanityChecker must not be null");
        this.numericMappingStrategy = Objects.requireNonNull(
                numericMappingStrategy, "numericMappingStrategy must not be null");
    }

    /** Parses and prepares an EA XML/XMI input while preserving the existing schema resolution policy. */
    public PreparedSchema prepare(
            MultipartFile file,
            String sourceName,
            String schemaName) throws IOException {
        return prepare(file, sourceName, schemaName, null);
    }

    public PreparedSchema prepare(
            MultipartFile file,
            String sourceName,
            String schemaName,
            AuditGenerationOptions auditOptions) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        DatabaseSchema parsed;
        try (InputStream inputStream = file.getInputStream()) {
            parsed = new EnterpriseArchitectXmlParser(
                    eaImportProperties.getDefaultSchema(), true)
                    .parse(sourceName, inputStream, schemaName);
        }
        return auditOptions == null
                ? preparationService.prepare(parsed)
                : preparationService.prepare(parsed, auditOptions);
    }

    /** Generates and packages all established EA artifacts for an already prepared schema. */
    public byte[] generate(
            PreparedSchema prepared,
            String baseName,
            ArtifactGenerationContext context) throws IOException {
        return generate(prepared, baseName, context, null);
    }

    public byte[] generate(
            PreparedSchema prepared,
            String baseName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions) throws IOException {
        Objects.requireNonNull(prepared, "prepared must not be null");
        Objects.requireNonNull(baseName, "baseName must not be null");
        Objects.requireNonNull(context, "context must not be null");

        Path work = Files.createTempDirectory("schemaforge-ea-");
        try {
            Path output = Files.createDirectories(work.resolve("output"));
            writeEaPerTableOutputs(prepared, output, baseName, context, auditOptions);
            return artifactPackageBuilder.zipDirectory(output);
        } finally {
            artifactPackageBuilder.deleteRecursively(work);
        }
    }

    private void writeEaPerTableOutputs(
            PreparedSchema prepared,
            Path output,
            String baseName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions) throws IOException {
        DatabaseSchema schema = prepared.schema();
        ValidationReport report = prepared.validationReport();
        List<ValidationIssue> jsonIssues = new ArrayList<>(report.issues());
        String timestamp = context.generationTimestamp();

        DependencyOrder dependencyOrder = dependencyOrder(schema.tables());

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Dialect dialect = DialectFactory.create(platform, numericMappingStrategy);
            MetadataRepository repository = metadataRepositoryResolver.resolve(platform);
            MetadataComparisonResult metadata = new MetadataComparisonValidator(dialect, repository).validate(schema);
            metadata.issues().stream()
                    .map(issue -> new ValidationIssue(
                            issue.severity(),
                            issue.code(),
                            "dialects." + platform.commandLineName() + "." + issue.path(),
                            "[" + platform.name() + "] " + issue.message()))
                    .forEach(jsonIssues::add);

            for (Table table : schema.tables()) {
                DatabaseSchema tableSchema = singleTableSchema(schema, table);
                ValidationReport tableReport = validationForTable(report, table);
                MetadataComparisonResult tableMetadata = metadataForTable(metadata, table);
                String sql = new DdlGenerator(dialect, schema)
                        .generate(tableSchema, tableReport, tableMetadata);
                Path ddlRelativePath = artifactNamingPolicy.ddlRelativePath(
                        eaArtifactBaseName(schema, table, platform), platform, timestamp);
                String sqlFileName = ddlRelativePath.getFileName().toString();
                requireValidOracleDdl(platform, sql, sqlFileName);
                Path ddlPath = output.resolve(ddlRelativePath);
                Files.createDirectories(ddlPath.getParent());
                Files.writeString(ddlPath, sql, StandardCharsets.UTF_8);
                context.ledger().generated(context, ArtifactType.DDL, platform,
                        tableSchema(schema, table) + "." + table.qualifiedName().name().value(),
                        ArtifactPaths.relative(output, ddlPath), "application/sql", "DdlGenerator");

                comparisonArtifactProducer.writeEaComparisonWorkbook(
                        schema, table, repository, metadata, output, platform, dialect,
                        context, timestamp);
            }

            // Per-table CREATE scripts above remain unconditional. If matching live tables exist,
            // emit additional Flyway migrations under migration/<platform>/.
            migrationArtifactProducer.writeMigrationArtifacts(schema, repository, output, platform, context);
            writeEaRunAll(schema, platform, dependencyOrder, baseName, timestamp, output, context);
        }

        crudArtifactProducer.writeMetadataCrudArtifacts(schema, output, baseName, timestamp, context);
        diagramArtifactProducer.writeMermaidArtifact(schema, output, baseName, timestamp, context);
        diagramArtifactProducer.writeGraphvizArtifact(schema, output, baseName, timestamp, context);
        diagramArtifactProducer.writeConceptualErdArtifacts(schema, output, baseName, timestamp, context);

        ValidationReport jsonReport = new ValidationReport(
                jsonIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                jsonIssues);
        Path modelPath = output.resolve(artifactNamingPolicy.canonicalJsonRelativePath(baseName, timestamp));
        Files.createDirectories(modelPath.getParent());
        new JsonExporter().write(modelPath, schema, jsonReport);
        context.ledger().generated(context, ArtifactType.CANONICAL_JSON, null,
                baseName, ArtifactPaths.relative(output, modelPath),
                "application/json", "JsonExporter");

        ArtifactManifest.EnterpriseArchitectExtension eaExtension =
                new ArtifactManifest.EnterpriseArchitectExtension(
                        dependencyOrder.tables().stream()
                                .map(table -> table.qualifiedName().toString()).toList(),
                        dependencyOrder.cyclicTables().stream()
                                .map(table -> table.qualifiedName().toString()).toList());
        Map<String, Object> manifestExtensions = new LinkedHashMap<>();
        manifestExtensions.put("enterpriseArchitect", eaExtension);
        Map<String, Object> generationOptions = new LinkedHashMap<>();
        generationOptions.put("numericMapping", Map.of("strategy", numericMappingStrategy.name()));
        if (auditOptions != null) {
            generationOptions.put("audit", auditOptions.manifestValue());
        }
        manifestExtensions.put("generationOptions", generationOptions);
        artifactManifestWriter.write(
                output, context, baseName,
                List.of(new ArtifactManifestAssembler.ModelInput(
                        context.sourceName(), schema, jsonReport)),
                manifestExtensions);
    }

    private void requireValidOracleDdl(DatabasePlatform platform, String sql, String source) {
        if (platform == DatabasePlatform.ORACLE) {
            oracleDdlSanityChecker.requireValid(sql, source);
        }
    }

    private void writeEaRunAll(
            DatabaseSchema schema,
            DatabasePlatform platform,
            DependencyOrder order,
            String sourceBaseName,
            String timestamp,
            Path artifactRoot,
            ArtifactGenerationContext context) throws IOException {

        Path runAllRelativePath = artifactNamingPolicy.runAllRelativePath(
                sourceBaseName, platform, timestamp);
        Path runAllPath = artifactRoot.resolve(runAllRelativePath);
        Files.createDirectories(runAllPath.getParent());

        StringBuilder script = new StringBuilder();
        String comment = "--";
        script.append(comment).append(" SchemaForge EA run-all script").append(System.lineSeparator())
                .append(comment).append(" Schema: ").append(schema.name().value()).append(System.lineSeparator())
                .append(comment).append(" Generated: ").append(timestamp).append(System.lineSeparator());
        if (!order.cyclicTables().isEmpty()) {
            script.append(comment)
                    .append(" WARNING: cyclic internal foreign-key dependencies detected for: ")
                    .append(order.cyclicTables().stream()
                            .map(table -> table.qualifiedName().toString())
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append(System.lineSeparator());
        }
        script.append(System.lineSeparator());

        for (Table table : order.tables()) {
            Path ddlRelativePath = artifactNamingPolicy.ddlRelativePath(
                    eaArtifactBaseName(schema, table, platform), platform, timestamp);
            String reference = artifactPackageBuilder.normalizePath(
                    runAllPath.getParent().relativize(artifactRoot.resolve(ddlRelativePath)));
            switch (platform) {
                case ORACLE -> script.append("@@").append(reference);
                case POSTGRESQL -> script.append("\\ir ").append(reference);
                case DB2_ZOS -> script.append("-- Execute in this order: ").append(reference);
                case SQLSERVER -> script.append(":r ").append(reference);
                case MYSQL -> script.append("-- Execute in this order: ").append(reference);
            }
            script.append(System.lineSeparator());
        }
        Files.writeString(runAllPath, script.toString(), StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.RUN_SCRIPT, platform,
                sourceBaseName, ArtifactPaths.relative(artifactRoot, runAllPath),
                "application/sql", LEGACY_RUN_SCRIPT_PRODUCER);
    }

    private DatabaseSchema singleTableSchema(DatabaseSchema source, Table table) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder(source.name().value())
                .description(source.description().value());
        source.metadata().forEach(builder::metadata);
        relevantSequences(source, table).forEach(builder::addSequence);
        builder.addTable(table);
        return builder.build();
    }

    private List<Sequence> relevantSequences(DatabaseSchema schema, Table table) {
        if (schema.sequences().isEmpty()) return List.of();
        List<Sequence> result = new ArrayList<>();
        for (Sequence sequence : schema.sequences()) {
            String simpleName = sequence.qualifiedName().name().value().toUpperCase(Locale.ROOT);
            String qualifiedName = sequence.qualifiedName().toString().toUpperCase(Locale.ROOT);
            boolean used = table.columns().stream()
                    .map(column -> column.defaultValue())
                    .filter(DefaultValue::isPresent)
                    .map(defaultValue -> defaultValue.expression().toUpperCase(Locale.ROOT))
                    .anyMatch(expression -> expression.contains(qualifiedName) || expression.contains(simpleName));
            if (used) result.add(sequence);
        }
        return result;
    }

    private ValidationReport validationForTable(ValidationReport report, Table table) {
        List<ValidationIssue> issues = report.issues().stream()
                .filter(issue -> issueAppliesToTable(issue, table))
                .toList();
        return new ValidationReport(
                issues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                issues);
    }

    private MetadataComparisonResult metadataForTable(MetadataComparisonResult metadata, Table table) {
        String prefix = MetadataComparisonValidator.tablePath(table);
        Map<String, Long> frequencies = metadata.columnFrequencies().entrySet().stream()
                .filter(entry -> entry.getKey().equals(prefix) || entry.getKey().startsWith(prefix + "."))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, String> resolvedSchemas = metadata.resolvedForeignKeySchemas().entrySet().stream()
                .filter(entry -> entry.getKey().equals(prefix) || entry.getKey().startsWith(prefix + "."))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ValidationIssue> issues = metadata.issues().stream()
                .filter(issue -> issueAppliesToTable(issue, table))
                .toList();
        return new MetadataComparisonResult(issues, frequencies, resolvedSchemas, metadata.metadataAvailable());
    }

    private boolean issueAppliesToTable(ValidationIssue issue, Table table) {
        String path = issue.path();
        if (path == null || path.isBlank() || !path.startsWith("tables.")) return true;
        String prefix = MetadataComparisonValidator.tablePath(table);
        return path.equals(prefix) || path.startsWith(prefix + ".");
    }

    private DependencyOrder dependencyOrder(List<Table> tables) {
        Map<String, Table> byName = new LinkedHashMap<>();
        for (Table table : tables) byName.put(tableKey(table), table);

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();
        byName.keySet().forEach(key -> {
            indegree.put(key, 0);
            dependents.put(key, new LinkedHashSet<>());
        });

        for (Table source : tables) {
            String sourceKey = tableKey(source);
            Set<String> dependencies = new LinkedHashSet<>();
            for (ForeignKey foreignKey : source.foreignKeys()) {
                String targetKey = resolveInternalTableKey(source, foreignKey, byName);
                if (targetKey != null && !targetKey.equals(sourceKey)) dependencies.add(targetKey);
            }
            indegree.put(sourceKey, dependencies.size());
            dependencies.forEach(target -> dependents.get(target).add(sourceKey));
        }

        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((key, value) -> { if (value == 0) queue.addLast(key); });
        List<Table> ordered = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String key = queue.removeFirst();
            if (!emitted.add(key)) continue;
            ordered.add(byName.get(key));
            for (String dependent : dependents.getOrDefault(key, Set.of())) {
                int next = indegree.computeIfPresent(dependent, (ignored, value) -> value - 1);
                if (next == 0) queue.addLast(dependent);
            }
        }

        List<Table> cyclic = new ArrayList<>();
        for (Table table : tables) {
            if (!emitted.contains(tableKey(table))) {
                cyclic.add(table);
                ordered.add(table);
            }
        }
        return new DependencyOrder(List.copyOf(ordered), List.copyOf(cyclic));
    }

    private String resolveInternalTableKey(
            Table source,
            ForeignKey foreignKey,
            Map<String, Table> byName) {
        String targetName = foreignKey.referencedTable().name().normalized();
        String targetSchema = foreignKey.referencedTable().schemaName()
                .map(identifier -> identifier.normalized())
                .orElseGet(() -> source.qualifiedName().schemaName()
                        .map(identifier -> identifier.normalized()).orElse(""));
        String exact = targetSchema + "." + targetName;
        if (byName.containsKey(exact)) return exact;
        return byName.entrySet().stream()
                .filter(entry -> entry.getValue().qualifiedName().name().normalized().equals(targetName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private String eaArtifactBaseName(DatabaseSchema schema, Table table, DatabasePlatform platform) {
        String value = tableSchema(schema, table) + "." + table.qualifiedName().name().value();
        return platform == DatabasePlatform.POSTGRESQL
                ? value.toLowerCase(Locale.ROOT)
                : value;
    }

    private String tableSchema(DatabaseSchema schema, Table table) {
        return table.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema.name().value());
    }

    private String tableKey(Table table) {
        String schema = table.qualifiedName().schemaName()
                .map(identifier -> identifier.normalized()).orElse("");
        return schema + "." + table.qualifiedName().name().normalized();
    }

    private record DependencyOrder(List<Table> tables, List<Table> cyclicTables) { }
}
