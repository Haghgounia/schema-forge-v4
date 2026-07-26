package com.behsazan.schemaforge.dialect;

/**
 * Optional DDL capabilities exposed by a database dialect.
 *
 * <p>The generator depends only on these semantic capabilities and never on a
 * concrete DBMS type. New database engines therefore extend the platform by
 * implementing {@link Dialect}, without adding DBMS-specific branches to the
 * generation layer.</p>
 */
public enum DialectFeature {
    SEQUENCE,
    IDENTITY_COLUMN,
    GENERATED_COLUMN,
    TABLE_COMMENT,
    COLUMN_COMMENT,
    GRANT,
    INDEX_INCLUDE,
    PARTIAL_INDEX,
    EXPRESSION_INDEX,
    DEFERRABLE_CONSTRAINT
}
