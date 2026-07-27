package com.behsazan.schemaforge.validation.sqlserver;

/** One deterministic SQL Server offline-validation finding. */
public record SqlServerOfflineValidationIssue(
        String severity,
        String code,
        int statementNumber,
        String message) {
}
