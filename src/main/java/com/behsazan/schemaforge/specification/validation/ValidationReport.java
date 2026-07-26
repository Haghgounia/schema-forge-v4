package com.behsazan.schemaforge.specification.validation;

import java.util.List;

/**
 * Represents the immutable validation report produced by the SchemaForge workflow.
 *
 * @since 4.1
 */
public record ValidationReport(boolean valid, List<ValidationIssue> issues) {
    public ValidationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
