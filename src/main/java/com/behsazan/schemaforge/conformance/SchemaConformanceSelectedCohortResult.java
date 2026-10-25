package com.behsazan.schemaforge.conformance;

import java.time.Instant;
import java.util.List;

/** Cohort evidence: exact requested names and selected JDBC target accompany the report. */
public record SchemaConformanceSelectedCohortResult(
        String reportContract,
        SchemaConformanceSelectedTarget target,
        String auditedAt,
        List<String> requestedTables,
        SchemaConformanceReport report) {
    public static final String CONTRACT = "schemaforge-schema-conformance-selected-cohort/v1";

    public SchemaConformanceSelectedCohortResult(SchemaConformanceSelectedTarget target,
                                                  List<String> requestedTables,
                                                  SchemaConformanceReport report) {
        this(CONTRACT, target, Instant.now().toString(), List.copyOf(requestedTables), report);
    }
}
