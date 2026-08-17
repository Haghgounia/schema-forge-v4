package com.behsazan.schemaforge.validation.datatype;

import com.behsazan.schemaforge.specification.validation.ValidationIssue;

import java.util.List;

/**
 * Cross-dialect datatype compatibility findings for one canonical schema.
 *
 * <p>Warnings describe deliberate but potentially lossy target mappings. Errors
 * describe mappings that must not be invented because the canonical source does
 * not contain enough information or exceeds a hard target-database limit.</p>
 */
public record DatatypeCompatibilityAssessment(List<ValidationIssue> issues) {
    public DatatypeCompatibilityAssessment {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean blocking() {
        return issues.stream().anyMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity()));
    }
}
