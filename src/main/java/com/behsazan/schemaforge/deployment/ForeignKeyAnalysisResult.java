package com.behsazan.schemaforge.deployment;

import java.util.List;
import java.util.Objects;

/** Aggregate result of database-neutral FK validation for one integrated canonical schema. */
public record ForeignKeyAnalysisResult(
        int tables,
        int foreignKeys,
        int physicalForeignKeys,
        int logicalForeignKeys,
        int resolvedPhysicalForeignKeys,
        int selfReferences,
        int cycleGroups,
        List<ForeignKeyAnalysisIssue> issues) {

    public ForeignKeyAnalysisResult {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
    }

    /** Returns true when no blocker prevents integrated FK deployment. */
    public boolean deployable() {
        return issues.stream().noneMatch(issue -> issue.severity() == ForeignKeyAnalysisSeverity.ERROR);
    }

    /** Number of blocker findings that must be fixed before integrated deployment. */
    public long errorCount() {
        return issues.stream().filter(issue -> issue.severity() == ForeignKeyAnalysisSeverity.ERROR).count();
    }

    /** Number of non-blocking warnings. */
    public long warningCount() {
        return issues.stream().filter(issue -> issue.severity() == ForeignKeyAnalysisSeverity.WARNING).count();
    }
}
