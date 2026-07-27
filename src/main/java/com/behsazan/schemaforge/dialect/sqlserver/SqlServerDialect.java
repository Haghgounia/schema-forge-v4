package com.behsazan.schemaforge.dialect.sqlserver;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Microsoft SQL Server-specific type, identifier, expression and DDL rendering rules. */
public final class SqlServerDialect implements Dialect {
    private static final Set<DialectFeature> FEATURES = Set.of(
            DialectFeature.SEQUENCE,
            DialectFeature.IDENTITY_COLUMN,
            DialectFeature.GENERATED_COLUMN,
            DialectFeature.TABLE_COMMENT,
            DialectFeature.COLUMN_COMMENT,
            DialectFeature.GRANT,
            DialectFeature.INDEX_INCLUDE,
            DialectFeature.PARTIAL_INDEX);

    private final NumericMappingStrategy numericMappingStrategy;
    private final SqlServerTypeMapper typeMapper;
    private final SqlServerIdentifierRenderer identifierRenderer;
    private final SqlServerExpressionMapper expressionMapper;

    public SqlServerDialect() {
        this(NumericMappingStrategy.SAFE);
    }

    public SqlServerDialect(NumericMappingStrategy strategy) {
        this.numericMappingStrategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.typeMapper = new SqlServerTypeMapper(strategy);
        this.identifierRenderer = new SqlServerIdentifierRenderer();
        this.expressionMapper = new SqlServerExpressionMapper();
    }

    @Override
    public Set<DialectFeature> supportedFeatures() {
        return FEATURES;
    }

    @Override
    public NumericMappingStrategy numericMappingStrategy() {
        return numericMappingStrategy;
    }

    @Override
    public String sqlType(Column column) {
        Objects.requireNonNull(column, "column must not be null");
        return typeMapper.map(column.dataType());
    }

    @Override
    public String quote(Identifier identifier) {
        return identifierRenderer.render(identifier);
    }

    @Override
    public String expression(String expression) {
        return expressionMapper.map(expression);
    }

    @Override
    public boolean generatedColumnIncludesDataType() {
        return false;
    }

    @Override
    public boolean generatedColumnIncludesNullability() {
        return false;
    }

    @Override
    public String generatedColumnClause(Column column) {
        return " AS (" + expression(column.generatedExpression()) + ")";
    }

    @Override
    public String identityClause(Column column) {
        return " IDENTITY(1,1)";
    }

    @Override
    public String defaultClause(Column column) {
        return " DEFAULT " + expression(column.defaultValue().expression());
    }

    @Override
    public String sequenceCacheClause(Integer cacheSize) {
        return cacheSize == null || cacheSize == 0 ? " NO CACHE" : " CACHE " + cacheSize;
    }

    @Override
    public String sequenceCycleClause(boolean cycle) {
        return cycle ? " CYCLE" : " NO CYCLE";
    }

    @Override
    public String sequenceOptions(Integer cacheSize, boolean cycle) {
        return sequenceCycleClause(cycle) + sequenceCacheClause(cacheSize);
    }

    @Override
    public String tableTablespaceClause(String tablespace) {
        return filegroupClause(tablespace);
    }

    @Override
    public String indexTablespaceClause(String tablespace) {
        return filegroupClause(tablespace);
    }

    @Override
    public String qualifyIndexName(QualifiedName tableName, String renderedIndexName) {
        // SQL Server index names are scoped by table and are not schema-qualified.
        return renderedIndexName;
    }

    @Override
    public String primaryKeyConstraint(String constraintName, String tableName, String columns,
                                       String qualifiedIndexName, String indexTablespace,
                                       boolean deferrable, boolean initiallyDeferred) {
        return "CONSTRAINT " + constraintName + " PRIMARY KEY (" + columns + ")"
                + filegroupClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred);
    }

    @Override
    public String uniqueConstraint(String constraintName, String tableName, String columns,
                                   String qualifiedIndexName, String indexTablespace,
                                   boolean deferrable, boolean initiallyDeferred) {
        return "ALTER TABLE " + tableName
                + " ADD CONSTRAINT " + constraintName + " UNIQUE(" + columns + ")"
                + filegroupClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred)
                + statementTerminator();
    }

    @Override
    public String indexTail(String includeColumns, String indexTablespace, String predicate) {
        return indexIncludeClause(includeColumns)
                + partialIndexClause(predicate)
                + indexTablespaceClause(indexTablespace);
    }

    @Override
    public String referentialActionClause(String clause, ReferentialAction action) {
        if (action == ReferentialAction.RESTRICT) {
            return " " + clause + " NO ACTION";
        }
        return Dialect.super.referentialActionClause(clause, action);
    }

    @Override
    public String tableCommentStatement(QualifiedName tableName, String comment) {
        Identifier schema = tableName.schemaName().orElseGet(() -> Identifier.of("dbo"));
        return "EXEC sys.sp_addextendedproperty "
                + "@name=N'MS_Description', @value=N'" + escapeLiteral(comment) + "', "
                + "@level0type=N'SCHEMA', @level0name=N'" + escapeLiteral(metadataName(schema)) + "', "
                + "@level1type=N'TABLE', @level1name=N'" + escapeLiteral(metadataName(tableName.name())) + "'"
                + statementTerminator();
    }

    @Override
    public String columnCommentStatement(QualifiedName tableName, Identifier columnName, String comment) {
        Identifier schema = tableName.schemaName().orElseGet(() -> Identifier.of("dbo"));
        return "EXEC sys.sp_addextendedproperty "
                + "@name=N'MS_Description', @value=N'" + escapeLiteral(comment) + "', "
                + "@level0type=N'SCHEMA', @level0name=N'" + escapeLiteral(metadataName(schema)) + "', "
                + "@level1type=N'TABLE', @level1name=N'" + escapeLiteral(metadataName(tableName.name())) + "', "
                + "@level2type=N'COLUMN', @level2name=N'" + escapeLiteral(metadataName(columnName)) + "'"
                + statementTerminator();
    }

    @Override
    public String scriptPreamble(String source, String schemaName) {
        String nl = System.lineSeparator();
        return "-- ==============================================================" + nl
                + "-- SchemaForge Offline Microsoft SQL Server DDL" + nl
                + (source == null ? "" : "-- Source File : " + source + nl)
                + "-- Schema      : " + schemaName + nl
                + "-- ==============================================================" + nl
                + "SET XACT_ABORT ON;" + nl
                + "SET NOCOUNT ON;";
    }

    private String filegroupClause(String filegroup) {
        if (filegroup == null || filegroup.isBlank()) return "";
        return " ON " + identifierRenderer.render(Identifier.of(filegroup.trim()));
    }

    private String metadataName(Identifier identifier) {
        return identifier.normalized().toUpperCase(Locale.ROOT);
    }

    private String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
