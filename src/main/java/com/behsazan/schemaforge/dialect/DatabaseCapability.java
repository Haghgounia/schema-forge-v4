package com.behsazan.schemaforge.dialect;

/**
 * Defines the supported database capability values used by SchemaForge.
 *
 * @since 4.1
 */
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
