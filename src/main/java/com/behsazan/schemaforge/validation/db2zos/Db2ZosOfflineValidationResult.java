package com.behsazan.schemaforge.validation.db2zos;

import java.util.List;

/** Result of static Db2 for z/OS SQL preflight validation. */
public record Db2ZosOfflineValidationResult(
        boolean valid,
        int statementCount,
        List<Db2ZosOfflineValidationIssue> issues) {

    public Db2ZosOfflineValidationResult {
        issues = List.copyOf(issues);
    }
}
