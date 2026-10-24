package com.behsazan.schemaforge.conformance;

import java.time.Instant;

/** The returned audit is tied to the selected JDBC connection for traceability. */
public record SchemaConformanceSelectedResult(
        String reportContract,
        SchemaConformanceSelectedTarget target,
        String auditedAt,
        SchemaConformanceReport report) {
    public static final String CONTRACT = "schemaforge-schema-conformance-selected/v1";

    public SchemaConformanceSelectedResult(SchemaConformanceSelectedTarget target,
                                           SchemaConformanceReport report) {
        this(CONTRACT, target, Instant.now().toString(), report);
    }
}
