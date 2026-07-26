package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.validation.ValidationReport;

import java.util.Objects;

/** Canonical schema after normalization, standards enrichment and validation. */
public record PreparedSchema(DatabaseSchema schema, ValidationReport validationReport) {
    public PreparedSchema {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(validationReport, "validationReport must not be null");
    }
}
