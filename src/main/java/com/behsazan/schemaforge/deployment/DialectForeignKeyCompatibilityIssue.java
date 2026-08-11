package com.behsazan.schemaforge.deployment;

/** One DBMS-specific FK compatibility problem detected before integrated SQL execution. */
public record DialectForeignKeyCompatibilityIssue(
        DialectForeignKeyCompatibilityCode code,
        String table,
        String foreignKey,
        String column,
        String referencedTable,
        String referencedColumn,
        String referencingSqlType,
        String referencedSqlType,
        String message) {
}
