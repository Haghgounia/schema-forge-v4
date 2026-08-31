package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DBMS-specific SQL rendering contract used by the DBMS-neutral DDL generator.
 * Any syntax that varies between database engines belongs here, not in DdlGenerator.
 */
public interface Dialect {
    /** Returns the optional DDL capabilities implemented by this dialect. */
    default Set<DialectFeature> supportedFeatures() {
        return Set.of();
    }

    default boolean supports(DialectFeature feature) {
        return supportedFeatures().contains(feature);
    }

    default void require(DialectFeature feature) {
        if (!supports(feature)) {
            throw new UnsupportedOperationException(
                    name() + " dialect does not support " + feature);
        }
    }

    String sqlType(Column column);

    /**
     * Table-aware datatype hook for target-specific storage constraints. Dialects that do not
     * need table context keep the existing single-column mapping unchanged.
     */
    default String sqlType(Table table, Column column) {
        Objects.requireNonNull(table, "table must not be null");
        return sqlType(column);
    }

    /**
     * Schema-aware datatype hook for target adaptations that depend on relationships outside
     * the table currently being rendered. The default preserves the existing table-aware
     * behavior. A caller may render a table subset while still supplying the full canonical
     * schema as mapping context.
     */
    default String sqlType(DatabaseSchema schemaContext, Table table, Column column) {
        Objects.requireNonNull(schemaContext, "schemaContext must not be null");
        return sqlType(table, column);
    }

    /**
     * Optional inline constraint/annotation emitted after the normal column attributes.
     * This is intended for semantics-preserving target adaptations such as retaining a
     * canonical maximum character length when a VARCHAR must use off-row text storage.
     */
    default String inlineColumnConstraintClause(Table table, Column column) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(column, "column must not be null");
        return "";
    }

    /**
     * Schema-aware variant of the inline adaptation hook. The default delegates to the
     * historical table-local contract so existing dialects remain unchanged.
     */
    default String inlineColumnConstraintClause(
            DatabaseSchema schemaContext, Table table, Column column) {
        Objects.requireNonNull(schemaContext, "schemaContext must not be null");
        return inlineColumnConstraintClause(table, column);
    }

    /**
     * Performs dialect-specific table invariants that cannot be validated from a single column.
     * The default accepts the canonical table unchanged.
     */
    default void validateTable(Table table) {
        Objects.requireNonNull(table, "table must not be null");
    }

    /**
     * Additional definitions that must be present inside CREATE TABLE for a target dialect.
     * These are target-only compatibility definitions and do not mutate the canonical model.
     */
    default List<String> supplementalCreateTableDefinitions(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        return List.of();
    }

    /**
     * Controls whether a canonical sequence is emitted for this dialect. A dialect with native
     * identity columns may suppress a parser-generated backing sequence when the identity
     * semantics are rendered directly by the target database. Genuine standalone sequences
     * remain visible and therefore fail normally when SEQUENCE is unsupported.
     */
    default boolean emitSequence(DatabaseSchema schema, Sequence sequence) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(sequence, "sequence must not be null");
        return true;
    }

    /** Whether table/column comments are emitted inline in CREATE TABLE. */
    default boolean commentsInline() {
        return false;
    }

    /** Inline column comment fragment positioned at the end of a column definition. */
    default String inlineColumnCommentClause(Column column) {
        return "";
    }

    /** Inline table comment fragment positioned in the CREATE TABLE option tail. */
    default String inlineTableCommentClause(String comment) {
        return "";
    }

    /**
     * Renders source/profile-backed physical syntax attached directly to a column definition.
     * The default is empty because most dialects keep physical storage at table/index scope.
     */
    default String columnPhysicalClause(Column column) {
        return "";
    }

    /** Returns the active exact-numeric mapping policy for metadata comparison. */
    default NumericMappingStrategy numericMappingStrategy() {
        return NumericMappingStrategy.SAFE;
    }

    String quote(Identifier identifier);

    /**
     * Renders a generated/supporting object identifier using the target DBMS length policy.
     * Business table/column identifiers continue to use {@link #quote(Identifier)} directly.
     */
    default String quoteObject(Identifier logicalIdentifier) {
        return quote(PhysicalObjectNamePolicy.physicalIdentifier(this, logicalIdentifier));
    }

    default String name() {
        return getClass().getSimpleName().replace("Dialect", "");
    }

    default String statementTerminator() {
        return ";";
    }

    /** Whether a generated/computed column keeps the canonical datatype in its definition. */
    default boolean generatedColumnIncludesDataType() {
        return true;
    }

    /** Whether a generated/computed column may carry an explicit NOT NULL clause. */
    default boolean generatedColumnIncludesNullability() {
        return true;
    }

    default String generatedColumnClause(Column column) {
        return " AS (" + column.generatedExpression() + ")";
    }

    default String identityClause(Column column) {
        return " GENERATED BY DEFAULT AS IDENTITY";
    }

    /** Whether logical identity columns are implemented with named sequences in this dialect. */
    default boolean identityUsesNamedSequence() {
        return false;
    }

    /**
     * Returns the named sequence used for an identity column. A table with one identity
     * column uses SEQ_&lt;TABLE&gt;; multiple identity columns receive column-qualified names.
     */
    default QualifiedName identitySequenceName(
            QualifiedName tableName, Column column, boolean multipleIdentityColumns) {
        String sequenceName = "SEQ_" + tableName.name().normalized();
        if (multipleIdentityColumns) {
            sequenceName += "_" + column.name().normalized();
        }
        return QualifiedName.of(
                tableName.schemaName().map(Identifier::value).orElse(null),
                sequenceName);
    }

    /** Renders the default clause that consumes the next value of an identity sequence. */
    default String identitySequenceClause(QualifiedName sequenceName) {
        return " DEFAULT " + expression(sequenceName + ".NEXTVAL");
    }

    default String defaultClause(Column column) {
        return " DEFAULT " + expression(column.defaultValue().expression());
    }

    /** Renders a DBMS-specific scalar expression used by defaults, checks and generated columns. */
    default String expression(String expression) {
        return expression;
    }

    default String sequenceCacheClause(Integer cacheSize) {
        return cacheSize == null || cacheSize == 0 ? " CACHE 1" : " CACHE " + cacheSize;
    }

    default String sequenceCycleClause(boolean cycle) {
        return cycle ? " CYCLE" : " NO CYCLE";
    }

    /** Renders cache/cycle options in the order required by the target database. */
    default String sequenceOptions(Integer cacheSize, boolean cycle) {
        return sequenceCacheClause(cacheSize) + sequenceCycleClause(cycle);
    }

    default String sequenceTail() {
        return "";
    }

    default String constraintValidationClause() {
        return "";
    }

    /** Renders the opening fragment for an ALTER TABLE ... ADD CONSTRAINT statement. */
    default String alterTableAddConstraintPrefix(String tableName) {
        return "ALTER TABLE " + tableName + " ADD CONSTRAINT ";
    }

    /**
     * Renders an optional statement issued after a constraint has been created.
     * SQL Server uses this hook to make CHECK and FOREIGN KEY constraints explicitly
     * enabled and trusted; most dialects need no follow-up statement.
     */
    default String postCreateConstraintStatement(String tableName, String constraintName) {
        return "";
    }

    /** Whether table/column comments must be emitted before foreign-key dependencies. */
    default boolean commentsBeforeForeignKeys() {
        return false;
    }

    /**
     * Returns the dialect/project default table tablespace for a table when the
     * canonical model does not provide an explicit TABLESPACE physical option.
     */
    default String defaultTableTablespace(QualifiedName tableName) {
        return null;
    }

    /**
     * Returns the dialect/project default index tablespace for PK, UK and
     * standalone indexes when no explicit physical option is present.
     */
    default String defaultIndexTablespace(QualifiedName tableName) {
        return null;
    }

    /**
     * Resolves the table placement value that is valid for this target dialect.
     * The default preserves the historical generic TABLESPACE contract and then
     * falls back to the dialect-derived schema default. Dialects whose source
     * placement is target-specific may override this hook to prevent cross-DBMS
     * physical-option leakage.
     */
    default String resolveTableTablespace(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        String explicit = table.physicalOptions().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("TABLESPACE"))
                .map(java.util.Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse(null);
        return explicit != null ? explicit : defaultTableTablespace(table.qualifiedName());
    }

    default String tableTablespaceClause(String tablespace) {
        return tablespace == null || tablespace.isBlank() ? "" : " TABLESPACE " + tablespace.trim();
    }

    default String indexTablespaceClause(String tablespace) {
        return tablespace == null || tablespace.isBlank() ? "" : " TABLESPACE " + tablespace.trim();
    }

    /**
     * Optional index-organization keyword positioned before INDEX in dialects
     * that distinguish clustered/nonclustered rowstore indexes.
     */
    default String indexOrganizationClause(Index index) {
        return "";
    }

    /**
     * Combines the already-active table placement clause with a new commented
     * physical candidate block. Dialects override this only when grammar order
     * requires placement to precede the physical block.
     */
    default String tableTailWithPhysical(String activePlacementClause, String physicalCommentBlock) {
        String physical = physicalCommentBlock == null ? "" : physicalCommentBlock;
        String placement = activePlacementClause == null ? "" : activePlacementClause;
        return physical + placement;
    }

    /**
     * Renders a primary-key definition while preserving active placement and
     * inserting a non-executable physical index block at the grammar-correct location.
     */
    default String primaryKeyConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            boolean deferrable, boolean initiallyDeferred) {
        return primaryKeyConstraint(
                constraintName, tableName, columns, qualifiedIndexName,
                indexTablespace, deferrable, initiallyDeferred);
    }

    /** Object-aware variant used when backing-index physical evidence is available. */
    default String primaryKeyConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            Index physicalIndex, boolean deferrable, boolean initiallyDeferred) {
        return primaryKeyConstraintWithPhysical(
                constraintName, tableName, columns, qualifiedIndexName, indexTablespace,
                physicalIndexComment, deferrable, initiallyDeferred);
    }

    /** Same contract as primaryKeyConstraintWithPhysical for UNIQUE constraints. */
    default String uniqueConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            boolean deferrable, boolean initiallyDeferred) {
        return uniqueConstraint(
                constraintName, tableName, columns, qualifiedIndexName,
                indexTablespace, deferrable, initiallyDeferred);
    }

    /** Object-aware variant used when backing-index physical evidence is available. */
    default String uniqueConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            Index physicalIndex, boolean deferrable, boolean initiallyDeferred) {
        return uniqueConstraintWithPhysical(
                constraintName, tableName, columns, qualifiedIndexName, indexTablespace,
                physicalIndexComment, deferrable, initiallyDeferred);
    }

    /**
     * Renders CREATE INDEX tail fragments with the physical comment positioned
     * according to the target DBMS grammar.
     */
    default String indexTailWithPhysical(
            String includeColumns, String physicalIndexComment,
            String indexTablespace, String predicate) {
        String physical = physicalIndexComment == null ? "" : physicalIndexComment;
        return indexIncludeClause(includeColumns)
                + physical
                + indexTablespaceClause(indexTablespace)
                + partialIndexClause(predicate);
    }

    /**
     * Object-aware index tail used by P5 so operational build directives remain
     * separate from persistent physical options.
     */
    default String indexTailWithPhysical(
            String includeColumns, String physicalIndexComment,
            String indexTablespace, String predicate, Index index) {
        return indexTailWithPhysical(includeColumns, physicalIndexComment, indexTablespace, predicate)
                + indexBuildTail(index);
    }

    /** Modifier placed immediately after the INDEX keyword (PostgreSQL CONCURRENTLY). */
    default String indexCreateModifier(Index index) {
        return "";
    }

    /** DBMS-specific build clause placed in the CREATE INDEX tail. */
    default String indexBuildTail(Index index) {
        return "";
    }

    /** Optional DBA-visible warning/review comment for operational build directives. */
    default String indexBuildReviewComment(Index index) {
        return "";
    }

    /** Renders an index name in the namespace required by the target database. */
    default String qualifyIndexName(QualifiedName tableName, String renderedIndexName) {
        return tableName.schemaName()
                .map(schema -> quote(schema) + "." + renderedIndexName)
                .orElse(renderedIndexName);
    }

    /**
     * Whether primary-key and unique constraints require explicit unique enforcing indexes.
     * Db2 for z/OS can leave a table definition incomplete when these indexes are absent.
     */
    default boolean requiresExplicitConstraintIndexes() {
        return false;
    }

    default String primaryKeyConstraint(String constraintName, String tableName, String columns,
                                        String qualifiedIndexName, String indexTablespace, boolean deferrable, boolean initiallyDeferred) {
        return "CONSTRAINT " + constraintName + " PRIMARY KEY (" + columns + ")"
                + deferrabilityClause(deferrable, initiallyDeferred);
    }

    default String uniqueConstraint(String constraintName, String tableName, String columns,
                                    String qualifiedIndexName, String indexTablespace, boolean deferrable, boolean initiallyDeferred) {
        return "ALTER TABLE " + tableName
                + " ADD CONSTRAINT " + constraintName
                + " UNIQUE(" + columns + ")"
                + deferrabilityClause(deferrable, initiallyDeferred)
                + statementTerminator();
    }

    default String deferrabilityClause(boolean deferrable, boolean initiallyDeferred) {
        if (!deferrable) return "";
        require(DialectFeature.DEFERRABLE_CONSTRAINT);
        return initiallyDeferred ? " DEFERRABLE INITIALLY DEFERRED" : " DEFERRABLE INITIALLY IMMEDIATE";
    }

    default String indexIncludeClause(String columns) {
        if (columns == null || columns.isBlank()) return "";
        require(DialectFeature.INDEX_INCLUDE);
        return " INCLUDE (" + columns + ")";
    }

    default String partialIndexClause(String predicate) {
        if (predicate == null || predicate.isBlank()) return "";
        require(DialectFeature.PARTIAL_INDEX);
        return " WHERE " + expression(predicate);
    }

    /**
     * Renders the optional tail of CREATE INDEX. Vendors differ in the required
     * ordering of INCLUDE, filter predicates and physical placement clauses.
     */
    default String indexTail(String includeColumns, String indexTablespace, String predicate) {
        return indexIncludeClause(includeColumns)
                + indexTablespaceClause(indexTablespace)
                + partialIndexClause(predicate);
    }

    /** Renders a referential action, including the leading blank and clause name. */
    default String referentialActionClause(String clause, ReferentialAction action) {
        if (action == null || action == ReferentialAction.NO_ACTION) {
            return "";
        }
        String renderedAction = switch (action) {
            case RESTRICT -> "RESTRICT";
            case CASCADE -> "CASCADE";
            case SET_NULL -> "SET NULL";
            case SET_DEFAULT -> "SET DEFAULT";
            case NO_ACTION -> "NO ACTION";
        };
        return " " + clause + " " + renderedAction;
    }

    default String tableCommentStatement(QualifiedName tableName, String comment) {
        return "COMMENT ON TABLE " + qualifiedName(tableName)
                + " IS '" + escapeLiteral(comment) + "'" + statementTerminator();
    }

    default String columnCommentStatement(QualifiedName tableName, Identifier columnName, String comment) {
        return "COMMENT ON COLUMN " + qualifiedName(tableName) + "." + quote(columnName)
                + " IS '" + escapeLiteral(comment) + "'" + statementTerminator();
    }

    private String qualifiedName(QualifiedName name) {
        return name.schemaName()
                .map(schema -> quote(schema) + "." + quote(name.name()))
                .orElseGet(() -> quote(name.name()));
    }

    private String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    default String scriptPreamble(String source, String schemaName) {
        String nl = System.lineSeparator();
        return "-- ==============================================================" + nl
                + "-- SchemaForge Offline " + name() + " DDL" + nl
                + (source == null ? "" : "-- Source File : " + source + nl)
                + "-- Schema      : " + schemaName + nl
                + "-- ==============================================================";
    }

    default String warningLine(String text) {
        return "-- " + text;
    }

    /**
     * Renders the schema bootstrap fragment that must appear before any generated
     * object in that schema. Dialects may return executable, idempotent DDL or a
     * non-executable provisioning template when schema creation is an
     * administrative/security operation rather than ordinary application DDL.
     */
    /**
     * Returns a non-executable DBA provisioning template for storage/infrastructure concepts
     * that are specific to this DBMS. Environment-specific paths, sizes, volumes and storage
     * names are deliberately represented by placeholders and are never guessed by SchemaForge.
     */
    default String infrastructureProvisioningTemplate(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        return "";
    }

    default String schemaBootstrapStatement(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        return "";
    }
}
