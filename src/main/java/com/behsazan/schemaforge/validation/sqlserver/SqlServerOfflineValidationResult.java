package com.behsazan.schemaforge.validation.sqlserver;

import java.util.List;

/** Result of static validation of a generated SQL Server DDL script. */
public record SqlServerOfflineValidationResult(
        boolean valid,
        int statementCount,
        List<SqlServerOfflineValidationIssue> issues) {
    public SqlServerOfflineValidationResult {
        issues = List.copyOf(issues);
    }
}
