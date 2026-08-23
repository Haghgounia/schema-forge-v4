package com.behsazan.schemaforge.artifact;

import java.util.Objects;

/** Minimal provenance attached to one artifact descriptor. */
public record ArtifactProvenance(
        ArtifactOrigin origin,
        String sourceName,
        String producer) {

    public ArtifactProvenance {
        Objects.requireNonNull(origin, "origin must not be null");
        sourceName = sourceName == null ? "" : sourceName.trim();
        producer = requireNonBlank(producer, "producer");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
