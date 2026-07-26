package com.behsazan.schemaforge.domain.enums;
/**
 * Defines the supported index type values used by SchemaForge.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public enum IndexType { NORMAL, UNIQUE, BITMAP, FUNCTION_BASED, CLUSTERED, NONCLUSTERED }
