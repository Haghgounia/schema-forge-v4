package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.util.Objects;
import java.util.Optional;

/**
 * Database-neutral metadata for one SchemaForge artifact generation outcome.
 *
 * <p>This record describes an artifact; it does not own artifact bytes, a filesystem path, or an
 * HTTP response. {@code platform == null} is the explicit representation of a platform-neutral
 * artifact. For {@link ArtifactStatus#GENERATED}, both {@code relativePath} and {@code mediaType}
 * are mandatory. Skipped or failed outcomes may omit them.</p>
 *
 * <p>{@code relativePath} is a portable package-relative identity and therefore uses forward
 * slashes. It is not required to match the host operating system's path separator.</p>
 *
 * <p>{@code outcomeReason} is optional for generated artifacts and captures a human-readable,
 * deterministic reason for skipped/failed outcomes so package consumers do not need to infer the
 * reason from logs or a separate report.</p>
 */
public record ArtifactDescriptor(
        ArtifactType type,
        DatabasePlatform platform,
        String logicalName,
        String relativePath,
        String mediaType,
        String generationId,
        ArtifactStatus status,
        ArtifactProvenance provenance,
        String outcomeReason) {

    public ArtifactDescriptor(
            ArtifactType type,
            DatabasePlatform platform,
            String logicalName,
            String relativePath,
            String mediaType,
            String generationId,
            ArtifactStatus status,
            ArtifactProvenance provenance) {
        this(type, platform, logicalName, relativePath, mediaType, generationId, status, provenance, "");
    }

    public ArtifactDescriptor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        logicalName = requireNonBlank(logicalName, "logicalName");
        generationId = requireNonBlank(generationId, "generationId");
        relativePath = normalizeOptional(relativePath);
        mediaType = normalizeOptional(mediaType);
        outcomeReason = normalizeOptional(outcomeReason);

        if (!relativePath.isEmpty()) {
            validateRelativePath(relativePath);
        }
        if (!mediaType.isEmpty() && !mediaType.contains("/")) {
            throw new IllegalArgumentException("mediaType must be a valid type/subtype value");
        }
        if (status == ArtifactStatus.GENERATED) {
            if (relativePath.isEmpty()) {
                throw new IllegalArgumentException("relativePath is required for generated artifacts");
            }
            if (mediaType.isEmpty()) {
                throw new IllegalArgumentException("mediaType is required for generated artifacts");
            }
        }
    }

    /** Returns the platform when this artifact is DBMS-specific. */
    public Optional<DatabasePlatform> platformOptional() {
        return Optional.ofNullable(platform);
    }

    /** True for artifacts such as canonical JSON, reports, and diagrams that are DBMS-neutral. */
    public boolean platformNeutral() {
        return platform == null;
    }

    /** Artifact Contract version implemented by this descriptor type. */
    public String contractVersion() {
        return ArtifactContract.VERSION;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validateRelativePath(String path) {
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("relativePath must not be absolute: " + path);
        }
        if (path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relativePath must use '/' separators: " + path);
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("relativePath contains an invalid segment: " + path);
            }
        }
    }
}
