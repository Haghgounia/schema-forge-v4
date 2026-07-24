package com.behsazan.schemaforge.specification.validation;

import java.util.List;

public record ValidationReport(boolean valid, List<ValidationIssue> issues) {
    public ValidationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
