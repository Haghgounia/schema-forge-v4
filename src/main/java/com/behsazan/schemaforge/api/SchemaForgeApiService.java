package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.CollisionSafeArtifactTargetAllocator;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.diagram.graphviz.GraphvizDiagramExporter;
import com.behsazan.schemaforge.diagram.graphviz.GraphvizBatchDiagramExporter;
import com.behsazan.schemaforge.diagram.mermaid.MermaidDiagramExporter;
import com.behsazan.schemaforge.diagram.mermaid.MermaidBatchDiagramExporter;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudPackageGenerator;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudProcedureGenerator;
import com.behsazan.schemaforge.migration.MigrationArtifact;
import com.behsazan.schemaforge.migration.MigrationFileWriter;
import com.behsazan.schemaforge.migration.MigrationGenerationService;
import com.behsazan.schemaforge.migration.MigrationRenderOptions;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.reporting.SchemaCompareExcelWriter;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.ea.EnterpriseArchitectXmlParser;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Coordinates schema forge api operations.
 *
 * @since 4.1
 */
@Service
public class SchemaForgeApiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaForgeApiService.class);
    private final SchemaPreparationService preparationService;
    private final MetadataRepositoryResolver metadataRepositoryResolver;
    private final EaImportProperties eaImportProperties;
    private final ObjectMapper objectMapper;
    private final ArtifactNamingPolicy artifactNamingPolicy = new ArtifactNamingPolicy();
    private final LegacyWordSpecificationParser legacyWordSpecificationParser = new LegacyWordSpecificationParser();
    private final SchemaCompareExcelWriter compareExcelWriter = new SchemaCompareExcelWriter();
    private final OracleCrudPackageGenerator oracleCrudGenerator = new OracleCrudPackageGenerator();
    private final SqlServerCrudProcedureGenerator sqlServerCrudGenerator = new SqlServerCrudProcedureGenerator();
    private final OracleCrudGenerationOptions oracleCrudOptions;
    private final SqlServerCrudGenerationOptions sqlServerCrudOptions;
    private final OracleDdlSanityChecker oracleDdlSanityChecker = new OracleDdlSanityChecker();
    private final MermaidDiagramExporter mermaidDiagramExporter = new MermaidDiagramExporter();
    private final MermaidBatchDiagramExporter mermaidBatchDiagramExporter = new MermaidBatchDiagramExporter();
    private final GraphvizDiagramExporter graphvizDiagramExporter = new GraphvizDiagramExporter();
    private final GraphvizBatchDiagramExporter graphvizBatchDiagramExporter = new GraphvizBatchDiagramExporter();
    private final MigrationGenerationService migrationGenerationService = new MigrationGenerationService();
    private final MigrationFileWriter migrationFileWriter = new MigrationFileWriter();

    public SchemaForgeApiService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver) {
        this(auditProperties, grantProperties, spellCheckProperties, objectMapper,
                metadataRepositoryResolver, EaImportProperties.defaults());
    }

    @Autowired
    public SchemaForgeApiService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver,
            EaImportProperties eaImportProperties) {
        this.preparationService = new SchemaPreparationService(
                auditProperties, grantProperties, spellCheckProperties, objectMapper);
        this.metadataRepositoryResolver = metadataRepositoryResolver;
        this.eaImportProperties = eaImportProperties;
        this.objectMapper = objectMapper;
        List<String> crudGrantees = grantProperties.getGrants().stream()
                .filter(SchemaForgeApiService::hasWritePrivilege)
                .map(GrantProperties.GrantRule::getGrantee)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        this.oracleCrudOptions = OracleCrudGenerationOptions.ofGrantees(crudGrantees);
        this.sqlServerCrudOptions = SqlServerCrudGenerationOptions.ofGrantees(crudGrantees);
    }

    public byte[] generateFromWord(MultipartFile file) throws IOException {
        return generateFromWordTracked(file).content();
    }

    GenerationArchive generateFromWordTracked(MultipartFile file) throws IOException {
        requireExtension(file, ".docx");
        String sourceName = safeName(file.getOriginalFilename(), "input.docx");
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD, sourceName);
        Path work = Files.createTempDirectory("schemaforge-word-");
        try {
            Path input = work.resolve(sourceName);
            file.transferTo(input);
            Path output = Files.createDirectories(work.resolve("output"));
            generateWordForAll(input, output, context);
            return new GenerationArchive(zipDirectory(output), context.ledger().snapshot());
        } finally {
            deleteRecursively(work);
        }
    }

    public byte[] generateFromLegacyWord(MultipartFile file, String schemaName) throws IOException {
        return generateFromLegacyWordTracked(file, schemaName).content();
    }

    GenerationArchive generateFromLegacyWordTracked(MultipartFile file, String schemaName) throws IOException {
        requireWordExtension(file);
        String schema = requireText(schemaName, "Legacy Word schema parameter is required");
        String fallback = file.getOriginalFilename() != null
                && file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".docx")
                ? "input.docx"
                : "input.doc";
        String sourceName = safeName(file.getOriginalFilename(), fallback);
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.LEGACY_WORD, sourceName);
        Path work = Files.createTempDirectory("schemaforge-legacy-word-");
        try {
            Path input = work.resolve(sourceName);
            file.transferTo(input);
            Path output = Files.createDirectories(work.resolve("output"));
            generateLegacyWordForAll(input, output, schema, context);
            return new GenerationArchive(zipDirectory(output), context.ledger().snapshot());
        } finally {
            deleteRecursively(work);
        }
    }

    public byte[] generateFromZip(MultipartFile file) throws IOException {
        return generateFromZipTracked(file).content();
    }

    GenerationArchive generateFromZipTracked(MultipartFile file) throws IOException {
        requireExtension(file, ".zip");
        String sourceName = safeName(file.getOriginalFilename(), "input.zip");
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, sourceName);
        Path work = Files.createTempDirectory("schemaforge-zip-");
        try {
            Path inputDir = Files.createDirectories(work.resolve("input"));
            Path outputDir = Files.createDirectories(work.resolve("output"));
            unzipSafely(file, inputDir);

            List<Path> documents;
            try (var files = Files.walk(inputDir)) {
                documents = files.filter(Files::isRegularFile)
                        .filter(SchemaForgeApiService::isProcessableWordDocument)
                        .sorted(Comparator.comparing(path ->
                                normalizePath(inputDir.relativize(path)).toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (documents.isEmpty()) {
                throw new IllegalArgumentException("ZIP does not contain any processable DOCX files");
            }

            List<String> summary = new ArrayList<>();
            summary.add("sequence,document,status,generated_files,error");
            StringBuilder errors = new StringBuilder();
            int sequence = 0;
            List<Table> batchDiagramTables = new ArrayList<>();
            CollisionSafeArtifactTargetAllocator batchTargetAllocator =
                    new CollisionSafeArtifactTargetAllocator();

            for (Path document : documents) {
                sequence++;
                String relativeDocument = normalizePath(inputDir.relativize(document));
                Path documentOutput = Files.createDirectories(
                        work.resolve("staging").resolve(String.format(Locale.ROOT, "%05d", sequence)));
                try {
                    ArtifactGenerationContext documentContext = context.isolatedChild(
                            ArtifactOrigin.ZIP_BATCH, relativeDocument);
                    PreparedSchema prepared = generateWordForAll(document, documentOutput, documentContext);
                    batchDiagramTables.addAll(prepared.schema().tables());
                    long generatedFiles = countRegularFiles(documentOutput);
                    Map<String, String> remappedPaths = moveGeneratedFiles(
                            documentOutput, outputDir, batchTargetAllocator, relativeDocument);
                    mergeBatchArtifacts(documentContext, context, remappedPaths);
                    summary.add(csvLine(
                            Integer.toString(sequence),
                            relativeDocument,
                            "SUCCESS",
                            Long.toString(generatedFiles),
                            ""));
                } catch (Exception exception) {
                    String message = safeMessage(exception);
                    LOGGER.warn("ZIP document skipped after generation failure: {} - {}",
                            relativeDocument, message);
                    summary.add(csvLine(
                            Integer.toString(sequence),
                            relativeDocument,
                            "FAILED",
                            "0",
                            exception.getClass().getSimpleName() + ": " + message));
                    appendBatchError(errors, sequence, relativeDocument, exception);
                } finally {
                    deleteRecursively(documentOutput);
                }
            }

            if (!batchDiagramTables.isEmpty()) {
                writeBatchMermaidArtifacts(batchDiagramTables, outputDir, context);
                writeBatchGraphvizArtifacts(batchDiagramTables, outputDir, context);
            }

            Path summaryPath = outputDir.resolve(artifactNamingPolicy.batchGenerationSummaryRelativePath());
            Files.createDirectories(summaryPath.getParent());
            Files.writeString(
                    summaryPath,
                    String.join("\n", summary) + "\n",
                    StandardCharsets.UTF_8);
            context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                    "batch-generation", ArtifactPaths.relative(outputDir, summaryPath),
                    "text/csv", "SchemaForgeApiService");
            Path errorPath = outputDir.resolve(artifactNamingPolicy.batchGenerationErrorRelativePath());
            Files.writeString(
                    errorPath,
                    errors.toString(),
                    StandardCharsets.UTF_8);
            context.ledger().generated(context, ArtifactType.ERROR_REPORT, null,
                    "batch-generation", ArtifactPaths.relative(outputDir, errorPath),
                    "text/plain", "SchemaForgeApiService");

            return new GenerationArchive(zipDirectory(outputDir), context.ledger().snapshot());
        } finally {
            deleteRecursively(work);
        }
    }

    public byte[] generateFromEaXml(MultipartFile file) throws IOException {
        return generateFromEaXml(file, null);
    }

    public byte[] generateFromEaXml(MultipartFile file, String schemaName) throws IOException {
        return generateFromEaXmlTracked(file, schemaName).content();
    }

    GenerationArchive generateFromEaXmlTracked(MultipartFile file, String schemaName) throws IOException {
        String name = safeName(file.getOriginalFilename(), "ea-model.xml");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xml") && !lower.endsWith(".xmi")) {
            throw new IllegalArgumentException("EA file must be XML or XMI");
        }
        Path work = Files.createTempDirectory("schemaforge-ea-");
        try {
            DatabaseSchema parsed;
            try (InputStream inputStream = file.getInputStream()) {
                parsed = new EnterpriseArchitectXmlParser(
                        eaImportProperties.getDefaultSchema(), true)
                        .parse(name, inputStream, schemaName);
            }
            PreparedSchema prepared = preparationService.prepare(parsed);
            Path output = Files.createDirectories(work.resolve("output"));
            ArtifactGenerationContext context = ArtifactGenerationContext.create(
                    ArtifactOrigin.ENTERPRISE_ARCHITECT, name);
            writeEaPerTableOutputs(prepared, output, stripExtension(name), context);
            return new GenerationArchive(zipDirectory(output), context.ledger().snapshot());
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Parses and enriches the Word model only once. All registered database dialects are generated
     * from the exact same enriched model, so configured audit columns cannot diverge.
     */
    private PreparedSchema generateWordForAll(
            Path input, Path output, ArtifactGenerationContext context) throws IOException {
        DatabaseSchema parsed;
        try (InputStream stream = Files.newInputStream(input)) {
            parsed = new WordSpecificationParser().parse(
                    new SpecificationSource(input.getFileName().toString(), stream));
        }
        PreparedSchema prepared = preparationService.prepare(parsed);
        writeAllDatabaseOutputs(
                prepared, output, stripExtension(input.getFileName().toString()), context);
        return prepared;
    }

    private void generateLegacyWordForAll(
            Path input, Path output, String schemaName, ArtifactGenerationContext context) throws IOException {
        DatabaseSchema parsed = legacyWordSpecificationParser.parse(
                input.getParent(), input, schemaName);
        PreparedSchema prepared = preparationService.prepare(parsed);
        writeAllDatabaseOutputs(
                prepared, output, stripExtension(input.getFileName().toString()), context);
    }

    private void writeAllDatabaseOutputs(
            PreparedSchema prepared, Path output, String baseName, ArtifactGenerationContext context)
            throws IOException {
        DatabaseSchema schema = prepared.schema();
        ValidationReport report = prepared.validationReport();
        List<ValidationIssue> jsonIssues = new ArrayList<>(report.issues());

        // All normal artifacts in one top-level request share the same timestamp.
        String timestamp = context.generationTimestamp();

        // Metadata is queried once per database output. The same comparison result is
        // reused by SQL generation and the consolidated JSON validation report.
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            var dialect = DialectFactory.create(platform);
            MetadataRepository repository = metadataRepositoryResolver.resolve(platform);
            MetadataComparisonResult metadata = new MetadataComparisonValidator(
                    dialect, repository).validate(schema);
            metadata.issues().stream()
                    .map(issue -> new ValidationIssue(
                            issue.severity(),
                            issue.code(),
                            "dialects." + platform.commandLineName() + "." + issue.path(),
                            "[" + platform.name() + "] " + issue.message()))
                    .forEach(jsonIssues::add);

            String sql = new DdlGenerator(dialect).generate(schema, report, metadata);
            Path ddlRelativePath = artifactNamingPolicy.ddlRelativePath(baseName, platform, timestamp);
            String sqlFileName = ddlRelativePath.getFileName().toString();
            requireValidOracleDdl(platform, sql, sqlFileName);
            Path ddlPath = output.resolve(ddlRelativePath);
            Files.createDirectories(ddlPath.getParent());
            Files.writeString(ddlPath, sql, StandardCharsets.UTF_8);
            context.ledger().generated(context, ArtifactType.DDL, platform,
                    baseName, ArtifactPaths.relative(output, ddlPath),
                    "application/sql", "DdlGenerator");

            // CREATE DDL is always emitted first, even when the live table already exists.
            // ALTER/Flyway output is an additional artifact and never replaces the CREATE script.
            writeMigrationArtifacts(schema, repository, output, platform, context);
            writeComparisonWorkbooks(
                    schema, repository, metadata, output, timestamp, platform, dialect, context);
        }

        writeMetadataCrudArtifacts(schema, output, baseName, timestamp, context);
        writeMermaidArtifact(schema, output, baseName, timestamp, context);
        writeGraphvizArtifact(schema, output, baseName, timestamp, context);
        writeConceptualErdArtifacts(schema, output, baseName, timestamp, context);

        ValidationReport jsonReport = new ValidationReport(
                jsonIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                jsonIssues);
        Path jsonPath = output.resolve(artifactNamingPolicy.canonicalJsonRelativePath(baseName, timestamp));
        Files.createDirectories(jsonPath.getParent());
        new JsonExporter().write(jsonPath, schema, jsonReport);
        context.ledger().generated(context, ArtifactType.CANONICAL_JSON, null,
                baseName, ArtifactPaths.relative(output, jsonPath),
                "application/json", "JsonExporter");
    }


    /**
     * Writes one Mermaid ER artifact beside the normal per-document SQL/JSON/Excel outputs.
     * The diagram is rendered from the same prepared canonical schema used by all SQL dialects,
     * so no source document is reparsed and no historical version selection is performed.
     */
    private void writeMermaidArtifact(
            DatabaseSchema schema, Path output, String baseName, String timestamp,
            ArtifactGenerationContext context) throws IOException {
        String mermaid = mermaidDiagramExporter.export(schema.tables(), DiagramExportOptions.erAll());
        Path path = output.resolve(artifactNamingPolicy.mermaidErRelativePath(baseName, timestamp));
        Files.createDirectories(path.getParent());
        Files.writeString(path, mermaid, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                baseName + ":er", ArtifactPaths.relative(output, path),
                "text/plain", "MermaidDiagramExporter");
    }


    /**
     * Writes one Graphviz ER artifact beside the normal per-document SQL/JSON/Excel outputs.
     * Only textual DOT is generated; SchemaForge does not execute a Graphviz binary.
     */
    private void writeGraphvizArtifact(
            DatabaseSchema schema, Path output, String baseName, String timestamp,
            ArtifactGenerationContext context) throws IOException {
        String dot = graphvizDiagramExporter.export(schema.tables(), DiagramExportOptions.erAll());
        Path path = output.resolve(artifactNamingPolicy.graphvizErRelativePath(baseName, timestamp));
        Files.createDirectories(path.getParent());
        Files.writeString(path, dot, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.GRAPHVIZ_DIAGRAM, null,
                baseName + ":er", ArtifactPaths.relative(output, path),
                "text/vnd.graphviz", "GraphvizDiagramExporter");
    }


    /**
     * Writes field-free conceptual ERD artifacts from the same canonical schema. Cardinality and
     * optionality are derived only from FK nullability and exact PK/UK evidence; column names are
     * never used to infer a relationship.
     */
    private void writeConceptualErdArtifacts(
            DatabaseSchema schema, Path output, String baseName, String timestamp,
            ArtifactGenerationContext context) throws IOException {
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.CONCEPTUAL_ERD)
                .build();
        String mermaid = mermaidDiagramExporter.export(schema.tables(), options);
        String dot = graphvizDiagramExporter.export(schema.tables(), options);
        Path mermaidPath = output.resolve(artifactNamingPolicy.mermaidConceptualRelativePath(baseName, timestamp));
        Path graphvizPath = output.resolve(artifactNamingPolicy.graphvizConceptualRelativePath(baseName, timestamp));
        Files.createDirectories(mermaidPath.getParent());
        Files.createDirectories(graphvizPath.getParent());
        Files.writeString(mermaidPath, mermaid, StandardCharsets.UTF_8);
        Files.writeString(graphvizPath, dot, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                baseName + ":conceptual-erd", ArtifactPaths.relative(output, mermaidPath),
                "text/plain", "MermaidDiagramExporter");
        context.ledger().generated(context, ArtifactType.GRAPHVIZ_DIAGRAM, null,
                baseName + ":conceptual-erd", ArtifactPaths.relative(output, graphvizPath),
                "text/vnd.graphviz", "GraphvizDiagramExporter");
    }


    /**
     * Writes batch-level Mermaid ER, conceptual ERD, and dependency diagrams for ZIP generation. Duplicate qualified
     * table names are never auto-selected: every duplicated name is excluded from the batch graph
     * and recorded in the issues report. Per-document Mermaid files are unaffected.
     */
    private void writeBatchMermaidArtifacts(
            List<Table> tableDefinitions, Path output, ArtifactGenerationContext context) throws IOException {
        MermaidBatchDiagramExporter.Result result = mermaidBatchDiagramExporter.export(tableDefinitions);
        Path batchDirectory = Files.createDirectories(
                output.resolve(artifactNamingPolicy.batchMermaidDirectory()));

        Path erPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.ER));
        Path conceptualPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.CONCEPTUAL_ERD));
        Path dependencyPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.DEPENDENCY));
        Files.writeString(erPath, result.er(), StandardCharsets.UTF_8);
        Files.writeString(conceptualPath, result.conceptualErd(), StandardCharsets.UTF_8);
        Files.writeString(dependencyPath, result.dependency(), StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                "batch:schema-er", ArtifactPaths.relative(output, erPath),
                "text/plain", "MermaidBatchDiagramExporter");
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                "batch:conceptual-erd", ArtifactPaths.relative(output, conceptualPath),
                "text/plain", "MermaidBatchDiagramExporter");
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                "batch:dependency", ArtifactPaths.relative(output, dependencyPath),
                "text/plain", "MermaidBatchDiagramExporter");

        List<String> issues = new ArrayList<>();
        issues.add("code,source_table,target_table,occurrences,detail");
        for (MermaidBatchDiagramExporter.Issue issue : result.issues()) {
            issues.add(csvLine(
                    issue.code(),
                    issue.sourceTable(),
                    issue.targetTable(),
                    Integer.toString(issue.occurrences()),
                    issue.detail()));
        }
        Path issuesPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.ISSUES));
        Files.writeString(issuesPath, String.join("\n", issues) + "\n", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.ISSUE_REPORT, null,
                "batch:mermaid", ArtifactPaths.relative(output, issuesPath),
                "text/csv", "MermaidBatchDiagramExporter");

        String summary = "SchemaForge batch Mermaid summary\n"
                + "=================================\n"
                + "Table definitions       : " + result.tableDefinitions() + "\n"
                + "Distinct table names    : " + result.distinctTableNames() + "\n"
                + "Duplicate table names   : " + result.duplicateTableNames() + "\n"
                + "Exported unique tables  : " + result.exportedTables() + "\n"
                + "Physical FKs (exported) : " + result.physicalForeignKeys() + "\n"
                + "Resolved physical FKs   : " + result.resolvedPhysicalForeignKeys() + "\n"
                + "Issues                   : " + result.issues().size() + "\n"
                + "Duplicate policy         : EXCLUDE_ALL_DUPLICATE_DEFINITIONS_NO_AUTO_SELECTION\n";
        Path summaryPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.SUMMARY));
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                "batch:mermaid", ArtifactPaths.relative(output, summaryPath),
                "text/plain", "MermaidBatchDiagramExporter");
    }


    /**
     * Writes batch Graphviz conceptual ERD and dependency diagrams. The duplicate policy is intentionally identical
     * to the Mermaid batch exporter: duplicated qualified table names are excluded, never selected.
     */
    private void writeBatchGraphvizArtifacts(
            List<Table> tableDefinitions, Path output, ArtifactGenerationContext context) throws IOException {
        GraphvizBatchDiagramExporter.Result result = graphvizBatchDiagramExporter.export(tableDefinitions);
        Path batchDirectory = Files.createDirectories(
                output.resolve(artifactNamingPolicy.batchGraphvizDirectory()));

        Path conceptualPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.CONCEPTUAL_ERD));
        Path dependencyPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.DEPENDENCY));
        Path clusteredPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.CLUSTERED));
        Path compactPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.COMPACT));
        Path overviewPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.OVERVIEW));
        Files.writeString(conceptualPath, result.conceptualErd(), StandardCharsets.UTF_8);
        Files.writeString(dependencyPath, result.dependency(), StandardCharsets.UTF_8);
        Files.writeString(clusteredPath, result.clusteredDependency(), StandardCharsets.UTF_8);
        Files.writeString(compactPath, result.compactDependency(), StandardCharsets.UTF_8);
        Files.writeString(overviewPath, result.overviewDependency(), StandardCharsets.UTF_8);

        for (Path diagram : List.of(conceptualPath, dependencyPath, clusteredPath, compactPath, overviewPath)) {
            context.ledger().generated(context, ArtifactType.GRAPHVIZ_DIAGRAM, null,
                    "batch:" + diagram.getFileName().toString().replace("schema-", "").replace(".dot", ""),
                    ArtifactPaths.relative(output, diagram), "text/vnd.graphviz",
                    "GraphvizBatchDiagramExporter");
        }

        List<String> issues = new ArrayList<>();
        issues.add("code,source_table,target_table,occurrences,detail");
        for (GraphvizBatchDiagramExporter.Issue issue : result.issues()) {
            issues.add(csvLine(
                    issue.code(),
                    issue.sourceTable(),
                    issue.targetTable(),
                    Integer.toString(issue.occurrences()),
                    issue.detail()));
        }
        Path issuesPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.ISSUES));
        Files.writeString(issuesPath, String.join("\n", issues) + "\n", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.ISSUE_REPORT, null,
                "batch:graphviz", ArtifactPaths.relative(output, issuesPath),
                "text/csv", "GraphvizBatchDiagramExporter");

        String summary = "SchemaForge batch Graphviz summary\n"
                + "=================================\n"
                + "Table definitions       : " + result.tableDefinitions() + "\n"
                + "Distinct table names    : " + result.distinctTableNames() + "\n"
                + "Duplicate table names   : " + result.duplicateTableNames() + "\n"
                + "Exported unique tables  : " + result.exportedTables() + "\n"
                + "Connected tables        : " + result.connectedTables() + "\n"
                + "Physical FKs (exported) : " + result.physicalForeignKeys() + "\n"
                + "Resolved physical FKs   : " + result.resolvedPhysicalForeignKeys() + "\n"
                + "Issues                   : " + result.issues().size() + "\n"
                + "Duplicate policy         : EXCLUDE_ALL_DUPLICATE_DEFINITIONS_NO_AUTO_SELECTION\n"
                + "Full profile             : disconnected=true, labels=true, clusterBySchema=true\n"
                + "Compact profile          : disconnected=false, labels=true, clusterBySchema=true\n"
                + "Overview profile         : disconnected=false, labels=false, clusterBySchema=true\n"
                + "Renderer                 : DOT_ONLY_NO_GRAPHVIZ_EXECUTION\n";
        Path summaryPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.SUMMARY));
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                "batch:graphviz", ArtifactPaths.relative(output, summaryPath),
                "text/plain", "GraphvizBatchDiagramExporter");
    }


    /**
     * Adds Oracle packages and SQL Server CRUD procedures to Word/ZIP REST output.
     * These artifacts remain metadata-based: they are generated only when the
     * corresponding repository is enabled and the live table can be resolved.
     * A per-document summary makes every skip or failure visible to the caller.
     */
    private void writeMetadataCrudArtifacts(
            DatabaseSchema documentSchema, Path output, String baseName, String timestamp,
            ArtifactGenerationContext context) throws IOException {

        List<String> summary = new ArrayList<>();
        summary.add("platform,schema,table,status,file,error");

        writeMetadataCrudArtifactsForPlatform(
                documentSchema, output, timestamp, DatabasePlatform.ORACLE, summary, context);
        writeMetadataCrudArtifactsForPlatform(
                documentSchema, output, timestamp, DatabasePlatform.SQLSERVER, summary, context);

        Path summaryPath = output.resolve(artifactNamingPolicy.metadataCrudSummaryRelativePath(baseName, timestamp));
        Files.createDirectories(summaryPath.getParent());
        Files.writeString(summaryPath, String.join("\n", summary) + "\n", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                baseName + ":metadata-crud", ArtifactPaths.relative(output, summaryPath),
                "text/csv", "SchemaForgeApiService");
    }

    private void writeMetadataCrudArtifactsForPlatform(
            DatabaseSchema documentSchema,
            Path output,
            String timestamp,
            DatabasePlatform platform,
            List<String> summary,
            ArtifactGenerationContext context) {

        MetadataRepository repository = metadataRepositoryResolver.resolve(platform);
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
                        schemaName + "." + tableName, "SchemaForgeApiService");
                continue;
            }

            if (!repository.available()) {
                summary.add(csvLine(platform.name(), schemaName, tableName,
                        "SKIPPED_REPOSITORY_DISABLED", "",
                        "Metadata repository is not enabled"));
                context.ledger().skipped(context, ArtifactType.CRUD, platform,
                        schemaName + "." + tableName, "SchemaForgeApiService");
                continue;
            }

            try {
                var liveTable = findMetadataTable(repository, schemaName, tableName);
                if (liveTable.isEmpty()) {
                    summary.add(csvLine(platform.name(), schemaName, tableName,
                            "SKIPPED_TABLE_NOT_FOUND", "",
                            "Live table was not found"));
                    LOGGER.warn("[{}] REST CRUD artifact skipped; live table not found: {}.{}",
                            platform.name(), schemaName, tableName);
                    context.ledger().skipped(context, ArtifactType.CRUD, platform,
                            schemaName + "." + tableName, "SchemaForgeApiService");
                    continue;
                }
                if (liveTable.get().primaryKey().isEmpty()) {
                    summary.add(csvLine(platform.name(), schemaName, tableName,
                            "SKIPPED_NO_PRIMARY_KEY", "",
                            "Live table has no primary key"));
                    LOGGER.info("[{}] REST CRUD artifact skipped; live table has no primary key: {}.{}",
                            platform.name(), schemaName, tableName);
                    context.ledger().skipped(context, ArtifactType.CRUD, platform,
                            schemaName + "." + tableName, "SchemaForgeApiService");
                    continue;
                }

                String logicalName = schemaName.toUpperCase(Locale.ROOT) + "."
                        + tableName.toUpperCase(Locale.ROOT);
                String fileName = artifactNamingPolicy.crudFileName(logicalName, platform, timestamp);
                String sql = platform == DatabasePlatform.ORACLE
                        ? oracleCrudGenerator.generate(liveTable.get(), oracleCrudOptions)
                        : sqlServerCrudGenerator.generate(liveTable.get(), sqlServerCrudOptions);

                Path crudRelativePath = artifactNamingPolicy.crudRelativePath(logicalName, platform, timestamp);
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
                        schemaName + "." + tableName, "SchemaForgeApiService");
            }
        }
    }

    private static java.util.Optional<Table> findMetadataTable(
            MetadataRepository repository, String schemaName, String tableName) {
        var table = repository.findTable(schemaName, tableName);
        if (table.isPresent()) {
            return table;
        }
        String matchedSchema = repository.findTableSchemas(tableName).stream()
                .filter(candidate -> candidate.equalsIgnoreCase(schemaName))
                .findFirst()
                .orElse(null);
        return matchedSchema == null
                ? java.util.Optional.empty()
                : repository.findTable(matchedSchema, tableName);
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

    /**
     * EA exports can contain many tables. Each table is therefore emitted as an
     * independent per-dialect script and, when metadata is available, an
     * independent comparison workbook. The canonical model and a manifest remain
     * consolidated at archive root.
     */
    private void writeEaPerTableOutputs(
            PreparedSchema prepared, Path output, String baseName, ArtifactGenerationContext context)
            throws IOException {
        DatabaseSchema schema = prepared.schema();
        ValidationReport report = prepared.validationReport();
        List<ValidationIssue> jsonIssues = new ArrayList<>(report.issues());
        String timestamp = context.generationTimestamp();

        Map<String, Map<String, Object>> manifestTables = new LinkedHashMap<>();
        for (Table table : schema.tables()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("schema", tableSchema(schema, table));
            item.put("table", table.qualifiedName().name().value());
            manifestTables.put(tableKey(table), item);
        }

        DependencyOrder dependencyOrder = dependencyOrder(schema.tables());

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Dialect dialect = DialectFactory.create(platform);
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

                Map<String, Object> item = manifestTables.get(tableKey(table));
                item.put(platform.commandLineName() + "Sql",
                        ArtifactPaths.relative(output, ddlPath));

                String workbookPath = writeEaComparisonWorkbook(
                        schema, table, repository, metadata, output, platform, dialect,
                        context, timestamp);
                if (workbookPath != null) {
                    item.put(platform.commandLineName() + "Excel", workbookPath);
                }
            }

            // Per-table CREATE scripts above remain unconditional. If matching live tables exist,
            // emit additional Flyway migrations under migration/<platform>/.
            writeMigrationArtifacts(schema, repository, output, platform, context);
            writeEaRunAll(
                    schema, platform, dependencyOrder, baseName, timestamp, output, context);
        }

        writeMetadataCrudArtifacts(schema, output, baseName, timestamp, context);
        writeMermaidArtifact(schema, output, baseName, timestamp, context);
        writeGraphvizArtifact(schema, output, baseName, timestamp, context);
        writeConceptualErdArtifacts(schema, output, baseName, timestamp, context);

        ValidationReport jsonReport = new ValidationReport(
                jsonIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                jsonIssues);
        Path modelPath = output.resolve(artifactNamingPolicy.canonicalJsonRelativePath(baseName, timestamp));
        Files.createDirectories(modelPath.getParent());
        new JsonExporter().write(modelPath, schema, jsonReport);
        context.ledger().generated(context, ArtifactType.CANONICAL_JSON, null,
                baseName, ArtifactPaths.relative(output, modelPath),
                "application/json", "JsonExporter");

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("sourceFile", sourceFileName(schema, baseName));
        manifest.put("generatedAt", timestamp);
        manifest.put("schema", schema.name().value());
        manifest.put("tableCount", schema.tables().size());
        manifest.put("dependencyOrder", dependencyOrder.tables().stream()
                .map(table -> table.qualifiedName().toString()).toList());
        manifest.put("cyclicTables", dependencyOrder.cyclicTables().stream()
                .map(table -> table.qualifiedName().toString()).toList());
        manifest.put("mermaid", normalizePath(artifactNamingPolicy.mermaidErRelativePath(baseName, timestamp)));
        manifest.put("graphviz", normalizePath(artifactNamingPolicy.graphvizErRelativePath(baseName, timestamp)));
        manifest.put("conceptualErdMermaid", normalizePath(artifactNamingPolicy.mermaidConceptualRelativePath(baseName, timestamp)));
        manifest.put("conceptualErdGraphviz", normalizePath(artifactNamingPolicy.graphvizConceptualRelativePath(baseName, timestamp)));
        manifest.put("tables", new ArrayList<>(manifestTables.values()));
        Path manifestPath = output.resolve(artifactNamingPolicy.manifestRelativePath());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        context.ledger().generated(context, ArtifactType.MANIFEST, null,
                baseName, ArtifactPaths.relative(output, manifestPath),
                "application/json", "SchemaForgeApiService");
    }

    private String writeEaComparisonWorkbook(
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
                    "SchemaCompareExcelWriter");
            return null;
        }

        String schemaName = tableSchema(schema, documentTable);
        String tableName = documentTable.qualifiedName().name().value();
        var databaseTable = repository.findTable(schemaName, tableName);
        if (databaseTable.isEmpty()) {
            List<String> candidateSchemas = repository.findTableSchemas(tableName);
            String matchedSchema = candidateSchemas.stream()
                    .filter(candidate -> candidate.equalsIgnoreCase(schemaName))
                    .findFirst()
                    .orElse(null);
            if (matchedSchema != null) {
                databaseTable = repository.findTable(matchedSchema, tableName);
            }
        }
        if (databaseTable.isEmpty()) {
            LOGGER.warn("[{}] EA comparison workbook skipped; table not found. requestedSchema={}, requestedTable={}",
                    platform.name(), schemaName, tableName);
            context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                    schemaName + "." + tableName, "SchemaCompareExcelWriter");
            return null;
        }

        Map<String, Long> usageCounts = new LinkedHashMap<>();
        documentTable.columns().forEach(column -> usageCounts.put(
                column.name().normalized(),
                metadata.frequency(MetadataComparisonValidator.path(documentTable, column))));

        byte[] workbook = compareExcelWriter.write(
                documentTable, databaseTable.get(), usageCounts, platform.name(), dialect);
        String logicalName = eaArtifactBaseName(schema, documentTable, platform);
        Path relativePath = artifactNamingPolicy.comparisonRelativePath(logicalName, platform, timestamp);
        Path workbookPath = artifactRoot.resolve(relativePath);
        Files.createDirectories(workbookPath.getParent());
        Files.write(workbookPath, workbook);
        context.ledger().generated(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                schemaName + "." + tableName, ArtifactPaths.relative(artifactRoot, workbookPath),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "SchemaCompareExcelWriter");
        String normalized = ArtifactPaths.relative(artifactRoot, workbookPath);
        LOGGER.info("[{}] EA comparison workbook generated: {}", platform.name(), normalized);
        return normalized;
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
            String reference = normalizePath(runAllPath.getParent().relativize(artifactRoot.resolve(ddlRelativePath)));
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
                "application/sql", "SchemaForgeApiService");
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

    private String sourceFileName(DatabaseSchema schema, String fallback) {
        return schema.metadata().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("source.fileName"))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private record DependencyOrder(List<Table> tables, List<Table> cyclicTables) { }

    /**
     * Writes additional Flyway-compatible ALTER scripts for desired tables that already exist.
     *
     * <p>This is deliberately additive: normal CREATE DDL is generated independently before this
     * method is called. A missing live table, an unavailable metadata repository, or an empty diff
     * never suppresses the normal CREATE artifact.</p>
     */
    private void writeMigrationArtifacts(
            DatabaseSchema schema,
            MetadataRepository repository,
            Path output,
            DatabasePlatform platform,
            ArtifactGenerationContext context) throws IOException {

        if (!repository.available()) {
            for (Table table : schema.tables()) {
                context.ledger().skipped(context, ArtifactType.MIGRATION, platform,
                        tableSchema(schema, table) + "." + table.qualifiedName().name().value(),
                        "MigrationGenerationService");
            }
            return;
        }

        Path migrationDirectory = output.resolve(artifactNamingPolicy.migrationDirectory(platform));

        for (Table desiredTable : schema.tables()) {
            String schemaName = desiredTable.qualifiedName().schemaName()
                    .map(identifier -> identifier.value())
                    .orElse(schema.name().value());
            String tableName = desiredTable.qualifiedName().name().value();

            var liveTable = repository.findTable(schemaName, tableName);
            if (liveTable.isEmpty()) {
                LOGGER.debug("[{}] Migration skipped; live table not found: {}.{}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.MIGRATION, platform,
                        schemaName + "." + tableName, "MigrationGenerationService");
                continue;
            }

            MigrationArtifact artifact = migrationGenerationService.generate(
                    platform,
                    liveTable.get(),
                    desiredTable,
                    MigrationRenderOptions.safeDefaults());
            if (artifact.plan().empty()) {
                LOGGER.info("[{}] Migration not required; live table already matches desired columns: {}.{}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.MIGRATION, platform,
                        schemaName + "." + tableName, "MigrationGenerationService");
                continue;
            }

            Path written = migrationFileWriter.write(migrationDirectory, artifact);
            context.ledger().generated(context, ArtifactType.MIGRATION, platform,
                    schemaName + "." + tableName, ArtifactPaths.relative(output, written),
                    "application/sql", "MigrationGenerationService");
            LOGGER.info("[{}] Flyway migration generated: {}",
                    platform.name(), output.relativize(written));
        }
    }

    private void writeComparisonWorkbooks(
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
                        "SchemaCompareExcelWriter");
            }
            return;
        }

        for (Table documentTable : schema.tables()) {
            String schemaName = documentTable.qualifiedName().schemaName()
                    .map(identifier -> identifier.value())
                    .orElse(schema.name().value());
            String tableName = documentTable.qualifiedName().name().value();
            var databaseTable = repository.findTable(schemaName, tableName);
            if (databaseTable.isEmpty()) {
                List<String> candidateSchemas = repository.findTableSchemas(tableName);
                String matchedSchema = candidateSchemas.stream()
                        .filter(candidate -> candidate.equalsIgnoreCase(schemaName))
                        .findFirst()
                        .orElse(null);
                if (matchedSchema != null) {
                    databaseTable = repository.findTable(matchedSchema, tableName);
                }
            }
            if (databaseTable.isEmpty()) {
                LOGGER.warn("[{}] Comparison workbook skipped; table not found. requestedSchema={}, requestedTable={}",
                        platform.name(), schemaName, tableName);
                context.ledger().skipped(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                        schemaName + "." + tableName, "SchemaCompareExcelWriter");
                continue;
            }
            LOGGER.info("[{}] Comparison table resolved. requested={}.{}, actual={}",
                    platform.name(), schemaName, tableName,
                    databaseTable.get().qualifiedName().toString());

            Map<String, Long> usageCounts = new LinkedHashMap<>();
            documentTable.columns().forEach(column -> usageCounts.put(
                    column.name().normalized(),
                    metadata.frequency(MetadataComparisonValidator.path(documentTable, column))));

            byte[] workbook = compareExcelWriter.write(
                    documentTable, databaseTable.get(), usageCounts, platform.name(), dialect);
            String logicalName = schemaName + "." + tableName;
            Path workbookPath = output.resolve(
                    artifactNamingPolicy.comparisonRelativePath(logicalName, platform, timestamp));
            Files.createDirectories(workbookPath.getParent());
            Files.write(workbookPath, workbook);
            context.ledger().generated(context, ArtifactType.COMPARISON_WORKBOOK, platform,
                    schemaName + "." + tableName, ArtifactPaths.relative(output, workbookPath),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "SchemaCompareExcelWriter");
            LOGGER.info("[{}] Comparison workbook generated: {}", platform.name(), workbookPath.getFileName());
        }
    }

    private static boolean isProcessableWordDocument(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".docx")) return false;
        if (name.startsWith("~$") || name.startsWith("._") || name.startsWith(".")) return false;
        for (Path segment : path) {
            if ("__MACOSX".equalsIgnoreCase(segment.toString())) return false;
        }
        return true;
    }

    private static long countRegularFiles(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static Map<String, String> moveGeneratedFiles(
            Path source,
            Path destination,
            CollisionSafeArtifactTargetAllocator allocator,
            String sourceIdentity) throws IOException {
        Map<String, String> remapped = new LinkedHashMap<>();
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path requestedRelative = source.relativize(file);
                Path resolvedRelative = allocator.reserve(
                        requestedRelative, sourceIdentity + "::" + normalizePath(requestedRelative));
                Path target = destination.resolve(resolvedRelative);
                Files.createDirectories(target.getParent());
                Files.move(file, target);
                remapped.put(normalizePath(requestedRelative), normalizePath(resolvedRelative));
            }
        }
        return remapped;
    }

    private static void mergeBatchArtifacts(
            ArtifactGenerationContext documentContext,
            ArtifactGenerationContext batchContext,
            Map<String, String> remappedPaths) {
        for (ArtifactDescriptor descriptor : documentContext.ledger().snapshot()) {
            if (descriptor.status() != com.behsazan.schemaforge.artifact.ArtifactStatus.GENERATED) {
                batchContext.ledger().add(descriptor);
                continue;
            }
            String remapped = remappedPaths.get(descriptor.relativePath());
            if (remapped == null) {
                throw new IllegalStateException(
                        "Generated artifact was not moved into the batch package: " + descriptor.relativePath());
            }
            batchContext.ledger().add(new ArtifactDescriptor(
                    descriptor.type(),
                    descriptor.platform(),
                    descriptor.logicalName(),
                    remapped,
                    descriptor.mediaType(),
                    descriptor.generationId(),
                    descriptor.status(),
                    descriptor.provenance()));
        }
    }

    private static String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
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

    private static void appendBatchError(
            StringBuilder errors, int sequence, String document, Exception exception) {
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        errors.append("============================================================\n")
                .append("Sequence : ").append(sequence).append('\n')
                .append("Document : ").append(document).append('\n')
                .append("Error    : ").append(exception.getClass().getName())
                .append(": ").append(safeMessage(exception)).append('\n')
                .append("------------------------------------------------------------\n")
                .append(stackTrace)
                .append('\n');
    }

    private static void unzipSafely(MultipartFile file, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) throw new IllegalArgumentException("Unsafe ZIP entry: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        }
    }

    private static byte[] zipDirectory(Path directory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(directory.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void requireWordExtension(MultipartFile file) {
        String name = safeName(file.getOriginalFilename(), "upload");
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".doc") && !lower.endsWith(".docx")) {
            throw new IllegalArgumentException("Expected .doc or .docx file");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static void requireExtension(MultipartFile file, String extension) {
        String name = safeName(file.getOriginalFilename(), "upload");
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        if (!name.toLowerCase(Locale.ROOT).endsWith(extension)) throw new IllegalArgumentException("Expected " + extension + " file");
    }

    private static String safeName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        return Path.of(name).getFileName().toString();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
    record GenerationArchive(byte[] content, List<ArtifactDescriptor> artifacts) {
        GenerationArchive {
            content = content.clone();
            artifacts = List.copyOf(artifacts);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

}
