package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactRequestStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.glossary.GlossaryReportWriter;
import com.behsazan.schemaforge.artifact.CollisionSafeArtifactTargetAllocator;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates ZIP-batch generation while preserving the established batch artifact contract.
 *
 * <p>Standard Word ZIP batches and Legacy Word ZIP batches share the same safety, isolation,
 * collision handling, diagnostics, manifest and packaging path. Parser selection is explicit;
 * the legacy mode never falls back to the standard Word parser.</p>
 */
public final class BatchGenerationOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            "com.behsazan.schemaforge.api.SchemaForgeApiService");
    private static final String LEGACY_PRODUCER = "SchemaForgeApiService";

    private final DocumentGenerationOrchestrator documentGenerationOrchestrator;
    private final DiagramArtifactProducer diagramArtifactProducer;
    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final ArtifactPackageBuilder artifactPackageBuilder;
    private final ArtifactManifestWriter artifactManifestWriter;
    private final NumericMappingStrategy numericMappingStrategy;
    private final GenerationSummaryReportWriter generationSummaryReportWriter;
    private final GlossaryReportWriter glossaryReportWriter;

    public BatchGenerationOrchestrator(
            DocumentGenerationOrchestrator documentGenerationOrchestrator,
            DiagramArtifactProducer diagramArtifactProducer,
            ArtifactNamingPolicy artifactNamingPolicy,
            ArtifactPackageBuilder artifactPackageBuilder,
            ArtifactManifestWriter artifactManifestWriter) {
        this(documentGenerationOrchestrator, diagramArtifactProducer, artifactNamingPolicy,
                artifactPackageBuilder, artifactManifestWriter, DialectFactory.configuredNumericMappingStrategy());
    }

    public BatchGenerationOrchestrator(
            DocumentGenerationOrchestrator documentGenerationOrchestrator,
            DiagramArtifactProducer diagramArtifactProducer,
            ArtifactNamingPolicy artifactNamingPolicy,
            ArtifactPackageBuilder artifactPackageBuilder,
            ArtifactManifestWriter artifactManifestWriter,
            NumericMappingStrategy numericMappingStrategy) {
        this(documentGenerationOrchestrator, diagramArtifactProducer, artifactNamingPolicy,
                artifactPackageBuilder, artifactManifestWriter, numericMappingStrategy, null);
    }

    public BatchGenerationOrchestrator(
            DocumentGenerationOrchestrator documentGenerationOrchestrator,
            DiagramArtifactProducer diagramArtifactProducer,
            ArtifactNamingPolicy artifactNamingPolicy,
            ArtifactPackageBuilder artifactPackageBuilder,
            ArtifactManifestWriter artifactManifestWriter,
            NumericMappingStrategy numericMappingStrategy,
            GlossaryReportWriter glossaryReportWriter) {
        this.glossaryReportWriter = glossaryReportWriter;
        this.documentGenerationOrchestrator = Objects.requireNonNull(
                documentGenerationOrchestrator, "documentGenerationOrchestrator must not be null");
        this.diagramArtifactProducer = Objects.requireNonNull(
                diagramArtifactProducer, "diagramArtifactProducer must not be null");
        this.artifactNamingPolicy = Objects.requireNonNull(
                artifactNamingPolicy, "artifactNamingPolicy must not be null");
        this.artifactPackageBuilder = Objects.requireNonNull(
                artifactPackageBuilder, "artifactPackageBuilder must not be null");
        this.artifactManifestWriter = Objects.requireNonNull(
                artifactManifestWriter, "artifactManifestWriter must not be null");
        this.numericMappingStrategy = Objects.requireNonNull(
                numericMappingStrategy, "numericMappingStrategy must not be null");
        this.generationSummaryReportWriter = new GenerationSummaryReportWriter(this.artifactNamingPolicy);
    }

    public byte[] generate(
            MultipartFile file,
            String logicalName,
            ArtifactGenerationContext context) throws IOException {
        return generate(file, logicalName, context, null);
    }

    public byte[] generate(
            MultipartFile file,
            String logicalName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions) throws IOException {
        return generate(file, logicalName, context, auditOptions, Set.of(DatabasePlatform.values()));
    }

    public byte[] generate(
            MultipartFile file,
            String logicalName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms) throws IOException {
        return generate(
                file, logicalName, context, auditOptions, platforms, TimestampSemantics.SOURCE);
    }

    public byte[] generate(
            MultipartFile file,
            String logicalName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms,
            TimestampSemantics timestampSemantics) throws IOException {
        return generateInternal(
                file, logicalName, context, auditOptions, platforms, timestampSemantics,
                BatchInputMode.STANDARD_WORD, null);
    }

    /**
     * Generates one isolated batch from a ZIP of legacy Word table specifications.
     *
     * <p>Both {@code .doc} and {@code .docx} inputs are accepted. The schema is mandatory and is
     * applied to every legacy document in the batch; SchemaForge does not guess a schema per file.</p>
     */
    public byte[] generateLegacyWordBatch(
            MultipartFile file,
            String logicalName,
            String schemaName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms) throws IOException {
        return generateLegacyWordBatch(
                file, logicalName, schemaName, context, auditOptions, platforms, TimestampSemantics.SOURCE);
    }

    public byte[] generateLegacyWordBatch(
            MultipartFile file,
            String logicalName,
            String schemaName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms,
            TimestampSemantics timestampSemantics) throws IOException {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException("Legacy Word schema parameter is required");
        }
        return generateInternal(
                file,
                logicalName,
                context,
                auditOptions,
                platforms,
                timestampSemantics,
                BatchInputMode.LEGACY_WORD,
                schemaName.trim());
    }

    private byte[] generateInternal(
            MultipartFile file,
            String logicalName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms,
            TimestampSemantics timestampSemantics,
            BatchInputMode inputMode,
            String legacySchemaName) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(platforms, "platforms must not be null");
        Objects.requireNonNull(timestampSemantics, "timestampSemantics must not be null");
        Objects.requireNonNull(inputMode, "inputMode must not be null");
        if (platforms.isEmpty()) {
            throw new IllegalArgumentException("At least one database platform must be selected");
        }

        Path work = Files.createTempDirectory(inputMode.tempDirectoryPrefix());
        try {
            Path inputDir = Files.createDirectories(work.resolve("input"));
            Path outputDir = Files.createDirectories(work.resolve("output"));
            BatchArchiveSupport.unzipSafely(file, inputDir);

            List<Path> inputFiles = BatchArchiveSupport.regularInputFiles(inputDir);
            long processableDocuments = inputFiles.stream()
                    .filter(inputMode::isProcessable)
                    .count();
            if (processableDocuments == 0) {
                throw new IllegalArgumentException(inputMode.emptyArchiveMessage());
            }

            List<String> summary = new ArrayList<>();
            summary.add("sequence,document,status,generated_files,error");
            StringBuilder errors = new StringBuilder();
            int sequence = 0;
            int successCount = 0;
            int failedCount = 0;
            int skippedCount = 0;
            List<Table> batchDiagramTables = new ArrayList<>();
            List<ArtifactManifestAssembler.ModelInput> manifestModels = new ArrayList<>();
            CollisionSafeArtifactTargetAllocator batchTargetAllocator =
                    new CollisionSafeArtifactTargetAllocator();
            Map<String, String> acceptedContentHashes = new LinkedHashMap<>();
            Map<String, String> acceptedLogicalTables = new LinkedHashMap<>();

            for (Path document : inputFiles) {
                sequence++;
                String relativeDocument = artifactPackageBuilder.normalizePath(inputDir.relativize(document));
                String skippedInputReason = inputMode.skippedInputReason(document);
                if (skippedInputReason != null) {
                    skippedCount++;
                    summary.add(BatchArchiveSupport.csvLine(
                            Integer.toString(sequence), relativeDocument, "SKIPPED", "0", skippedInputReason));
                    continue;
                }

                String contentHash = sha256(document);
                String duplicateSource = acceptedContentHashes.get(contentHash);
                if (duplicateSource != null) {
                    skippedCount++;
                    summary.add(BatchArchiveSupport.csvLine(
                            Integer.toString(sequence),
                            relativeDocument,
                            "SKIPPED",
                            "0",
                            "DUPLICATE_SOURCE_CONTENT: identical to " + duplicateSource));
                    continue;
                }

                Path documentOutput = Files.createDirectories(
                        work.resolve("staging").resolve(String.format(Locale.ROOT, "%05d", sequence)));
                try {
                    ArtifactGenerationContext documentContext = context.isolatedChild(
                            inputMode.artifactOrigin(), relativeDocument);
                    PreparedSchema prepared = generateDocument(
                            inputMode,
                            document,
                            documentOutput,
                            legacySchemaName,
                            documentContext,
                            auditOptions,
                            platforms,
                            timestampSemantics);

                    String duplicateLogicalTable = null;
                    String duplicateLogicalSource = null;
                    for (Table table : prepared.schema().tables()) {
                        String logicalKey = logicalTableKey(table);
                        String priorSource = acceptedLogicalTables.get(logicalKey);
                        if (priorSource != null) {
                            duplicateLogicalTable = logicalKey;
                            duplicateLogicalSource = priorSource;
                            break;
                        }
                    }
                    if (duplicateLogicalTable != null) {
                        skippedCount++;
                        summary.add(BatchArchiveSupport.csvLine(
                                Integer.toString(sequence),
                                relativeDocument,
                                "SKIPPED",
                                "0",
                                "DUPLICATE_LOGICAL_TABLE: " + duplicateLogicalTable
                                        + " already provided by " + duplicateLogicalSource));
                        continue;
                    }

                    batchDiagramTables.addAll(prepared.schema().tables());
                    long generatedFiles = BatchArchiveSupport.countRegularFiles(documentOutput);
                    Map<String, String> remappedPaths = BatchArchiveSupport.moveGeneratedFiles(
                            documentOutput, outputDir, batchTargetAllocator, relativeDocument);
                    BatchArchiveSupport.mergeBatchArtifacts(documentContext, context, remappedPaths);
                    manifestModels.add(new ArtifactManifestAssembler.ModelInput(
                            relativeDocument, prepared.schema(), prepared.validationReport()));
                    acceptedContentHashes.put(contentHash, relativeDocument);
                    for (Table table : prepared.schema().tables()) {
                        acceptedLogicalTables.put(logicalTableKey(table), relativeDocument);
                    }
                    successCount++;
                    summary.add(BatchArchiveSupport.csvLine(
                            Integer.toString(sequence),
                            relativeDocument,
                            "SUCCESS",
                            Long.toString(generatedFiles),
                            ""));
                } catch (Exception exception) {
                    failedCount++;
                    String message = BatchArchiveSupport.safeMessage(exception);
                    LOGGER.warn("{} document skipped after generation failure: {} - {}",
                            inputMode.logLabel(), relativeDocument, message);
                    summary.add(BatchArchiveSupport.csvLine(
                            Integer.toString(sequence),
                            relativeDocument,
                            "FAILED",
                            "0",
                            exception.getClass().getSimpleName() + ": " + message));
                    BatchArchiveSupport.appendBatchError(errors, sequence, relativeDocument, exception);
                } finally {
                    artifactPackageBuilder.deleteRecursively(documentOutput);
                }
            }

            if (!batchDiagramTables.isEmpty()) {
                diagramArtifactProducer.writeBatchMermaidArtifacts(batchDiagramTables, outputDir, context);
                diagramArtifactProducer.writeBatchGraphvizArtifacts(batchDiagramTables, outputDir, context);
            }

            Path summaryPath = outputDir.resolve(artifactNamingPolicy.batchGenerationSummaryRelativePath());
            Files.createDirectories(summaryPath.getParent());
            Files.writeString(summaryPath, String.join("\n", summary) + "\n", StandardCharsets.UTF_8);
            context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                    "batch-generation", ArtifactPaths.relative(outputDir, summaryPath),
                    "text/csv", LEGACY_PRODUCER);

            Path errorPath = outputDir.resolve(artifactNamingPolicy.batchGenerationErrorRelativePath());
            Files.writeString(errorPath, errors.toString(), StandardCharsets.UTF_8);
            context.ledger().generated(context, ArtifactType.ERROR_REPORT, null,
                    "batch-generation", ArtifactPaths.relative(outputDir, errorPath),
                    "text/plain", LEGACY_PRODUCER);

            Map<String, Object> manifestExtensions = new LinkedHashMap<>();
            Map<String, Object> generationOptions = new LinkedHashMap<>();
            generationOptions.put("numericMapping", Map.of("strategy", numericMappingStrategy.name()));
            generationOptions.put("platforms", DatabasePlatform.values().length == platforms.size()
                    ? List.of("all")
                    : DatabasePlatform.valuesAsList(platforms));
            generationOptions.put("timestampSemantics", timestampSemantics.requestValue());
            if (auditOptions != null) {
                generationOptions.put("audit", auditOptions.manifestValue());
            }
            if (inputMode == BatchInputMode.LEGACY_WORD) {
                generationOptions.put("legacyWordSchema", legacySchemaName);
            }
            manifestExtensions.put("generationOptions", generationOptions);
            Map<String, Object> batchInput = new LinkedHashMap<>();
            batchInput.put("regularFileCount", inputFiles.size());
            batchInput.put("processableDocumentCount", processableDocuments);
            batchInput.put("successCount", successCount);
            batchInput.put("failedCount", failedCount);
            batchInput.put("skippedCount", skippedCount);
            if (inputMode == BatchInputMode.LEGACY_WORD) {
                batchInput.put("inputMode", inputMode.manifestValue());
                batchInput.put("schema", legacySchemaName);
            }
            manifestExtensions.put("batchInput", batchInput);

            ArtifactRequestStatus batchRequestStatus =
                    failedCount > 0
                            ? ArtifactRequestStatus.PARTIAL_SUCCESS
                            : null;

            Map<String, String> summaryFacts = new LinkedHashMap<>();
            summaryFacts.put("Input Documents", Long.toString(processableDocuments));
            summaryFacts.put("Successful Documents", Integer.toString(successCount));
            summaryFacts.put("Failed Documents", Integer.toString(failedCount));
            summaryFacts.put("Skipped Inputs", Integer.toString(skippedCount));
            if (inputMode == BatchInputMode.LEGACY_WORD) {
                summaryFacts.put("Legacy Word Schema", legacySchemaName);
            }
            List<DatabaseSchema> extractedSchemas = manifestModels.stream()
                    .map(ArtifactManifestAssembler.ModelInput::schema)
                    .toList();
            generationSummaryReportWriter.write(
                    outputDir, context, extractedSchemas,
                    platforms,
                    summaryFacts, batchRequestStatus);

            if (glossaryReportWriter != null) {
                glossaryReportWriter.write(outputDir, context, manifestModels);
            }
            artifactManifestWriter.write(
                    outputDir,
                    context,
                    logicalName,
                    manifestModels,
                    manifestExtensions,
                    batchRequestStatus);

            return artifactPackageBuilder.zipDirectory(outputDir);
        } finally {
            artifactPackageBuilder.deleteRecursively(work);
        }
    }

    private PreparedSchema generateDocument(
            BatchInputMode inputMode,
            Path document,
            Path documentOutput,
            String legacySchemaName,
            ArtifactGenerationContext documentContext,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms,
            TimestampSemantics timestampSemantics) throws IOException {
        return switch (inputMode) {
            case STANDARD_WORD -> documentGenerationOrchestrator.generateStandardWord(
                    document, documentOutput, documentContext, auditOptions, platforms, timestampSemantics);
            case LEGACY_WORD -> documentGenerationOrchestrator.generateLegacyWord(
                    document, documentOutput, legacySchemaName, documentContext, auditOptions, platforms, timestampSemantics);
        };
    }

    private static String logicalTableKey(Table table) {
        return table.qualifiedName().schemaName()
                .map(schema -> schema.normalized() + ".")
                .orElse("")
                + table.qualifiedName().name().normalized();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private enum BatchInputMode {
        STANDARD_WORD(
                ArtifactOrigin.ZIP_BATCH,
                "schemaforge-zip-",
                "ZIP",
                "STANDARD_WORD",
                "ZIP does not contain any processable DOCX files"),
        LEGACY_WORD(
                ArtifactOrigin.LEGACY_WORD_BATCH,
                "schemaforge-legacy-word-batch-",
                "Legacy Word ZIP",
                "LEGACY_WORD",
                "ZIP does not contain any processable Legacy Word .doc or .docx files");

        private final ArtifactOrigin artifactOrigin;
        private final String tempDirectoryPrefix;
        private final String logLabel;
        private final String manifestValue;
        private final String emptyArchiveMessage;

        BatchInputMode(
                ArtifactOrigin artifactOrigin,
                String tempDirectoryPrefix,
                String logLabel,
                String manifestValue,
                String emptyArchiveMessage) {
            this.artifactOrigin = artifactOrigin;
            this.tempDirectoryPrefix = tempDirectoryPrefix;
            this.logLabel = logLabel;
            this.manifestValue = manifestValue;
            this.emptyArchiveMessage = emptyArchiveMessage;
        }

        ArtifactOrigin artifactOrigin() {
            return artifactOrigin;
        }

        String tempDirectoryPrefix() {
            return tempDirectoryPrefix;
        }

        String logLabel() {
            return logLabel;
        }

        String manifestValue() {
            return manifestValue;
        }

        String emptyArchiveMessage() {
            return emptyArchiveMessage;
        }

        boolean isProcessable(Path path) {
            return skippedInputReason(path) == null;
        }

        String skippedInputReason(Path path) {
            return switch (this) {
                case STANDARD_WORD -> BatchArchiveSupport.skippedInputReason(path);
                case LEGACY_WORD -> BatchArchiveSupport.skippedLegacyWordInputReason(path);
            };
        }
    }
}
