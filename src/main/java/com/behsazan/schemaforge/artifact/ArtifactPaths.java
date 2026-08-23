package com.behsazan.schemaforge.artifact;

import java.nio.file.Path;

/** Portable path helpers used only for Artifact Contract metadata. */
public final class ArtifactPaths {
    private ArtifactPaths() {
    }

    public static String relative(Path root, Path artifact) {
        return root.relativize(artifact).toString().replace('\\', '/');
    }
}
