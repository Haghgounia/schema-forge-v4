package com.behsazan.schemaforge.deployment;

import java.util.Objects;

/** One database-neutral finding produced while validating integrated-schema foreign keys. */
public record ForeignKeyAnalysisIssue(
        ForeignKeyAnalysisSeverity severity,
        ForeignKeyAnalysisCode code,
        String table,
        String foreignKey,
        String referencedTable,
        String message) {

    public ForeignKeyAnalysisIssue {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(code, "code must not be null");
        table = table == null ? "" : table;
        foreignKey = foreignKey == null ? "" : foreignKey;
        referencedTable = referencedTable == null ? "" : referencedTable;
        message = message == null ? "" : message;
    }
}
