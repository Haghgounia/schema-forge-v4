package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.nio.file.Path;
import java.util.Objects;

/** Validation result for one generated database script. */
public record DdlValidationResult(
        Path sourceDocument,
        Path sqlFile,
        DatabasePlatform platform,
        DdlValidationStatus status,
        int executedStatements,
        String message) {

    public DdlValidationResult {
        Objects.requireNonNull(sourceDocument, "sourceDocument must not be null");
        Objects.requireNonNull(sqlFile, "sqlFile must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(status, "status must not be null");
        message = message == null ? "" : message;
    }
}
