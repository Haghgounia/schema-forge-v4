package com.behsazan.schemaforge.artifact.manifest;

import java.util.List;

/** Canonical model identity represented by one source in the generated package. */
public record ArtifactManifestModel(
        String sourceName,
        String schema,
        List<String> tables) {
    public ArtifactManifestModel {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
