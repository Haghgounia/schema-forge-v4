package com.behsazan.schemaforge.migration;

/** Column-level changes covered by migration foundation M1. */
public enum ColumnChangeKind {
    ADD_COLUMN,
    DROP_COLUMN,
    ALTER_TYPE,
    ALTER_NULLABILITY,
    ALTER_DEFAULT,
    ALTER_IDENTITY,
    ALTER_GENERATED_EXPRESSION
}
