package com.behsazan.schemaforge.artifact.manifest;

/** Package-level validation counters without duplicated issue bodies. */
public record ArtifactManifestValidation(
        boolean available,
        long errorCount,
        long warningCount,
        long recoveryWarningCount) {
}
