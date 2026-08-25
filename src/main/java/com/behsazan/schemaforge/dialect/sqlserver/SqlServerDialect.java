package com.behsazan.schemaforge.dialect.sqlserver;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.dialect.ForeignKeyTypeCompatibilityPolicy;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Microsoft SQL Server-specific type, identifier, expression and DDL rendering rules. */
public final class SqlServerDialect implements Dialect, ForeignKeyTypeCompatibilityPolicy {
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
    public String foreignKeyComparableType(Column column) {
        return sqlType(column).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
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
    public String indexOrganizationClause(Index index) {
        if (index == null) return "";
        String canonical = switch (index.type()) {
            case CLUSTERED -> "CLUSTERED";
            case NONCLUSTERED -> "NONCLUSTERED";
            default -> "";
        };
        if (!canonical.isBlank()) return canonical + " ";
        String explicit = physicalOption(index, "SQLSERVER_INDEX_ORGANIZATION", "INDEX_ORGANIZATION");
        if ("CLUSTERED".equals(explicit) || "NONCLUSTERED".equals(explicit)) {
            return explicit + " ";
        }
        return "";
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
    public String alterTableAddConstraintPrefix(String tableName) {
        return "ALTER TABLE " + tableName + " WITH CHECK ADD CONSTRAINT ";
    }

    @Override
    public String postCreateConstraintStatement(String tableName, String constraintName) {
        return "ALTER TABLE " + tableName + " CHECK CONSTRAINT "
                + constraintName + statementTerminator();
    }

    @Override
    public boolean commentsBeforeForeignKeys() {
        return true;
    }

    @Override
    public String indexTail(String includeColumns, String indexTablespace, String predicate) {
        return indexIncludeClause(includeColumns)
                + partialIndexClause(predicate)
                + indexTablespaceClause(indexTablespace);
    }

    @Override
    public String tableTailWithPhysical(String activePlacementClause, String physicalCommentBlock) {
        String placement = activePlacementClause == null ? "" : activePlacementClause;
        String physical = physicalCommentBlock == null ? "" : physicalCommentBlock;
        return placement + physical;
    }

    @Override
    public String primaryKeyConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            boolean deferrable, boolean initiallyDeferred) {
        return "CONSTRAINT " + constraintName + " PRIMARY KEY (" + columns + ")"
                + physicalIndexComment
                + filegroupClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred);
    }

    @Override
    public String primaryKeyConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            Index physicalIndex, boolean deferrable, boolean initiallyDeferred) {
        return "CONSTRAINT " + constraintName + " PRIMARY KEY " + indexOrganizationClause(physicalIndex)
                + "(" + columns + ")" + physicalIndexComment + filegroupClause(indexTablespace)
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
                + filegroupClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred)
                + statementTerminator();
    }

    @Override
    public String uniqueConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            Index physicalIndex, boolean deferrable, boolean initiallyDeferred) {
        String organization = indexOrganizationClause(physicalIndex);
        String uniqueClause = organization.isBlank()
                ? "UNIQUE(" + columns + ")"
                : "UNIQUE " + organization + "(" + columns + ")";
        return "ALTER TABLE " + tableName + " ADD CONSTRAINT " + constraintName + " " + uniqueClause
                + physicalIndexComment + filegroupClause(indexTablespace)
                + deferrabilityClause(deferrable, initiallyDeferred) + statementTerminator();
    }

    @Override
    public String indexTailWithPhysical(
            String includeColumns, String physicalIndexComment,
            String indexTablespace, String predicate) {
        return indexIncludeClause(includeColumns)
                + partialIndexClause(predicate)
                + physicalIndexComment
                + indexTablespaceClause(indexTablespace);
    }

    @Override
    public String indexTailWithPhysical(
            String includeColumns, String physicalIndexComment,
            String indexTablespace, String predicate, Index index) {
        return indexIncludeClause(includeColumns)
                + partialIndexClause(predicate)
                + physicalIndexComment
                + indexBuildTail(index)
                + indexTablespaceClause(indexTablespace);
    }

    @Override
    public String indexBuildTail(Index index) {
        if (index == null || index.buildOptions().isEmpty()) return "";
        java.util.List<String> options = new java.util.ArrayList<>();
        String online = buildOption(index, "ONLINE", "SQLSERVER_ONLINE");
        String resumable = buildOption(index, "RESUMABLE", "SQLSERVER_RESUMABLE");
        String sortInTempdb = buildOption(index, "SORT_IN_TEMPDB", "SQLSERVER_SORT_IN_TEMPDB");
        String maxDuration = buildOption(index, "MAX_DURATION_MINUTES", "MAX_DURATION", "SQLSERVER_MAX_DURATION_MINUTES");
        String maxdop = buildOption(index, "MAXDOP", "SQLSERVER_MAXDOP");

        boolean onlineOn = isOn(online);
        boolean onlineValid = online.isBlank() || onlineOn || isOff(online);
        boolean resumableOn = isOn(resumable);
        boolean resumableValid = resumable.isBlank() || resumableOn || isOff(resumable);

        if (onlineValid && !online.isBlank()) options.add("ONLINE = " + (onlineOn ? "ON" : "OFF"));
        if (resumableValid && !resumable.isBlank() && (!resumableOn || onlineOn)) {
            options.add("RESUMABLE = " + (resumableOn ? "ON" : "OFF"));
        }
        if (!maxDuration.isBlank() && resumableOn && onlineOn && positiveInteger(maxDuration)) {
            options.add("MAX_DURATION = " + Integer.parseInt(maxDuration) + " MINUTES");
        }
        if (!maxdop.isBlank() && integerInRange(maxdop, 0, 64)) {
            options.add("MAXDOP = " + Integer.parseInt(maxdop));
        }
        if (!sortInTempdb.isBlank() && (isOn(sortInTempdb) || isOff(sortInTempdb))) {
            if (!(resumableOn && onlineOn && isOn(sortInTempdb))) {
                options.add("SORT_IN_TEMPDB = " + (isOn(sortInTempdb) ? "ON" : "OFF"));
            }
        }
        return options.isEmpty() ? "" : " WITH (" + String.join(", ", options) + ")";
    }

    @Override
    public String indexBuildReviewComment(Index index) {
        if (index == null || index.buildOptions().isEmpty()) return "";
        java.util.List<String> issues = new java.util.ArrayList<>();
        String online = buildOption(index, "ONLINE", "SQLSERVER_ONLINE");
        String resumable = buildOption(index, "RESUMABLE", "SQLSERVER_RESUMABLE");
        String sortInTempdb = buildOption(index, "SORT_IN_TEMPDB", "SQLSERVER_SORT_IN_TEMPDB");
        String maxDuration = buildOption(index, "MAX_DURATION_MINUTES", "MAX_DURATION", "SQLSERVER_MAX_DURATION_MINUTES");
        String maxdop = buildOption(index, "MAXDOP", "SQLSERVER_MAXDOP");

        if (!online.isBlank() && !isOn(online) && !isOff(online)) {
            issues.add("ONLINE=" + online + " invalid (expected ON/OFF)");
        }
        if (!resumable.isBlank() && !isOn(resumable) && !isOff(resumable)) {
            issues.add("RESUMABLE=" + resumable + " invalid (expected ON/OFF)");
        }
        if (isOn(resumable) && !isOn(online)) {
            issues.add("RESUMABLE=ON requires explicit ONLINE=ON; RESUMABLE/MAX_DURATION were not emitted");
        }
        if (!maxDuration.isBlank() && !(isOn(resumable) && isOn(online))) {
            issues.add("MAX_DURATION requires RESUMABLE=ON with ONLINE=ON; value was not emitted");
        } else if (!maxDuration.isBlank() && !positiveInteger(maxDuration)) {
            issues.add("MAX_DURATION_MINUTES=" + maxDuration + " invalid (expected positive integer)");
        }
        if (!maxdop.isBlank() && !integerInRange(maxdop, 0, 64)) {
            issues.add("MAXDOP=" + maxdop + " invalid (expected integer 0..64)");
        }
        if (!sortInTempdb.isBlank() && !isOn(sortInTempdb) && !isOff(sortInTempdb)) {
            issues.add("SORT_IN_TEMPDB=" + sortInTempdb + " invalid (expected ON/OFF)");
        }
        if (isOn(sortInTempdb) && isOn(resumable) && isOn(online)) {
            issues.add("SORT_IN_TEMPDB=ON is not supported for resumable index operations; SORT_IN_TEMPDB was not emitted");
        }
        if (issues.isEmpty()) {
            return "-- [INDEX BUILD REVIEW][SQLSERVER] Explicit build options are operational directives; verify edition/version and deployment-window requirements before execution.";
        }
        return issues.stream()
                .map(issue -> "-- [INDEX BUILD ISSUE][SQLSERVER] " + issue + ".")
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
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

    @Override
    public String infrastructureProvisioningTemplate(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        String nl = System.lineSeparator();
        return "-- [INFRASTRUCTURE TEMPLATE][SQLSERVER] SQL Server uses FILEGROUP/FILES, not tablespaces." + nl
                + "-- DBA review required; database/file paths and growth settings are environment specific." + nl
                + "-- ALTER DATABASE [<DATABASE>] ADD FILEGROUP [<FILEGROUP>];" + nl
                + "-- ALTER DATABASE [<DATABASE>] ADD FILE (" + nl
                + "--   NAME = N'<LOGICAL_FILE_NAME>', FILENAME = N'<DATA_FILE_PATH>'," + nl
                + "--   SIZE = <INITIAL_SIZE>, MAXSIZE = <MAX_SIZE>, FILEGROWTH = <FILE_GROWTH>" + nl
                + "-- ) TO FILEGROUP [<FILEGROUP>];";
    }

    @Override
    public String schemaBootstrapStatement(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        String metadataSchemaName = escapeLiteral(metadataName(schemaName));
        String renderedSchemaName = quote(schemaName).replace("'", "''");
        return "IF SCHEMA_ID(N'" + metadataSchemaName + "') IS NULL "
                + "EXEC(N'CREATE SCHEMA " + renderedSchemaName
                + " AUTHORIZATION [dbo]')" + statementTerminator();
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

    private boolean positiveInteger(String value) {
        try { return Integer.parseInt(value) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    private boolean integerInRange(String value, int minimum, int maximum) {
        try {
            int number = Integer.parseInt(value);
            return number >= minimum && number <= maximum;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String physicalOption(Index index, String... keys) {
        for (String key : keys) {
            for (var entry : index.physicalOptions().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                    String value = entry.getValue().trim().toUpperCase(Locale.ROOT);
                    if (!value.isBlank()) return value;
                }
            }
        }
        return "";
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
