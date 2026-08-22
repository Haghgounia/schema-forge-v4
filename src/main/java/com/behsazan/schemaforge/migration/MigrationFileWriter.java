package com.behsazan.schemaforge.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Writes a generated versioned migration artifact without modifying an existing migration file. */
public final class MigrationFileWriter {
    public Path write(Path outputDirectory, MigrationArtifact artifact) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Objects.requireNonNull(artifact, "artifact must not be null");
        Files.createDirectories(outputDirectory);
        Path target = outputDirectory.resolve(artifact.fileName());
        if (Files.exists(target)) {
            throw new IOException("Flyway migration already exists and will not be overwritten: " + target);
        }
        Files.writeString(target, artifact.sql(), StandardCharsets.UTF_8);
        return target;
    }
}
