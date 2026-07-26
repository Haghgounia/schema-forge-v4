package com.behsazan.schemaforge.domain.enums;
/**
 * Defines the supported object type values used by SchemaForge.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public enum ObjectType { SCHEMA, TABLE, COLUMN, PRIMARY_KEY, FOREIGN_KEY, UNIQUE_KEY, CHECK_CONSTRAINT, INDEX, SEQUENCE, VIEW, SYNONYM, TRIGGER, PROCEDURE, FUNCTION, PACKAGE }
