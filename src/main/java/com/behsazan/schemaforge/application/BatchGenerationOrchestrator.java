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

            List<Path> inputFiles = BatchArchiveSupport.regularInputFiles(inputDir);
            long processableDocuments = inputFiles.stream()
                    .filter(BatchArchiveSupport::isProcessableWordDocument)
                    .count();
            if (processableDocuments == 0) {
                throw new IllegalArgumentException("ZIP does not contain any processable DOCX files");
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
                String skippedInputReason = BatchArchiveSupport.skippedInputReason(document);
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
                            ArtifactOrigin.ZIP_BATCH, relativeDocument);
                    PreparedSchema prepared = documentGenerationOrchestrator.generateStandardWord(
                            document, documentOutput, documentContext, auditOptions);

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

            Map<String, Object> manifestExtensions = new LinkedHashMap<>();
            if (auditOptions != null) {
                manifestExtensions.put(
                        "generationOptions", Map.of("audit", auditOptions.manifestValue()));
            }
            manifestExtensions.put("batchInput", Map.of(
                    "regularFileCount", inputFiles.size(),
                    "processableDocumentCount", processableDocuments,
                    "successCount", successCount,
                    "failedCount", failedCount,
                    "skippedCount", skippedCount));

            artifactManifestWriter.write(
                    outputDir, context, logicalName, manifestModels, manifestExtensions);
            return artifactPackageBuilder.zipDirectory(outputDir);
        } finally {
            artifactPackageBuilder.deleteRecursively(work);
        }
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
}
