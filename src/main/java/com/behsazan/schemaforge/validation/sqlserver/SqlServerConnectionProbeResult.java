package com.behsazan.schemaforge.validation.sqlserver;

/** Read-only SQL Server connectivity and catalog-access probe result. */
public record SqlServerConnectionProbeResult(
        boolean successful,
        String productName,
        String productVersion,
        String driverName,
        String driverVersion,
        String serverName,
        String databaseName,
        String defaultSchema,
        boolean catalogAccessible,
        String message) {
}
