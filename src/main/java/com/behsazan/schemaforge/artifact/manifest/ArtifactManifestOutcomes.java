package com.behsazan.schemaforge.artifact.manifest;


import com.behsazan.schemaforge.artifact.ArtifactRequestStatus;

/**
 * Aggregate outcome counts for artifacts represented in a SchemaForge manifest.
 */
public record ArtifactManifestOutcomes(
        long generated,
        long blocked,
        long skipped,
        long failed) {

    public ArtifactManifestOutcomes {

        if (generated < 0) {
            throw new IllegalArgumentException("generated must not be negative");
        }

        if (blocked < 0) {
            throw new IllegalArgumentException("blocked must not be negative");
        }

        if (skipped < 0) {
            throw new IllegalArgumentException("skipped must not be negative");
        }

        if (failed < 0) {
            throw new IllegalArgumentException("failed must not be negative");
        }
    }

    /**
     * Aggregate request status for a completed artifact-generation request.
     *
     * <p>Skipped artifacts alone do not make a request partial. A completed
     * request becomes PARTIAL_SUCCESS when at least one artifact is BLOCKED
     * or FAILED while the final package/manifest remains trustworthy.</p>
     */
    public ArtifactRequestStatus requestStatus() {

        if (blocked > 0 || failed > 0) {
            return ArtifactRequestStatus.PARTIAL_SUCCESS;
        }

        return ArtifactRequestStatus.SUCCESS;
    }

    /**
     * Total number of artifact outcomes represented by this aggregate.
     */
    public long total() {
        return generated + blocked + skipped + failed;
    }
}