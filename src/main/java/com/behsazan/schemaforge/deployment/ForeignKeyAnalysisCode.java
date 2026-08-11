package com.behsazan.schemaforge.deployment;

/** Stable codes emitted while validating foreign keys for integrated deployment. */
public enum ForeignKeyAnalysisCode {
    MISSING_REFERENCED_TABLE,
    MISSING_REFERENCED_COLUMN,
    REFERENCED_COLUMNS_NOT_UNIQUE,
    LOGICAL_FOREIGN_KEY_SKIPPED,
    SELF_REFERENCE,
    CYCLIC_DEPENDENCY
}
