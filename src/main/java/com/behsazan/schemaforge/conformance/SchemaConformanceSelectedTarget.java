package com.behsazan.schemaforge.conformance;

import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;

/** Immutable, non-secret identity of the exact browser-selected JDBC target. */
public record SchemaConformanceSelectedTarget(
        String platform,
        String jdbcTarget,
        String databaseName,
        String username,
        String databaseProduct,
        String databaseVersion,
        String verifiedSchema,
        String defaultSchema,
        String expiresAt) {

    public static SchemaConformanceSelectedTarget of(RuntimeMetadataConnectionRegistry.Selection selection) {
        return new SchemaConformanceSelectedTarget(
                selection.platform(), selection.jdbcTarget(), selection.databaseName(),
                selection.username(), selection.databaseProduct(), selection.databaseVersion(),
                selection.verifiedSchema(), selection.defaultSchema(), selection.expiresAt());
    }
}
