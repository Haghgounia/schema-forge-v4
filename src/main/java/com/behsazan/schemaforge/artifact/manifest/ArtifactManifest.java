package com.behsazan.schemaforge.artifact.manifest;

import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactRequestStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** SchemaForge standard package manifest contract V1. */
public record ArtifactManifest(
        String manifestContract,
        String artifactContractVersion,
        Generation generation,
        Source source,
        List<ArtifactManifestModel> models,
        ArtifactManifestValidation validation,
        ArtifactRequestStatus requestStatus,
        ArtifactManifestOutcomes artifactOutcomes,
        List<ArtifactManifestArtifact> artifacts,
        Map<String, Object> extensions) {

    public static final String CONTRACT = "schemaforge-manifest/v1";

    public ArtifactManifest {
        Objects.requireNonNull(
                requestStatus,
                "requestStatus must not be null");

        Objects.requireNonNull(
                artifactOutcomes,
                "artifactOutcomes must not be null");

        models = models == null
                ? List.of()
                : List.copyOf(models);

        artifacts = artifacts == null
                ? List.of()
                : List.copyOf(artifacts);

        extensions = extensions == null
                ? Map.of()
                : Collections.unmodifiableMap(
                new TreeMap<>(extensions));
    }

    public record Generation(
            String id,
            String timestampToken,
            String generatedAt) {
    }

    public record Source(
            ArtifactOrigin origin,
            String name) {
    }

    public record EnterpriseArchitectExtension(
            List<String> dependencyOrder,
            List<String> cyclicTables) {

        public EnterpriseArchitectExtension {
            dependencyOrder = dependencyOrder == null
                    ? List.of()
                    : List.copyOf(dependencyOrder);

            cyclicTables = cyclicTables == null
                    ? List.of()
                    : List.copyOf(cyclicTables);
        }
    }
}