package com.behsazan.schemaforge.conformance;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.util.List;

/** Aggregate response used when one conformance REST request targets multiple database platforms. */
public record SchemaConformanceMultiPlatformReport(
        String reportContract,
        SchemaConformanceScope scope,
        String schema,
        String table,
        List<DatabasePlatform> platforms,
        List<SchemaConformanceReport> reports) {

    public static final String CONTRACT = "schemaforge-schema-conformance-multi-platform/v1";

    public SchemaConformanceMultiPlatformReport {
        reportContract = reportContract == null || reportContract.isBlank() ? CONTRACT : reportContract;
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
        reports = reports == null ? List.of() : List.copyOf(reports);
    }
}
