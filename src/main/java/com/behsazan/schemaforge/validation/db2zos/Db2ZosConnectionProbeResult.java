package com.behsazan.schemaforge.validation.db2zos;

/** Read-only Db2 connectivity and catalog-access probe result. */
public record Db2ZosConnectionProbeResult(
        boolean successful,
        String productName,
        String productVersion,
        String driverName,
        String driverVersion,
        String currentServer,
        String currentSchema,
        String currentSqlId,
        boolean catalogAccessible,
        String message) {
}
