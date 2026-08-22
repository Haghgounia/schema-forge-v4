package com.behsazan.schemaforge.migration;

/** Operational risk attached to a generated migration change. */
public enum MigrationRisk {
    SAFE,
    REVIEW,
    DESTRUCTIVE;

    public static MigrationRisk max(MigrationRisk first, MigrationRisk second) {
        if (first == null) return second == null ? SAFE : second;
        if (second == null) return first;
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
