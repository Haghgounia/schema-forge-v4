package com.behsazan.schemaforge.migration;

import java.util.Objects;

/** Flyway-compatible migration artifact plus its audited migration plan. */
public record MigrationArtifact(String fileName, String sql, TableMigrationPlan plan) {
    public MigrationArtifact {
        fileName = Objects.requireNonNull(fileName, "fileName must not be null");
        sql = Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
    }
}
