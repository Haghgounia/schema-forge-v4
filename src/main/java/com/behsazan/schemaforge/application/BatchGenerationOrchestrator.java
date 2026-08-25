package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.CollisionSafeArtifactTargetAllocator;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.domain.model.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates ZIP-batch generation while preserving the established batch artifact contract.
 *
 * <p>Request validation and public API signatures stay in the REST-facing facade. This class owns
 * only the existing batch workflow: safe extraction, per-document isolated generation, collision-safe
 * merge, aggregate diagrams, diagnostic reports, Standard Manifest V1 and final ZIP packaging.</p>
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

    public BatchGenerationOrchestrator(
            DocumentGenerationOrchestrator documentGenerationOrchestrator,
            DiagramArtifactProducer diagramArtifactProducer,
            ArtifactNamingPolicy artifactNamingPolicy,
            ArtifactPackageBuilder artifactPackageBuilder,
            ArtifactManifestWriter artifactManifestWriter) {
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
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(context, "context must not be null");

        Path work = Files.createTempDirectory("schemaforge-zip-");
        try {
            Path inputDir = Files.createDirectories(work.resolve("input"));
            Path outputDir = Files.createDirectories(work.resolve("output"));
            BatchArchiveSupport.unzipSafely(file, inputDir);

            List<Path> documents = BatchArchiveSupport.processableWordDocuments(inputDir);
            if (documents.isEmpty()) {
                throw new IllegalArgumentException("ZIP does not contain any processable DOCX files");
            }

            List<String> summary = new ArrayList<>();
            summary.add("sequence,document,status,generated_files,error");
            StringBuilder errors = new StringBuilder();
            int sequence = 0;
            List<Table> batchDiagramTables = new ArrayList<>();
            List<ArtifactManifestAssembler.ModelInput> manifestModels = new ArrayList<>();
            CollisionSafeArtifactTargetAllocator batchTargetAllocator =
                    new CollisionSafeArtifactTargetAllocator();

            for (Path document : documents) {
                sequence++;
                String relativeDocument = artifactPackageBuilder.normalizePath(inputDir.relativize(document));
                Path documentOutput = Files.createDirectories(
                        work.resolve("staging").resolve(String.format(Locale.ROOT, "%05d", sequence)));
                try {
                    ArtifactGenerationContext documentContext = context.isolatedChild(
                            ArtifactOrigin.ZIP_BATCH, relativeDocument);
                    PreparedSchema prepared = documentGenerationOrchestrator.generateStandardWord(
                            document, documentOutput, documentContext, auditOptions);
                    batchDiagramTables.addAll(prepared.schema().tables());
                    long generatedFiles = BatchArchiveSupport.countRegularFiles(documentOutput);
                    Map<String, String> remappedPaths = BatchArchiveSupport.moveGeneratedFiles(
                            documentOutput, outputDir, batchTargetAllocator, relativeDocument);
                    BatchArchiveSupport.mergeBatchArtifacts(documentContext, context, remappedPaths);
                    manifestModels.add(new ArtifactManifestAssembler.ModelInput(
                            relativeDocument, prepared.schema(), prepared.validationReport()));
                    summary.add(BatchArchiveSupport.csvLine(
                            Integer.toString(sequence),
                            relativeDocument,
                            "SUCCESS",
                            Long.toString(generatedFiles),
                            ""));
                } catch (Exception exception) {
                    String message = BatchArchiveSupport.safeMessage(exception);
                    LOGGER.warn("ZIP document skipped after generation failure: {} - {}",
                            relativeDocument, message);
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

            artifactManifestWriter.write(
                    outputDir, context, logicalName, manifestModels,
                    auditOptions == null
                            ? Map.of()
                            : Map.of("generationOptions", Map.of("audit", auditOptions.manifestValue())));
            return artifactPackageBuilder.zipDirectory(outputDir);
        } finally {
            artifactPackageBuilder.deleteRecursively(work);
        }
    }
}
