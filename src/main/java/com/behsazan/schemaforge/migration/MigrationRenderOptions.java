package com.behsazan.schemaforge.migration;

/** Rendering controls for generated Flyway-compatible SQL. */
public record MigrationRenderOptions(boolean confirmDestructive) {
    public static MigrationRenderOptions safeDefaults() {
        return new MigrationRenderOptions(false);
    }
}
