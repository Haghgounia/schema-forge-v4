package com.behsazan.schemaforge.domain.enums;
/**
 * Defines the supported referential action values used by SchemaForge.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public enum ReferentialAction { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT }
