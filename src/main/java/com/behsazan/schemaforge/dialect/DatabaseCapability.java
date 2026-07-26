package com.behsazan.schemaforge.dialect;

public enum DatabaseCapability {
    SEQUENCE,
    IDENTITY,
    CHECK_CONSTRAINT,
    COMMENT_ON,
    SYNONYM,
    MATERIALIZED_VIEW,
    GENERATED_COLUMN,
    CASCADE_DELETE,
    DEFERRABLE_CONSTRAINT,
    TABLESPACE
}
