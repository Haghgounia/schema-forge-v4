package com.behsazan.schemaforge.validation.db2zos;

/** One deterministic finding produced without connecting to Db2 for z/OS. */
public record Db2ZosOfflineValidationIssue(
        String severity,
        String code,
        int statementNumber,
        String message) {
}
