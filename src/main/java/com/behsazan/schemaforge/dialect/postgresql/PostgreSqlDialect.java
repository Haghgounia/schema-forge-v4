package com.behsazan.schemaforge.dialect.postgresql;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** PostgreSQL-specific type, identifier and DDL rendering rules. */
public final class PostgreSqlDialect implements Dialect {
    private final NumericMappingStrategy numericMappingStrategy;
    private final PostgreSqlTypeMapper typeMapper;
    private static final PostgreSqlExpressionMapper EXPRESSION_MAPPER = new PostgreSqlExpressionMapper();
    private static final PostgreSqlIdentifierRenderer IDENTIFIER_RENDERER = new PostgreSqlIdentifierRenderer();
    private static final Set<String> COLUMN_STORAGE_MODES = Set.of("PLAIN", "EXTERNAL", "EXTENDED", "MAIN", "DEFAULT");
    private static final Set<String> COLUMN_COMPRESSION_METHODS = Set.of("PGLZ", "LZ4", "DEFAULT");
    private static final Set<String> NON_COMPRESSIBLE_COLUMN_TYPES = Set.of(
            "SMALLINT", "INTEGER", "BIGINT", "REAL", "DOUBLE PRECISION",
            "BOOLEAN", "DATE", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "UUID");

    public PostgreSqlDialect() {
        this(NumericMappingStrategy.SAFE);
    }

    public PostgreSqlDialect(NumericMappingStrategy strategy) {
        this.numericMappingStrategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.typeMapper = new PostgreSqlTypeMapper(strategy);
    }

    private static final Set<DialectFeature> FEATURES = Set.of(
            DialectFeature.SEQUENCE,
            DialectFeature.IDENTITY_COLUMN,
            DialectFeature.GENERATED_COLUMN,
            DialectFeature.TABLE_COMMENT,
            DialectFeature.COLUMN_COMMENT,
            DialectFeature.GRANT,
            DialectFeature.INDEX_INCLUDE,
            DialectFeature.PARTIAL_INDEX,
            DialectFeature.EXPRESSION_INDEX,
            DialectFeature.DEFERRABLE_CONSTRAINT);

    @Override
    public NumericMappingStrategy numericMappingStrategy() {
        return numericMappingStrategy;
    }

    @Override
    public Set<DialectFeature> supportedFeatures() {
        return FEATURES;
    }


    @Override
    public String sqlType(Column column) {
        Objects.requireNonNull(column, "column must not be null");
        return typeMapper.map(column.dataType());
    }

    @Override
    public String columnPhysicalClause(Column column) {
        Objects.requireNonNull(column, "column must not be null");
        StringBuilder sql = new StringBuilder();

        String storageRaw = findColumnPhysical(column, "POSTGRESQL_STORAGE", "COLUMN_STORAGE", "STORAGE");
        String storage = null;
        if (storageRaw != null) {
            String normalized = storageRaw.trim().toUpperCase(Locale.ROOT);
            if (COLUMN_STORAGE_MODES.contains(normalized)) {
                storage = normalized;
                sql.append(" STORAGE " ).append(normalized);
            } else {
                sql.append(sourcePhysicalIssue("STORAGE=" + storageRaw
                        + " must be one of PLAIN, EXTERNAL, EXTENDED, MAIN or DEFAULT; source value was not normalized."));
            }
        }

        String compressionRaw = findColumnPhysical(column, "POSTGRESQL_COMPRESSION", "COLUMN_COMPRESSION", "COMPRESSION");
        if (compressionRaw != null) {
            String normalized = compressionRaw.trim().toUpperCase(Locale.ROOT);
            if (!COLUMN_COMPRESSION_METHODS.contains(normalized)) {
                sql.append(sourcePhysicalIssue("COMPRESSION=" + compressionRaw
                        + " must be pglz, lz4 or default; source value was not normalized."));
            } else if ("PLAIN".equals(storage) || "EXTERNAL".equals(storage)) {
                sql.append(sourcePhysicalIssue("COMPRESSION=" + compressionRaw
                        + " was not emitted because PostgreSQL uses column compression only with MAIN or EXTENDED storage."));
            } else {
                String mappedType = typeMapper.map(column.dataType()).toUpperCase(Locale.ROOT);
                String baseType = mappedType.replaceFirst("\\(.*$", "");
                if (NON_COMPRESSIBLE_COLUMN_TYPES.contains(baseType)) {
                    sql.append(sourcePhysicalIssue("COMPRESSION=" + compressionRaw
                            + " was not emitted because " + mappedType + " is not a variable-width PostgreSQL type."));
                } else if (isKnownCompressibleType(baseType)) {
                    sql.append(" COMPRESSION " ).append(normalized.toLowerCase(Locale.ROOT));
                } else {
                    sql.append(sourcePhysicalReview("COMPRESSION=" + compressionRaw
                            + " was not emitted because compression support for canonical type " + mappedType
                            + " cannot be proven without database/type metadata."));
                }
            }
        }
        return sql.toString();
    }

    private boolean isKnownCompressibleType(String baseType) {
        return baseType.equals("VARCHAR") || baseType.equals("CHAR") || baseType.equals("TEXT")
                || baseType.equals("BYTEA") || baseType.equals("XML") || baseType.equals("JSON")
                || baseType.equals("JSONB") || baseType.equals("NUMERIC") || baseType.equals("DECIMAL");
    }

    private String findColumnPhysical(Column column, String... keys) {
        for (String key : keys) {
            for (var entry : column.physicalOptions().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && entry.getKey().trim().equalsIgnoreCase(key)) {
                    String value = entry.getValue().trim();
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return null;
    }

    private String sourcePhysicalIssue(String message) {
        return " /* [SOURCE PHYSICAL ISSUE][POSTGRESQL] " + safeInlineComment(message) + " */";
    }

    private String sourcePhysicalReview(String message) {
        return " /* [SOURCE PHYSICAL REVIEW][POSTGRESQL] " + safeInlineComment(message) + " */";
    }

    private String safeInlineComment(String value) {
        return value == null ? "" : value.replace("*/", "* /").replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public String quote(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        return IDENTIFIER_RENDERER.render(identifier);
    }


    @Override
    public String expression(String expression) {
        return EXPRESSION_MAPPER.map(expression);
    }

    @Override
    public String defaultClause(Column column) {
        return " DEFAULT " + expression(column.defaultValue().expression());
    }

    @Override
    public String generatedColumnClause(Column column) {
        return " GENERATED ALWAYS AS (" + expression(column.generatedExpression()) + ") STORED";
    }

    @Override
    public String identityClause(Column column) {
        return " GENERATED BY DEFAULT AS IDENTITY";
    }

    @Override
    public String sequenceCacheClause(Integer cacheSize) {
        return " CACHE " + (cacheSize == null || cacheSize == 0 ? 1 : cacheSize);
    }

    @Override
    public String sequenceCycleClause(boolean cycle) {
        return cycle ? " CYCLE" : " NO CYCLE";
    }

    @Override
    public String sequenceTail() {
        return "";
    }

    @Override
    public String constraintValidationClause() {
        return "";
    }


    @Override
    public String tableTablespaceClause(String tablespace) {
        if (tablespace == null || tablespace.isBlank()) return "";
        return " TABLESPACE " + IDENTIFIER_RENDERER.render(Identifier.of(tablespace.trim()));
    }

    @Override
    public String indexTablespaceClause(String tablespace) {
        if (tablespace == null || tablespace.isBlank()) return "";
        return " TABLESPACE " + IDENTIFIER_RENDERER.render(Identifier.of(tablespace.trim()));
    }

    @Override
    public String qualifyIndexName(QualifiedName tableName, String renderedIndexName) {
        // PostgreSQL creates an index in the same schema as its table and does not allow
        // a schema-qualified index name in CREATE INDEX.
        return renderedIndexName;
    }

    @Override
    public String primaryKeyConstraint(String constraintName, String tableName, String columns,
                                       String qualifiedIndexName, String indexTablespace, boolean deferrable, boolean initiallyDeferred) {
        return "CONSTRAINT " + constraintName + " PRIMARY KEY (" + columns + ")"
                + constraintIndexTablespaceClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred);
    }

    @Override
    public String uniqueConstraint(String constraintName, String tableName, String columns,
                                   String qualifiedIndexName, String indexTablespace, boolean deferrable, boolean initiallyDeferred) {
        return "ALTER TABLE " + tableName
                + " ADD CONSTRAINT " + constraintName + " UNIQUE(" + columns + ")"
                + constraintIndexTablespaceClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred)
                + statementTerminator();
    }


    @Override
    public String indexCreateModifier(Index index) {
        String value = buildOption(index, "CONCURRENTLY", "POSTGRESQL_CONCURRENTLY");
        return isOn(value) ? " CONCURRENTLY" : "";
    }

    @Override
    public String indexBuildReviewComment(Index index) {
        String value = buildOption(index, "CONCURRENTLY", "POSTGRESQL_CONCURRENTLY");
        if (value.isBlank()) return "";
        if (isOn(value)) {
            return "-- [INDEX BUILD REVIEW][POSTGRESQL] CONCURRENTLY is explicit; CREATE INDEX CONCURRENTLY cannot run inside a transaction block and concurrent builds on partitioned tables require per-partition handling.";
        }
        if (isOff(value)) return "";
        return "-- [INDEX BUILD ISSUE][POSTGRESQL] CONCURRENTLY=" + value
                + " is invalid; expected ON/OFF. The build directive was not emitted.";
    }

    private String buildOption(Index index, String... keys) {
        if (index == null || index.buildOptions().isEmpty()) return "";
        for (String key : keys) {
            for (var entry : index.buildOptions().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                    String value = entry.getValue().trim().toUpperCase(Locale.ROOT);
                    if (!value.isBlank()) return value;
                }
            }
        }
        return "";
    }

    private boolean isOn(String value) {
        return Set.of("ON", "TRUE", "YES", "1").contains(value);
    }

    private boolean isOff(String value) {
        return Set.of("OFF", "FALSE", "NO", "0").contains(value);
    }

    private String constraintIndexTablespaceClause(String tablespace) {
        if (tablespace == null || tablespace.isBlank()) {
            return "";
        }
        return " USING INDEX TABLESPACE "
                + IDENTIFIER_RENDERER.render(Identifier.of(tablespace.trim()));
    }

    @Override
    public String primaryKeyConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            boolean deferrable, boolean initiallyDeferred) {
        return "CONSTRAINT " + constraintName + " PRIMARY KEY (" + columns + ")"
                + physicalIndexComment
                + constraintIndexTablespaceClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred);
    }

    @Override
    public String uniqueConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            boolean deferrable, boolean initiallyDeferred) {
        return "ALTER TABLE " + tableName
                + " ADD CONSTRAINT " + constraintName + " UNIQUE(" + columns + ")"
                + physicalIndexComment
                + constraintIndexTablespaceClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred)
                + statementTerminator();
    }

    @Override
    public String indexTailWithPhysical(
            String includeColumns, String physicalIndexComment,
            String indexTablespace, String predicate) {
        return indexIncludeClause(includeColumns)
                + physicalIndexComment
                + indexTablespaceClause(indexTablespace)
                + partialIndexClause(predicate);
    }

    @Override
    public String scriptPreamble(String source, String schemaName) {
        String nl = System.lineSeparator();
        return "-- ==============================================================" + nl
                + "-- SchemaForge Offline PostgreSQL DDL" + nl
                + (source == null ? "" : "-- Source File : " + source + nl)
                + "-- Schema      : " + schemaName.toLowerCase(Locale.ROOT) + nl
                + "-- ==============================================================" + nl
                + "\\encoding UTF8" + nl
                + "\\set ON_ERROR_STOP on";
    }

    @Override
    public String warningLine(String text) {
        return "-- " + text;
    }

    @Override
    public String infrastructureProvisioningTemplate(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        String nl = System.lineSeparator();
        return "-- [INFRASTRUCTURE TEMPLATE][POSTGRESQL] Optional cluster-level tablespace; DBA review required." + nl
                + "-- The LOCATION directory must already exist and be owned by the PostgreSQL OS account." + nl
                + "-- CREATE TABLESPACE <TABLESPACE> OWNER CURRENT_USER LOCATION '<ABSOLUTE_DIRECTORY>';" + nl
                + "-- Normal SchemaForge operation does not require a dedicated PostgreSQL tablespace.";
    }

    @Override
    public String schemaBootstrapStatement(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        return "CREATE SCHEMA IF NOT EXISTS " + quote(schemaName)
                + " AUTHORIZATION CURRENT_USER" + statementTerminator();
    }
}
