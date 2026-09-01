package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request-local collector for Artifact Contract descriptors.
 *
 * <p>The ledger owns metadata only; it never owns artifact bytes and never performs filesystem
 * writes. A new ledger must be created for each top-level generation request.</p>
 */
public final class ArtifactLedger {
    private final List<ArtifactDescriptor> descriptors = new ArrayList<>();

    public void add(ArtifactDescriptor descriptor) {
        descriptors.add(Objects.requireNonNull(descriptor, "descriptor must not be null"));
    }

    public void generated(
            ArtifactGenerationContext context,
            ArtifactType type,
            DatabasePlatform platform,
            String logicalName,
            String relativePath,
            String mediaType,
            String producer) {
        add(descriptor(context, type, platform, logicalName, relativePath, mediaType,
                ArtifactStatus.GENERATED, producer, ""));
    }

    public void skipped(
            ArtifactGenerationContext context,
            ArtifactType type,
            DatabasePlatform platform,
            String logicalName,
            String producer) {
        skipped(context, type, platform, logicalName, producer, "SKIPPED_BY_PRODUCER");
    }

    public void skipped(
            ArtifactGenerationContext context,
            ArtifactType type,
            DatabasePlatform platform,
            String logicalName,
            String producer,
            String outcomeReason) {
        add(descriptor(context, type, platform, logicalName, "", "",
                ArtifactStatus.SKIPPED, producer, outcomeReason));
    }

    public void failed(
            ArtifactGenerationContext context,
            ArtifactType type,
            DatabasePlatform platform,
            String logicalName,
            String producer) {
        add(descriptor(context, type, platform, logicalName, "", "",
                ArtifactStatus.FAILED, producer, ""));
    }

    public List<ArtifactDescriptor> snapshot() {
        return List.copyOf(descriptors);
    }

    private static ArtifactDescriptor descriptor(
            ArtifactGenerationContext context,
            ArtifactType type,
            DatabasePlatform platform,
            String logicalName,
            String relativePath,
            String mediaType,
            ArtifactStatus status,
            String producer,
            String outcomeReason) {
        Objects.requireNonNull(context, "context must not be null");
        return new ArtifactDescriptor(
                type,
                platform,
                logicalName,
                relativePath,
                mediaType,
                context.generationId(),
                status,
                context.provenance(producer),
                outcomeReason);
    }
}
