package com.behsazan.schemaforge.conformance;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.util.List;

/** Immutable result of a read-only conformance audit against live database metadata. */
public record SchemaConformanceReport(
        String reportContract,
        DatabasePlatform platform,
        SchemaConformanceScope scope,
        String schema,
        String table,
        List<String> ruleFamilies,
        List<SchemaConformanceRuleFamilySummary> ruleFamilySummaries,
        SchemaConformanceSummary summary,
        List<SchemaConformanceFinding> findings) {

    public static final String CONTRACT = "schemaforge-schema-conformance/v3";

    public SchemaConformanceReport {
        reportContract = reportContract == null || reportContract.isBlank() ? CONTRACT : reportContract;
        ruleFamilies = ruleFamilies == null ? List.of() : List.copyOf(ruleFamilies);
        ruleFamilySummaries = ruleFamilySummaries == null ? List.of() : List.copyOf(ruleFamilySummaries);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
