package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates the shared Standard Word and Legacy Word generation pipeline.
 *
 * <p>The parser/recovery implementations, preparation pipeline, DBMS generators,
 * metadata comparison behavior, producer implementations, artifact naming and
 * Ledger semantics are unchanged. This class only moves the existing document
 * orchestration boundary out of the REST-facing facade.</p>
 */
public final class DocumentGenerationOrchestrator {
    private final SchemaPreparationService preparationService;
    private final MetadataRepositoryResolver metadataRepositoryResolver;
    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final DiagramArtifactProducer diagramArtifactProducer;
    private final MigrationArtifactProducer migrationArtifactProducer;
    private final ComparisonArtifactProducer comparisonArtifactProducer;
    private final CrudArtifactProducer crudArtifactProducer;
    private final LegacyWordSpecificationParser legacyWordSpecificationParser;
    private final OracleDdlSanityChecker oracleDdlSanityChecker;

    public DocumentGenerationOrchestrator(
            SchemaPreparationService preparationService,
            MetadataRepositoryResolver metadataRepositoryResolver,
            ArtifactNamingPolicy artifactNamingPolicy,
            DiagramArtifactProducer diagramArtifactProducer,
            MigrationArtifactProducer migrationArtifactProducer,
            ComparisonArtifactProducer comparisonArtifactProducer,
            CrudArtifactProducer crudArtifactProducer,
            OracleDdlSanityChecker oracleDdlSanityChecker) {
        this(
                preparationService,
                metadataRepositoryResolver,
                artifactNamingPolicy,
                diagramArtifactProducer,
                migrationArtifactProducer,
                comparisonArtifactProducer,
                crudArtifactProducer,
                new LegacyWordSpecificationParser(),
                oracleDdlSanityChecker);
    }

    DocumentGenerationOrchestrator(
            SchemaPreparationService preparationService,
            MetadataRepositoryResolver metadataRepositoryResolver,
            ArtifactNamingPolicy artifactNamingPolicy,
            DiagramArtifactProducer diagramArtifactProducer,
            MigrationArtifactProducer migrationArtifactProducer,
            ComparisonArtifactProducer comparisonArtifactProducer,
            CrudArtifactProducer crudArtifactProducer,
            LegacyWordSpecificationParser legacyWordSpecificationParser,
            OracleDdlSanityChecker oracleDdlSanityChecker) {
        this.preparationService = Objects.requireNonNull(preparationService, "preparationService must not be null");
        this.metadataRepositoryResolver = Objects.requireNonNull(
                metadataRepositoryResolver, "metadataRepositoryResolver must not be null");
        this.artifactNamingPolicy = Objects.requireNonNull(artifactNamingPolicy, "artifactNamingPolicy must not be null");
        this.diagramArtifactProducer = Objects.requireNonNull(
                diagramArtifactProducer, "diagramArtifactProducer must not be null");
        this.migrationArtifactProducer = Objects.requireNonNull(
                migrationArtifactProducer, "migrationArtifactProducer must not be null");
        this.comparisonArtifactProducer = Objects.requireNonNull(
                comparisonArtifactProducer, "comparisonArtifactProducer must not be null");
        this.crudArtifactProducer = Objects.requireNonNull(crudArtifactProducer, "crudArtifactProducer must not be null");
        this.legacyWordSpecificationParser = Objects.requireNonNull(
                legacyWordSpecificationParser, "legacyWordSpecificationParser must not be null");
        this.oracleDdlSanityChecker = Objects.requireNonNull(
                oracleDdlSanityChecker, "oracleDdlSanityChecker must not be null");
    }

    /** Parses, prepares and generates all artifacts for one Standard Word specification. */
    public PreparedSchema generateStandardWord(
            Path input, Path output, ArtifactGenerationContext context) throws IOException {
        return generateStandardWord(input, output, context, null);
    }

    /** Parses, prepares and generates all artifacts using request-level audit options. */
    public PreparedSchema generateStandardWord(
            Path input,
            Path output,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions) throws IOException {
        DatabaseSchema parsed;
        try (InputStream stream = Files.newInputStream(input)) {
            parsed = new WordSpecificationParser().parse(
                    new SpecificationSource(input.getFileName().toString(), stream));
        }
        PreparedSchema prepared = auditOptions == null
                ? preparationService.prepare(parsed)
                : preparationService.prepare(parsed, auditOptions);
        ValidationReport combinedReport = writeAllDatabaseOutputs(
                prepared, output, stripExtension(input.getFileName().toString()), context);
        return new PreparedSchema(prepared.schema(), combinedReport);
    }

    /** Parses, prepares and generates all artifacts for one Legacy Word specification. */
    public PreparedSchema generateLegacyWord(
            Path input,
            Path output,
            String schemaName,
            ArtifactGenerationContext context) throws IOException {
        return generateLegacyWord(input, output, schemaName, context, null);
    }

    /** Parses, prepares and generates Legacy Word artifacts using request-level audit options. */
    public PreparedSchema generateLegacyWord(
            Path input,
            Path output,
            String schemaName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions) throws IOException {
        DatabaseSchema parsed = legacyWordSpecificationParser.parse(
                input.getParent(), input, schemaName);
        PreparedSchema prepared = auditOptions == null
                ? preparationService.prepare(parsed)
                : preparationService.prepare(parsed, auditOptions);
        ValidationReport combinedReport = writeAllDatabaseOutputs(
                prepared, output, stripExtension(input.getFileName().toString()), context);
        return new PreparedSchema(prepared.schema(), combinedReport);
    }

    private ValidationReport writeAllDatabaseOutputs(
            PreparedSchema prepared,
            Path output,
            String baseName,
            ArtifactGenerationContext context) throws IOException {
        DatabaseSchema schema = prepared.schema();
        ValidationReport report = prepared.validationReport();
        List<ValidationIssue> jsonIssues = new ArrayList<>(report.issues());

        // All normal artifacts in one top-level request share the same timestamp.
        String timestamp = context.generationTimestamp();

        // Metadata is queried once per database output. The same comparison result is
        // reused by SQL generation and the consolidated JSON validation report.
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Dialect dialect = DialectFactory.create(platform);
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
            migrationArtifactProducer.writeMigrationArtifacts(schema, repository, output, platform, context);
            comparisonArtifactProducer.writeComparisonWorkbooks(
                    schema, repository, metadata, output, timestamp, platform, dialect, context);
        }

        crudArtifactProducer.writeMetadataCrudArtifacts(schema, output, baseName, timestamp, context);
        diagramArtifactProducer.writeMermaidArtifact(schema, output, baseName, timestamp, context);
        diagramArtifactProducer.writeGraphvizArtifact(schema, output, baseName, timestamp, context);
        diagramArtifactProducer.writeConceptualErdArtifacts(schema, output, baseName, timestamp, context);

        ValidationReport jsonReport = new ValidationReport(
                jsonIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                jsonIssues);
        Path jsonPath = output.resolve(artifactNamingPolicy.canonicalJsonRelativePath(baseName, timestamp));
        Files.createDirectories(jsonPath.getParent());
        new JsonExporter().write(jsonPath, schema, jsonReport);
        context.ledger().generated(context, ArtifactType.CANONICAL_JSON, null,
                baseName, ArtifactPaths.relative(output, jsonPath),
                "application/json", "JsonExporter");
        return jsonReport;
    }

    private void requireValidOracleDdl(DatabasePlatform platform, String sql, String source) {
        if (platform == DatabasePlatform.ORACLE) {
            oracleDdlSanityChecker.requireValid(sql, source);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
