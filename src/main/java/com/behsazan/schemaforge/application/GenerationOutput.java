package com.behsazan.schemaforge.application;

import java.nio.file.Path;

/** Files and validation status produced by one offline schema generation run. */
public record GenerationOutput(
        Path jsonFile,
        Path sqlFile,
        DatabasePlatform platform,
        boolean valid) {
}
