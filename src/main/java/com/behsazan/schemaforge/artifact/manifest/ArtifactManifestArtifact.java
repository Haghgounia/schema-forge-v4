package com.behsazan.schemaforge.artifact.manifest;

import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.application.DatabasePlatform;

/** Manifest serialization of one Artifact Contract outcome. */
public record ArtifactManifestArtifact(
        ArtifactType type,
        DatabasePlatform platform,
        String logicalName,
        String path,
        String mediaType,
        ArtifactStatus status,
        String outcomeReason,
        Provenance provenance,
        ArtifactIntegrity integrity) {

    public record Provenance(
            ArtifactOrigin origin,
            String sourceName,
            String producer) {
    }
}
