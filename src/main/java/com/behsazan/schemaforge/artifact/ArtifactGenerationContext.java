package com.behsazan.schemaforge.artifact;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Request-local identity, timestamp and provenance context shared by related generated artifacts.
 */
public record ArtifactGenerationContext(
        String generationId,
        String generationTimestamp,
        OffsetDateTime generatedAt,
        ArtifactOrigin origin,
        String sourceName,
        ArtifactLedger ledger) {

    public ArtifactGenerationContext {
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId must not be blank");
        }
        ArtifactNamingPolicy.validateTimestamp(generationTimestamp);
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        sourceName = sourceName == null ? "" : sourceName.trim();
        Objects.requireNonNull(ledger, "ledger must not be null");
    }

    public static ArtifactGenerationContext create(ArtifactOrigin origin, String sourceName) {
        OffsetDateTime generatedAt = OffsetDateTime.now();
        return create(origin, sourceName, ArtifactNamingPolicy.timestamp(generatedAt), generatedAt);
    }

    public static ArtifactGenerationContext create(
            ArtifactOrigin origin, String sourceName, String generationTimestamp) {
        return create(origin, sourceName, generationTimestamp, OffsetDateTime.now());
    }

    public static ArtifactGenerationContext create(
            ArtifactOrigin origin, String sourceName, String generationTimestamp, OffsetDateTime generatedAt) {
        return new ArtifactGenerationContext(
                UUID.randomUUID().toString(), generationTimestamp, generatedAt,
                origin, sourceName, new ArtifactLedger());
    }

    /** Creates a child context that shares generation identity, timestamp and ledger. */
    public ArtifactGenerationContext child(ArtifactOrigin childOrigin, String childSourceName) {
        return new ArtifactGenerationContext(
                generationId, generationTimestamp, generatedAt, childOrigin, childSourceName, ledger);
    }

    /** Creates a child context with an isolated ledger while preserving generation identity/timestamp. */
    public ArtifactGenerationContext isolatedChild(
            ArtifactOrigin childOrigin, String childSourceName) {
        return new ArtifactGenerationContext(
                generationId, generationTimestamp, generatedAt, childOrigin, childSourceName, new ArtifactLedger());
    }

    public ArtifactProvenance provenance(String producer) {
        return new ArtifactProvenance(origin, sourceName, producer);
    }
}
