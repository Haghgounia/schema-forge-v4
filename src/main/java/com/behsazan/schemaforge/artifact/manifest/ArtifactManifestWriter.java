package com.behsazan.schemaforge.artifact.manifest;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactRequestStatus;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes exactly one Standard Artifact Manifest V1 at package root. */
public final class ArtifactManifestWriter {

    private final ObjectMapper objectMapper;

    private final ArtifactManifestAssembler assembler =
            new ArtifactManifestAssembler();

    private final ArtifactNamingPolicy namingPolicy =
            new ArtifactNamingPolicy();

    public ArtifactManifestWriter(
            ObjectMapper objectMapper) {

        this.objectMapper =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null");
    }

    public ArtifactManifest write(
            Path artifactRoot,
            ArtifactGenerationContext context,
            String logicalName,
            List<ArtifactManifestAssembler.ModelInput> models,
            Map<String, Object> extensions) throws IOException {

        return write(
                artifactRoot,
                context,
                logicalName,
                models,
                extensions,
                null);
    }

    public ArtifactManifest write(
            Path artifactRoot,
            ArtifactGenerationContext context,
            String logicalName,
            List<ArtifactManifestAssembler.ModelInput> models,
            Map<String, Object> extensions,
            ArtifactRequestStatus requestedStatus) throws IOException {

        Objects.requireNonNull(
                artifactRoot,
                "artifactRoot must not be null");

        Objects.requireNonNull(
                context,
                "context must not be null");

        if (context.ledger()
                .snapshot()
                .stream()
                .anyMatch(
                        descriptor ->
                                descriptor.type() == ArtifactType.MANIFEST
                                        && descriptor.status()
                                        == ArtifactStatus.GENERATED)) {

            throw new IllegalStateException(
                    "Manifest was already registered for this generation");
        }

        Path relative =
                namingPolicy.manifestRelativePath();

        context.ledger().generated(
                context,
                ArtifactType.MANIFEST,
                null,
                logicalName,
                relative.toString()
                        .replace('\\', '/'),
                "application/json",
                "ArtifactManifestWriter");

        ArtifactManifest manifest =
                assembler.assemble(
                        artifactRoot,
                        context,
                        models,
                        extensions,
                        requestedStatus);

        Path manifestPath =
                artifactRoot.resolve(relative);

        Files.createDirectories(
                manifestPath.getParent() == null
                        ? artifactRoot
                        : manifestPath.getParent());

        objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        manifestPath.toFile(),
                        manifest);

        assembler.validateFinalPackage(
                artifactRoot,
                context);

        return manifest;
    }
}