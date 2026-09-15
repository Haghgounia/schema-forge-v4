package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the shared single-document workspace, manifest and package workflow.
 *
 * <p>Parsing, schema preparation and artifact generation remain delegated to
 * {@link DocumentGenerationOrchestrator}. Request validation, source-name
 * normalization, generation-context creation and API response construction remain
 * in the REST-facing facade.</p>
 */
public final class ArtifactGenerationService {
    private final DocumentGenerationOrchestrator documentGenerationOrchestrator;
    private final ArtifactPackageBuilder artifactPackageBuilder;
    private final ArtifactManifestWriter artifactManifestWriter;
    private final NumericMappingStrategy numericMappingStrategy;
    private final GenerationSummaryReportWriter generationSummaryReportWriter;

    public ArtifactGenerationService(
            DocumentGenerationOrchestrator documentGenerationOrchestrator,
            ArtifactPackageBuilder artifactPackageBuilder,
            ArtifactManifestWriter artifactManifestWriter) {
        this(documentGenerationOrchestrator, artifactPackageBuilder, artifactManifestWriter,
                DialectFactory.configuredNumericMappingStrategy());
    }

    public ArtifactGenerationService(
            DocumentGenerationOrchestrator documentGenerationOrchestrator,
            ArtifactPackageBuilder artifactPackageBuilder,
            ArtifactManifestWriter artifactManifestWriter,
            NumericMappingStrategy numericMappingStrategy) {
        this.documentGenerationOrchestrator = Objects.requireNonNull(
                documentGenerationOrchestrator, "documentGenerationOrchestrator must not be null");
        this.artifactPackageBuilder = Objects.requireNonNull(
                artifactPackageBuilder, "artifactPackageBuilder must not be null");
        this.artifactManifestWriter = Objects.requireNonNull(
                artifactManifestWriter, "artifactManifestWriter must not be null");
        this.numericMappingStrategy = Objects.requireNonNull(
                numericMappingStrategy, "numericMappingStrategy must not be null");
        this.generationSummaryReportWriter = new GenerationSummaryReportWriter(new ArtifactNamingPolicy());
    }

    /** Generates and packages one Standard Word specification. */
    public byte[] generateStandardWord(
            MultipartFile file,
            String sourceName,
            ArtifactGenerationContext context) throws IOException {
        return generateStandardWord(file, sourceName, context, null);
    }

    public byte[] generateStandardWord(
            MultipartFile file,
            String sourceName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions) throws IOException {
        return generateStandardWord(file, sourceName, context, auditOptions, Set.of(DatabasePlatform.values()));
    }

    public byte[] generateStandardWord(
            MultipartFile file,
            String sourceName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(platforms, "platforms must not be null");
        return generate(file, sourceName, "schemaforge-word-", context, auditOptions, platforms,
                (input, output) -> documentGenerationOrchestrator.generateStandardWord(
                        input, output, context, auditOptions, platforms));
    }

    /** Generates and packages one Legacy Word specification using the required schema. */
    public byte[] generateLegacyWord(
            MultipartFile file,
            String sourceName,
            String schemaName,
            ArtifactGenerationContext context) throws IOException {
        return generateLegacyWord(file, sourceName, schemaName, context, null);
    }

    public byte[] generateLegacyWord(
            MultipartFile file,
            String sourceName,
            String schemaName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions) throws IOException {
        return generateLegacyWord(file, sourceName, schemaName, context, auditOptions, Set.of(DatabasePlatform.values()));
    }

    public byte[] generateLegacyWord(
            MultipartFile file,
            String sourceName,
            String schemaName,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(platforms, "platforms must not be null");
        return generate(file, sourceName, "schemaforge-legacy-word-", context, auditOptions, platforms,
                (input, output) -> documentGenerationOrchestrator.generateLegacyWord(
                        input, output, schemaName, context, auditOptions, platforms));
    }

    private byte[] generate(
            MultipartFile file,
            String sourceName,
            String workPrefix,
            ArtifactGenerationContext context,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms,
            DocumentGenerator generator) throws IOException {
        Path work = Files.createTempDirectory(workPrefix);
        try {
            Path input = work.resolve(sourceName);
            file.transferTo(input);
            Path output = Files.createDirectories(work.resolve("output"));
            PreparedSchema prepared = generator.generate(input, output);
            generationSummaryReportWriter.write(
                    output, context, prepared.schema(), platforms);
            writeStandardManifest(output, context, stripExtension(sourceName), sourceName, prepared, auditOptions, platforms);
            return artifactPackageBuilder.zipDirectory(output);
        } finally {
            artifactPackageBuilder.deleteRecursively(work);
        }
    }

    private void writeStandardManifest(
            Path output,
            ArtifactGenerationContext context,
            String logicalName,
            String sourceName,
            PreparedSchema prepared,
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms) throws IOException {
        artifactManifestWriter.write(
                output,
                context,
                logicalName,
                List.of(new ArtifactManifestAssembler.ModelInput(
                        sourceName, prepared.schema(), prepared.validationReport())),
                generationExtensions(auditOptions, platforms));
    }


    private Map<String, Object> generationExtensions(
            AuditGenerationOptions auditOptions,
            Set<DatabasePlatform> platforms) {
        Map<String, Object> generationOptions = new java.util.LinkedHashMap<>();
        generationOptions.put("numericMapping", Map.of("strategy", numericMappingStrategy.name()));
        generationOptions.put("platforms", DatabasePlatform.values().length == platforms.size()
                ? List.of("all")
                : DatabasePlatform.valuesAsList(platforms));
        if (auditOptions != null) {
            generationOptions.put("audit", auditOptions.manifestValue());
        }
        return Map.of("generationOptions", generationOptions);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @FunctionalInterface
    private interface DocumentGenerator {
        PreparedSchema generate(Path input, Path output) throws IOException;
    }
}
