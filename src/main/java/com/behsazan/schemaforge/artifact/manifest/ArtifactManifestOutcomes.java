package com.behsazan.schemaforge.artifact.manifest;

/** Aggregate Artifact Contract outcome counts. */
public record ArtifactManifestOutcomes(
        long generated,
        long skipped,
        long failed) {
}
