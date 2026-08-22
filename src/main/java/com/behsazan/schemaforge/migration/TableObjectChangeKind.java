package com.behsazan.schemaforge.migration;

/** Structural change kind for table-owned constraints and indexes. */
public enum TableObjectChangeKind {
    ADD,
    DROP,
    REPLACE
}
