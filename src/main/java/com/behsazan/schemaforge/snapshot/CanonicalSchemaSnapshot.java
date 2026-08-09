package com.behsazan.schemaforge.snapshot;

import java.util.List;
import java.util.Map;

/**
 * Versioned, database-neutral JSON persistence contract for the canonical schema model.
 *
 * <p>This DTO intentionally contains only JSON-friendly primitives, lists and maps. It is kept
 * separate from the domain model so constructor/API refactoring in domain classes does not make
 * thousands of cached Word snapshots unreadable. Dialect-specific decisions such as Oracle
 * TIMESTAMP(9), PostgreSQL TIMESTAMP(6), or SQL Server DATETIME2(7) must never be persisted here.</p>
 */
public record CanonicalSchemaSnapshot(
        String snapshotVersion,
        String modelVersion,
        String parserVersion,
        String generatedAtUtc,
        SourceSnapshot source,
        SchemaSnapshot schema) {

    /** Source-file identity used for cache validation and audit traceability. */
    public record SourceSnapshot(
            String relativePath,
            String fileName,
            String sha256,
            long size,
            String lastModifiedUtc,
            String parserId) {
    }

    /** JSON representation of {@code DatabaseSchema}. */
    public record SchemaSnapshot(
            String name,
            String description,
            Map<String, String> metadata,
            List<TableSnapshot> tables,
            List<SequenceSnapshot> sequences) {
    }

    /** JSON representation of a canonical table. */
    public record TableSnapshot(
            String schema,
            String name,
            String persianName,
            String description,
            List<ColumnSnapshot> columns,
            PrimaryKeySnapshot primaryKey,
            List<ForeignKeySnapshot> foreignKeys,
            List<UniqueKeySnapshot> uniqueKeys,
            List<CheckConstraintSnapshot> checkConstraints,
            List<IndexSnapshot> indexes,
            Map<String, String> physicalOptions) {
    }

    /** JSON representation of a canonical column. */
    public record ColumnSnapshot(
            String name,
            DataTypeSnapshot dataType,
            boolean nullable,
            String defaultExpression,
            String description,
            boolean identity,
            Integer ordinalPosition,
            String generatedExpression) {
    }

    /** JSON representation of a database-neutral datatype. */
    public record DataTypeSnapshot(
            String name,
            Integer length,
            String lengthSemantics,
            Integer precision,
            Integer scale) {
    }

    /** JSON representation of a primary key. */
    public record PrimaryKeySnapshot(
            String name,
            List<String> columns,
            boolean deferrable,
            boolean initiallyDeferred) {
    }

    /** JSON representation of a foreign key and its target identity. */
    public record ForeignKeySnapshot(
            String name,
            List<String> columns,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns,
            String onDelete,
            String onUpdate,
            boolean deferrable,
            boolean initiallyDeferred,
            boolean physicalReference,
            boolean schemaExplicit) {
    }

    /** JSON representation of a unique key. */
    public record UniqueKeySnapshot(
            String name,
            List<String> columns,
            boolean deferrable,
            boolean initiallyDeferred) {
    }

    /** JSON representation of a check constraint. */
    public record CheckConstraintSnapshot(String name, String expression) {
    }

    /** JSON representation of a database-neutral index. */
    public record IndexSnapshot(
            String name,
            List<IndexColumnSnapshot> columns,
            String type,
            String description,
            List<String> includeColumns,
            String predicate) {
    }

    /** JSON representation of one index key column or expression. */
    public record IndexColumnSnapshot(
            String column,
            String expression,
            String direction) {
    }

    /** JSON representation of a canonical sequence. */
    public record SequenceSnapshot(
            String schema,
            String name,
            long startWith,
            long incrementBy,
            Long minValue,
            Long maxValue,
            boolean cycle,
            Integer cacheSize,
            String description) {
    }
}
