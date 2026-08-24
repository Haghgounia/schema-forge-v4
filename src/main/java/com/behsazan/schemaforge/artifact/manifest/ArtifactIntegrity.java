package com.behsazan.schemaforge.artifact.manifest;

/** Exact packaged-byte integrity metadata for one generated artifact. */
public record ArtifactIntegrity(
        String algorithm,
        String sha256,
        long sizeBytes) {
}
