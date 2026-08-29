package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.PhysicalObjectNamePolicy;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders a table migration plan as a Flyway-compatible versioned SQL body. */
public final class MigrationSqlRenderer {
    private static final String NL = System.lineSeparator();

    private final NumericMappingStrategy numericMappingStrategy;

    public MigrationSqlRenderer() {
        this(DialectFactory.configuredNumericMappingStrategy());
    }

    public MigrationSqlRenderer(NumericMappingStrategy numericMappingStrategy) {
        this.numericMappingStrategy = Objects.requireNonNull(
                numericMappingStrategy, "numericMappingStrategy must not be null");
    }

    public String render(TableMigrationPlan plan, MigrationRenderOptions options) {
        Objects.requireNonNull(plan, "plan must not be null");
        options = options == null ? MigrationRenderOptions.safeDefaults() : options;
        Dialect dialect = DialectFactory.create(plan.platform(), numericMappingStrategy);
        DdlGenerator ddlGenerator = new DdlGenerator(dialect);

        StringBuilder sql = new StringBuilder();
        appendHeader(sql, plan, options);
        if (plan.empty()) {
            sql.append("-- No table changes detected. This migration is intentionally empty.").append(NL);
            return sql.toString();
        }

        List<TableObjectChange> dependencyRefreshes = sqlServerDependencyRefreshes(plan);
        Set<String> dependencyGuardedColumns = sqlServerDependencyGuardedColumns(plan, dependencyRefreshes);
        renderDependencyRefreshDropPhase(sql, plan, dialect, options, dependencyRefreshes);
        renderObjectRenamePhase(sql, plan, dialect);
        renderObjectDropPhase(sql, plan, dialect, options);

        Set<String> renderedComposite = new HashSet<>();
        for (ColumnChange change : plan.columnChanges()) {
            sql.append(NL)
                    .append("-- [").append(change.risk()).append("] ")
                    .append(change.kind()).append(" ")
                    .append(change.columnName().value()).append(": ")
                    .append(change.rationale()).append(NL);

            if (manualOnly(plan.platform(), change)) {
                sql.append("-- HINT: SchemaForge does not auto-execute this identity/generated-expression transition.")
                        .append(NL);
                continue;
            }

            String compositeKey = compositeKey(plan.platform(), change);
            if (compositeKey != null && !renderedComposite.add(compositeKey)) {
                sql.append("-- HINT: change is covered by the combined column-definition statement above.").append(NL);
                continue;
            }

            List<String> statements;
            try {
                statements = renderChange(plan, dialect, change);
            } catch (UnsupportedOperationException unsupported) {
                sql.append("-- BLOCKED: this REVIEW change cannot be rendered automatically for ")
                        .append(plan.platform()).append(".").append(NL)
                        .append("-- HINT: ").append(safeComment(unsupported.getMessage())).append(NL)
                        .append("-- HINT: CREATE output remains independent; resolve this ALTER manually or provide explicit migration evidence.")
                        .append(NL);
                continue;
            }
            boolean dependencyBlocked = !options.confirmDestructive()
                    && plan.platform() == DatabasePlatform.SQLSERVER
                    && dependencyGuardedColumns.contains(change.columnName().normalized())
                    && (change.kind() == ColumnChangeKind.ALTER_TYPE
                    || change.kind() == ColumnChangeKind.ALTER_NULLABILITY);
            boolean blocked = change.risk() == MigrationRisk.DESTRUCTIVE && !options.confirmDestructive();
            blocked = blocked || dependencyBlocked;
            if (dependencyBlocked) {
                sql.append("-- BLOCKED: SQL Server ALTER COLUMN is commented because a required dependent-object DROP is blocked until confirmDestructive=true.")
                        .append(NL);
            } else if (blocked) {
                sql.append("-- BLOCKED: destructive SQL is commented out. Re-render with confirmDestructive=true after DBA approval.")
                        .append(NL);
            }
            for (String statement : statements) {
                if (statement == null || statement.isBlank()) continue;
                appendStatement(sql, statement, blocked);
            }
        }

        renderObjectAddPhase(sql, plan, dialect, ddlGenerator, options);
        renderDependencyRefreshAddPhase(sql, plan, ddlGenerator, options, dependencyRefreshes);
        return sql.toString();
    }

    private void renderObjectRenamePhase(
            StringBuilder sql, TableMigrationPlan plan, Dialect dialect) {
        for (TableObjectChange change : plan.objectChanges()) {
            if (change.kind() != TableObjectChangeKind.RENAME) continue;
            appendObjectHeading(sql, change, "RENAME PHASE");
            for (String statement : renderObjectRename(plan, dialect, change)) {
                if (statement != null && !statement.isBlank()) appendStatement(sql, statement, false);
            }
        }
    }

    private void renderObjectDropPhase(
            StringBuilder sql, TableMigrationPlan plan, Dialect dialect, MigrationRenderOptions options) {
        for (TableObjectChange change : orderedObjectChangesForDrop(plan.objectChanges())) {
            if (change.kind() == TableObjectChangeKind.ADD || change.kind() == TableObjectChangeKind.RENAME) continue;
            appendObjectHeading(sql, change, "DROP PHASE");
            boolean blocked = change.risk() == MigrationRisk.DESTRUCTIVE && !options.confirmDestructive();
            if (blocked) {
                sql.append("-- BLOCKED: destructive structural SQL is commented out. Re-render with confirmDestructive=true after DBA approval.")
                        .append(NL);
            }
            try {
                for (String statement : renderObjectDrop(plan, dialect, change)) {
                    if (statement != null && !statement.isBlank()) appendStatement(sql, statement, blocked);
                }
            } catch (UnsupportedOperationException unsupported) {
                sql.append("-- BLOCKED: structural DROP requires manual DBA handling: ")
                        .append(safeComment(unsupported.getMessage())).append(NL);
            }
        }
    }

    private void renderObjectAddPhase(
            StringBuilder sql, TableMigrationPlan plan, Dialect dialect, DdlGenerator ddlGenerator,
            MigrationRenderOptions options) {
        for (TableObjectChange change : orderedObjectChangesForAdd(plan.objectChanges())) {
            if (change.kind() == TableObjectChangeKind.DROP || change.kind() == TableObjectChangeKind.RENAME) continue;
            appendObjectHeading(sql, change, "ADD PHASE");
            boolean blocked = change.risk() == MigrationRisk.DESTRUCTIVE && !options.confirmDestructive();
            if (blocked) {
                sql.append("-- BLOCKED: replacement ADD stays commented while its destructive DROP is blocked.")
                        .append(NL);
            }
            try {
                for (String statement : renderObjectAdd(plan, ddlGenerator, change)) {
                    if (statement != null && !statement.isBlank()) appendStatement(sql, statement, blocked);
                }
            } catch (UnsupportedOperationException unsupported) {
                sql.append("-- BLOCKED: structural ADD requires manual DBA handling: ")
                        .append(safeComment(unsupported.getMessage())).append(NL);
            }
        }
    }


    private void renderDependencyRefreshDropPhase(
            StringBuilder sql, TableMigrationPlan plan, Dialect dialect, MigrationRenderOptions options,
            List<TableObjectChange> refreshes) {
        for (TableObjectChange change : orderedObjectChangesForDrop(refreshes)) {
            appendObjectHeading(sql, change, "SQLSERVER DEPENDENCY DROP");
            boolean blocked = !options.confirmDestructive();
            if (blocked) {
                sql.append("-- BLOCKED: SQL Server requires this temporary dependency DROP before ALTER COLUMN; re-render with confirmDestructive=true after DBA approval.")
                        .append(NL);
            }
            for (String statement : renderObjectDrop(plan, dialect, change)) {
                if (statement != null && !statement.isBlank()) appendStatement(sql, statement, blocked);
            }
        }
    }

    private void renderDependencyRefreshAddPhase(
            StringBuilder sql, TableMigrationPlan plan, DdlGenerator ddlGenerator, MigrationRenderOptions options,
            List<TableObjectChange> refreshes) {
        for (TableObjectChange change : orderedObjectChangesForAdd(refreshes)) {
            appendObjectHeading(sql, change, "SQLSERVER DEPENDENCY RECREATE");
            boolean blocked = !options.confirmDestructive();
            if (blocked) {
                sql.append("-- BLOCKED: dependency recreation stays commented while its temporary DROP is blocked.")
                        .append(NL);
            }
            for (String statement : renderObjectAdd(plan, ddlGenerator, change)) {
                if (statement != null && !statement.isBlank()) appendStatement(sql, statement, blocked);
            }
        }
    }

    private List<TableObjectChange> sqlServerDependencyRefreshes(TableMigrationPlan plan) {
        if (plan.platform() != DatabasePlatform.SQLSERVER) return List.of();

        Set<String> alteredColumns = new HashSet<>();
        for (ColumnChange change : plan.columnChanges()) {
            if (change.kind() == ColumnChangeKind.ALTER_TYPE
                    || change.kind() == ColumnChangeKind.ALTER_NULLABILITY) {
                alteredColumns.add(change.columnName().normalized());
            }
        }
        if (alteredColumns.isEmpty()) return List.of();

        List<TableObjectChange> refreshes = new ArrayList<>();
        Table live = plan.liveTable();
        Table desired = plan.desiredTable();

        live.primaryKey().ifPresent(before -> {
            PrimaryKey after = desired.primaryKey().orElse(null);
            Set<String> dependencyColumns = referencedIdentifierColumns(before.columns(), alteredColumns);
            if (after != null
                    && !dependencyColumns.isEmpty()
                    && !alreadyChanged(plan, TableObjectType.PRIMARY_KEY, before)) {
                refreshes.add(dependencyRefresh(TableObjectType.PRIMARY_KEY, chooseName(after.name(), before.name()),
                        before, after, dependencyColumns));
            }
        });

        for (UniqueKey before : live.uniqueKeys()) {
            UniqueKey after = desired.uniqueKeys().stream()
                    .filter(candidate -> sameName(candidate.name(), before.name()))
                    .findFirst().orElse(null);
            Set<String> dependencyColumns = referencedIdentifierColumns(before.columns(), alteredColumns);
            if (after != null
                    && !dependencyColumns.isEmpty()
                    && !alreadyChanged(plan, TableObjectType.UNIQUE_KEY, before)) {
                refreshes.add(dependencyRefresh(TableObjectType.UNIQUE_KEY, chooseName(after.name(), before.name()),
                        before, after, dependencyColumns));
            }
        }

        for (ForeignKey before : live.foreignKeys()) {
            ForeignKey after = desired.foreignKeys().stream()
                    .filter(candidate -> sameName(candidate.name(), before.name()))
                    .findFirst().orElse(null);
            Set<String> dependencyColumns = new HashSet<>(
                    referencedIdentifierColumns(before.columns(), alteredColumns));
            if (sameQualifiedTable(before.referencedTable(), live.qualifiedName())) {
                dependencyColumns.addAll(referencedIdentifierColumns(before.referencedColumns(), alteredColumns));
            }
            if (after != null
                    && !dependencyColumns.isEmpty()
                    && !alreadyChanged(plan, TableObjectType.FOREIGN_KEY, before)) {
                refreshes.add(dependencyRefresh(TableObjectType.FOREIGN_KEY, chooseName(after.name(), before.name()),
                        before, after, dependencyColumns));
            }
        }

        for (CheckConstraint before : live.checkConstraints()) {
            CheckConstraint after = desired.checkConstraints().stream()
                    .filter(candidate -> sameName(candidate.name(), before.name()))
                    .findFirst().orElse(null);
            Set<String> dependencyColumns = referencedExpressionColumns(before.expression(), alteredColumns);
            if (after != null
                    && !dependencyColumns.isEmpty()
                    && !alreadyChanged(plan, TableObjectType.CHECK_CONSTRAINT, before)) {
                refreshes.add(dependencyRefresh(TableObjectType.CHECK_CONSTRAINT,
                        chooseName(after.name(), before.name()), before, after, dependencyColumns));
            }
        }

        for (Index before : live.indexes()) {
            Index after = desired.indexes().stream()
                    .filter(candidate -> sameName(candidate.name(), before.name()))
                    .findFirst().orElse(null);
            Set<String> dependencyColumns = referencedIndexColumns(before, alteredColumns);
            if (after != null
                    && !dependencyColumns.isEmpty()
                    && !alreadyChanged(plan, TableObjectType.INDEX, before)) {
                refreshes.add(dependencyRefresh(TableObjectType.INDEX, chooseName(after.name(), before.name()),
                        before, after, dependencyColumns));
            }
        }

        return List.copyOf(refreshes);
    }

    private static Set<String> sqlServerDependencyGuardedColumns(
            TableMigrationPlan plan, List<TableObjectChange> refreshes) {
        if (plan.platform() != DatabasePlatform.SQLSERVER) return Set.of();
        List<TableObjectChange> dependencies = new ArrayList<>(refreshes);
        plan.objectChanges().stream()
                .filter(change -> change.kind() != TableObjectChangeKind.ADD && change.before() != null)
                .forEach(dependencies::add);

        Set<String> guarded = new HashSet<>();
        for (ColumnChange columnChange : plan.columnChanges()) {
            if (columnChange.kind() != ColumnChangeKind.ALTER_TYPE
                    && columnChange.kind() != ColumnChangeKind.ALTER_NULLABILITY) {
                continue;
            }
            String column = columnChange.columnName().normalized();
            if (dependencies.stream().anyMatch(change -> dependencyReferencesColumn(
                    change, column, plan.liveTable()))) {
                guarded.add(column);
            }
        }
        return Set.copyOf(guarded);
    }

    private static boolean dependencyReferencesColumn(
            TableObjectChange change, String column, Table liveTable) {
        if (change.before() == null) return false;
        Set<String> one = Set.of(column);
        return switch (change.objectType()) {
            case PRIMARY_KEY -> referencesAny(((PrimaryKey) change.before()).columns(), one);
            case UNIQUE_KEY -> referencesAny(((UniqueKey) change.before()).columns(), one);
            case CHECK_CONSTRAINT -> expressionReferencesAny(((CheckConstraint) change.before()).expression(), one);
            case INDEX -> indexReferencesAny((Index) change.before(), one);
            case FOREIGN_KEY -> {
                ForeignKey foreignKey = (ForeignKey) change.before();
                boolean local = referencesAny(foreignKey.columns(), one);
                boolean selfReferenced = sameQualifiedTable(foreignKey.referencedTable(), liveTable.qualifiedName())
                        && referencesAny(foreignKey.referencedColumns(), one);
                yield local || selfReferenced;
            }
        };
    }

    private static TableObjectChange dependencyRefresh(
            TableObjectType type, Identifier name, Object before, Object after, Set<String> alteredColumns) {
        return new TableObjectChange(
                type, TableObjectChangeKind.REPLACE, name, before, after, MigrationRisk.DESTRUCTIVE,
                "SQL Server requires temporary DROP/recreate while ALTER COLUMN changes dependent column(s): "
                        + String.join(",", alteredColumns));
    }

    private static boolean alreadyChanged(TableMigrationPlan plan, TableObjectType type, Object before) {
        return plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == type && Objects.equals(change.before(), before));
    }

    private static boolean referencesAny(List<Identifier> columns, Set<String> alteredColumns) {
        return !referencedIdentifierColumns(columns, alteredColumns).isEmpty();
    }

    private static Set<String> referencedIdentifierColumns(
            List<Identifier> columns, Set<String> alteredColumns) {
        Set<String> referenced = new HashSet<>();
        for (Identifier column : columns) {
            if (alteredColumns.contains(column.normalized())) referenced.add(column.normalized());
        }
        return Set.copyOf(referenced);
    }

    private static Set<String> referencedIndexColumns(Index index, Set<String> alteredColumns) {
        Set<String> referenced = new HashSet<>();
        for (var column : index.columns()) {
            if (column.column() != null && alteredColumns.contains(column.column().normalized())) {
                referenced.add(column.column().normalized());
            }
            if (column.expression() != null) {
                referenced.addAll(referencedExpressionColumns(column.expression(), alteredColumns));
            }
        }
        referenced.addAll(referencedIdentifierColumns(index.includeColumns(), alteredColumns));
        if (index.predicate() != null) {
            referenced.addAll(referencedExpressionColumns(index.predicate(), alteredColumns));
        }
        return Set.copyOf(referenced);
    }

    private static Set<String> referencedExpressionColumns(String expression, Set<String> alteredColumns) {
        Set<String> referenced = new HashSet<>();
        for (String column : alteredColumns) {
            if (expressionReferencesAny(expression, Set.of(column))) referenced.add(column);
        }
        return Set.copyOf(referenced);
    }

    private static boolean indexReferencesAny(Index index, Set<String> alteredColumns) {
        boolean key = index.columns().stream().anyMatch(column ->
                column.column() != null && alteredColumns.contains(column.column().normalized())
                        || column.expression() != null && expressionReferencesAny(column.expression(), alteredColumns));
        if (key) return true;
        if (referencesAny(index.includeColumns(), alteredColumns)) return true;
        return index.predicate() != null && expressionReferencesAny(index.predicate(), alteredColumns);
    }

    private static boolean expressionReferencesAny(String expression, Set<String> alteredColumns) {
        if (expression == null || expression.isBlank()) return false;
        StringBuilder outsideLiterals = new StringBuilder(expression.length());
        boolean inLiteral = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'') {
                if (inLiteral && i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inLiteral = !inLiteral;
                outsideLiterals.append(' ');
                continue;
            }
            outsideLiterals.append(inLiteral ? ' ' : c);
        }
        String normalized = outsideLiterals.toString().toUpperCase(Locale.ROOT)
                .replace("[", "").replace("]", "");
        for (String column : alteredColumns) {
            Pattern token = Pattern.compile("(?<![A-Z0-9_$#])" + Pattern.quote(column) + "(?![A-Z0-9_$#])");
            if (token.matcher(normalized).find()) return true;
        }
        return false;
    }

    private static boolean sameQualifiedTable(QualifiedName left, QualifiedName right) {
        if (!left.name().normalized().equals(right.name().normalized())) return false;
        String leftSchema = left.schemaName().map(Identifier::normalized).orElse("");
        String rightSchema = right.schemaName().map(Identifier::normalized).orElse("");
        return leftSchema.isEmpty() || rightSchema.isEmpty() || leftSchema.equals(rightSchema);
    }

    private static boolean sameName(Identifier left, Identifier right) {
        return left != null && right != null && left.normalized().equals(right.normalized());
    }

    private static Identifier chooseName(Identifier preferred, Identifier fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static List<TableObjectChange> orderedObjectChangesForDrop(List<TableObjectChange> changes) {
        return changes.stream()
                .sorted(Comparator.comparingInt(change -> dropPriority(change.objectType())))
                .toList();
    }

    private static List<TableObjectChange> orderedObjectChangesForAdd(List<TableObjectChange> changes) {
        return changes.stream()
                .sorted(Comparator.comparingInt(change -> addPriority(change.objectType())))
                .toList();
    }

    private static int dropPriority(TableObjectType type) {
        return switch (type) {
            case FOREIGN_KEY -> 0;
            case INDEX -> 1;
            case CHECK_CONSTRAINT -> 2;
            case UNIQUE_KEY -> 3;
            case PRIMARY_KEY -> 4;
        };
    }

    private static int addPriority(TableObjectType type) {
        return switch (type) {
            case PRIMARY_KEY -> 0;
            case UNIQUE_KEY -> 1;
            case CHECK_CONSTRAINT -> 2;
            case INDEX -> 3;
            case FOREIGN_KEY -> 4;
        };
    }

    private static void appendObjectHeading(StringBuilder sql, TableObjectChange change, String phase) {
        sql.append(NL).append("-- [").append(change.risk()).append("] ")
                .append(change.kind()).append(' ').append(change.objectType()).append(' ')
                .append(change.objectName() == null ? "<unnamed>" : change.objectName().value())
                .append(" [").append(phase).append("]: ").append(change.rationale()).append(NL);
    }

    private List<String> renderObjectRename(
            TableMigrationPlan plan, Dialect dialect, TableObjectChange change) {
        if (plan.platform() != DatabasePlatform.ORACLE) {
            throw new UnsupportedOperationException("automatic object rename is currently supported only for Oracle");
        }
        Identifier before = objectName(change.before(), change.objectType());
        Identifier logicalAfter = objectName(change.after(), change.objectType());
        if (before == null || logicalAfter == null) {
            throw new UnsupportedOperationException("rename requires both live and desired object names");
        }
        Identifier after = PhysicalObjectNamePolicy.physicalIdentifier(dialect, logicalAfter);
        String terminator = dialect.statementTerminator();
        String tableName = qualifiedName(dialect, plan.desiredTable().qualifiedName());
        return switch (change.objectType()) {
            case INDEX -> List.of("ALTER INDEX "
                    + qualifiedIndexName(dialect, plan.desiredTable(), before)
                    + " RENAME TO " + dialect.quote(after) + terminator);
            case PRIMARY_KEY, FOREIGN_KEY, UNIQUE_KEY, CHECK_CONSTRAINT -> List.of(
                    "ALTER TABLE " + tableName + " RENAME CONSTRAINT "
                            + dialect.quote(before) + " TO " + dialect.quote(after) + terminator);
        };
    }

    private static Identifier objectName(Object value, TableObjectType type) {
        if (value == null) return null;
        return switch (type) {
            case PRIMARY_KEY -> ((PrimaryKey) value).name();
            case FOREIGN_KEY -> ((ForeignKey) value).name();
            case UNIQUE_KEY -> ((UniqueKey) value).name();
            case CHECK_CONSTRAINT -> ((CheckConstraint) value).name();
            case INDEX -> ((Index) value).name();
        };
    }

    private List<String> renderObjectDrop(
            TableMigrationPlan plan, Dialect dialect, TableObjectChange change) {
        String tableName = qualifiedName(dialect, plan.desiredTable().qualifiedName());
        Identifier name = change.objectName();
        if (change.objectType() != TableObjectType.PRIMARY_KEY && name == null) {
            throw new UnsupportedOperationException("live object has no resolvable name");
        }
        String terminator = dialect.statementTerminator();
        return switch (change.objectType()) {
            case PRIMARY_KEY -> {
                if (plan.platform() == DatabasePlatform.MYSQL) {
                    yield List.of("ALTER TABLE " + tableName + " DROP PRIMARY KEY" + terminator);
                }
                if (name == null) throw new UnsupportedOperationException("live primary key has no resolvable name");
                yield List.of("ALTER TABLE " + tableName + " DROP CONSTRAINT " + dialect.quote(name) + terminator);
            }
            case FOREIGN_KEY -> {
                if (plan.platform() == DatabasePlatform.MYSQL) {
                    yield List.of("ALTER TABLE " + tableName + " DROP FOREIGN KEY " + dialect.quote(name) + terminator);
                }
                yield List.of("ALTER TABLE " + tableName + " DROP CONSTRAINT " + dialect.quote(name) + terminator);
            }
            case UNIQUE_KEY -> {
                if (plan.platform() == DatabasePlatform.MYSQL) {
                    yield List.of("ALTER TABLE " + tableName + " DROP INDEX " + dialect.quote(name) + terminator);
                }
                yield List.of("ALTER TABLE " + tableName + " DROP CONSTRAINT " + dialect.quote(name) + terminator);
            }
            case CHECK_CONSTRAINT -> {
                if (plan.platform() == DatabasePlatform.MYSQL) {
                    yield List.of("ALTER TABLE " + tableName + " DROP CHECK " + dialect.quote(name) + terminator);
                }
                yield List.of("ALTER TABLE " + tableName + " DROP CONSTRAINT " + dialect.quote(name) + terminator);
            }
            case INDEX -> {
                if (plan.platform() == DatabasePlatform.SQLSERVER) {
                    yield List.of("DROP INDEX " + dialect.quote(name) + " ON " + tableName + terminator);
                }
                if (plan.platform() == DatabasePlatform.MYSQL) {
                    yield List.of("ALTER TABLE " + tableName + " DROP INDEX " + dialect.quote(name) + terminator);
                }
                yield List.of("DROP INDEX " + qualifiedIndexName(dialect, plan.desiredTable(), name) + terminator);
            }
        };
    }

    private List<String> renderObjectAdd(
            TableMigrationPlan plan, DdlGenerator ddlGenerator, TableObjectChange change) {
        Object after = change.after();
        if (after == null) return List.of();
        Table table = plan.desiredTable();
        return switch (change.objectType()) {
            case PRIMARY_KEY -> ddlGenerator.renderMigrationAddPrimaryKey(table);
            case FOREIGN_KEY -> List.of(ddlGenerator.renderMigrationAddForeignKey(table, (ForeignKey) after));
            case UNIQUE_KEY -> ddlGenerator.renderMigrationAddUniqueKey(table, (UniqueKey) after);
            case CHECK_CONSTRAINT -> List.of(ddlGenerator.renderMigrationAddCheck(table, (CheckConstraint) after));
            case INDEX -> List.of(ddlGenerator.renderMigrationAddIndex(table, (Index) after));
        };
    }

    private static String qualifiedIndexName(Dialect dialect, Table table, Identifier indexName) {
        return table.qualifiedName().schemaName()
                .map(schema -> dialect.quote(schema) + "." + dialect.quote(indexName))
                .orElseGet(() -> dialect.quote(indexName));
    }

    private static void appendHeader(StringBuilder sql, TableMigrationPlan plan, MigrationRenderOptions options) {
        sql.append("-- SchemaForge Flyway-compatible migration").append(NL)
                .append("-- Phase            : ALTER/Migration M2 - columns + PK/FK/UK/CHECK/INDEX").append(NL)
                .append("-- Platform         : ").append(plan.platform()).append(NL)
                .append("-- Table            : ").append(plan.desiredTable().qualifiedName()).append(NL)
                .append("-- Highest risk     : ").append(plan.highestRisk()).append(NL)
                .append("-- SAFE             : ").append(plan.count(MigrationRisk.SAFE)).append(NL)
                .append("-- REVIEW           : ").append(plan.count(MigrationRisk.REVIEW)).append(NL)
                .append("-- DESTRUCTIVE      : ").append(plan.count(MigrationRisk.DESTRUCTIVE)).append(NL)
                .append("-- Destructive SQL  : ")
                .append(options.confirmDestructive() ? "ENABLED BY EXPLICIT CONFIRMATION" : "BLOCKED/COMMENTED")
                .append(NL)
                .append("-- HINT: SchemaForge never infers column renames. Missing old + new names are DROP + ADD until explicit evidence exists.")
                .append(NL)
                .append("-- HINT: M2 renders safe name-only RENAME operations before DROP/column/ADD phases.")
                .append(NL)
                .append("-- HINT: M2 orders owned-object DROP/REPLACE before column changes and owned-object ADD/REPLACE after them.")
                .append(NL)
                .append("-- HINT: SQL Server temporarily drops and recreates unchanged owned dependencies that would otherwise block ALTER COLUMN; these operational refreshes require confirmDestructive=true.")
                .append(NL)
                .append("-- HINT: Incoming foreign keys owned by other tables are not auto-dropped; DBA/deployment-wide dependency planning remains required for referenced-key changes.")
                .append(NL);
    }

    private static boolean manualOnly(DatabasePlatform platform, ColumnChange change) {
        if (change.kind() == ColumnChangeKind.ALTER_IDENTITY
                || change.kind() == ColumnChangeKind.ALTER_GENERATED_EXPRESSION) {
            return true;
        }
        return change.kind() == ColumnChangeKind.ADD_COLUMN
                && change.after() != null
                && change.after().identity()
                && platform == DatabasePlatform.ORACLE;
    }

    private static String compositeKey(DatabasePlatform platform, ColumnChange change) {
        if (platform == DatabasePlatform.MYSQL
                && (change.kind() == ColumnChangeKind.ALTER_TYPE
                || change.kind() == ColumnChangeKind.ALTER_NULLABILITY
                || change.kind() == ColumnChangeKind.ALTER_DEFAULT)) {
            return "MYSQL_MODIFY:" + change.columnName().normalized();
        }
        if (platform == DatabasePlatform.SQLSERVER
                && (change.kind() == ColumnChangeKind.ALTER_TYPE
                || change.kind() == ColumnChangeKind.ALTER_NULLABILITY)) {
            return "SQLSERVER_ALTER:" + change.columnName().normalized();
        }
        return null;
    }

    private List<String> renderChange(TableMigrationPlan plan, Dialect dialect, ColumnChange change) {
        String tableName = qualifiedName(dialect, plan.desiredTable().qualifiedName());
        return switch (change.kind()) {
            case ADD_COLUMN -> List.of(renderAddColumn(plan.platform(), dialect, plan.desiredTable(), change.after(), tableName));
            case DROP_COLUMN -> List.of(renderDropColumn(plan.platform(), dialect, change.columnName(), tableName));
            case ALTER_TYPE -> renderAlterType(plan.platform(), dialect, plan.desiredTable(), change.after(), tableName);
            case ALTER_NULLABILITY -> renderAlterNullability(plan.platform(), dialect, plan.desiredTable(), change.after(), tableName);
            case ALTER_DEFAULT -> renderAlterDefault(plan.platform(), dialect, plan.desiredTable(), change.after(), tableName);
            case ALTER_IDENTITY, ALTER_GENERATED_EXPRESSION -> List.of();
        };
    }

    private String renderAddColumn(DatabasePlatform platform, Dialect dialect, Table table, Column column, String tableName) {
        String keyword = switch (platform) {
            case ORACLE, SQLSERVER -> " ADD ";
            case POSTGRESQL, DB2_ZOS, DB2_LUW, MYSQL -> " ADD COLUMN ";
        };
        return "ALTER TABLE " + tableName + keyword + columnDefinition(dialect, table, column)
                + dialect.statementTerminator();
    }

    private String renderDropColumn(DatabasePlatform platform, Dialect dialect, Identifier column, String tableName) {
        return "ALTER TABLE " + tableName + " DROP COLUMN " + dialect.quote(column)
                + dialect.statementTerminator();
    }

    private List<String> renderAlterType(
            DatabasePlatform platform, Dialect dialect, Table table, Column desired, String tableName) {
        String column = dialect.quote(desired.name());
        String type = dialect.sqlType(table, desired);
        return switch (platform) {
            case ORACLE -> List.of("ALTER TABLE " + tableName + " MODIFY (" + column + " " + type + ")"
                    + dialect.statementTerminator());
            case POSTGRESQL -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column + " TYPE " + type
                    + dialect.statementTerminator());
            case DB2_ZOS, DB2_LUW -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column + " SET DATA TYPE " + type
                    + dialect.statementTerminator());
            case SQLSERVER -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column + " " + type
                    + (desired.nullable() ? " NULL" : " NOT NULL") + dialect.statementTerminator());
            case MYSQL -> List.of("ALTER TABLE " + tableName + " MODIFY COLUMN "
                    + columnDefinition(dialect, table, desired) + dialect.statementTerminator());
        };
    }

    private List<String> renderAlterNullability(
            DatabasePlatform platform, Dialect dialect, Table table, Column desired, String tableName) {
        String column = dialect.quote(desired.name());
        return switch (platform) {
            case ORACLE -> List.of("ALTER TABLE " + tableName + " MODIFY (" + column
                    + (desired.nullable() ? " NULL" : " NOT NULL") + ")" + dialect.statementTerminator());
            case POSTGRESQL -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column
                    + (desired.nullable() ? " DROP NOT NULL" : " SET NOT NULL") + dialect.statementTerminator());
            case DB2_ZOS, DB2_LUW -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column
                    + (desired.nullable() ? " DROP NOT NULL" : " SET NOT NULL") + dialect.statementTerminator());
            case SQLSERVER -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column + " "
                    + dialect.sqlType(table, desired) + (desired.nullable() ? " NULL" : " NOT NULL")
                    + dialect.statementTerminator());
            case MYSQL -> List.of("ALTER TABLE " + tableName + " MODIFY COLUMN "
                    + columnDefinition(dialect, table, desired) + dialect.statementTerminator());
        };
    }

    private List<String> renderAlterDefault(
            DatabasePlatform platform, Dialect dialect, Table table, Column desired, String tableName) {
        String column = dialect.quote(desired.name());
        boolean present = desired.defaultValue().isPresent();
        String expression = present ? dialect.expression(desired.defaultValue().expression()) : null;
        return switch (platform) {
            case ORACLE -> List.of("ALTER TABLE " + tableName + " MODIFY (" + column + " DEFAULT "
                    + (present ? expression : "NULL") + ")" + dialect.statementTerminator());
            case POSTGRESQL -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column
                    + (present ? " SET DEFAULT " + expression : " DROP DEFAULT") + dialect.statementTerminator());
            case DB2_ZOS, DB2_LUW -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column
                    + (present ? " SET DEFAULT " + expression : " DROP DEFAULT") + dialect.statementTerminator());
            case MYSQL -> List.of("ALTER TABLE " + tableName + " MODIFY COLUMN "
                    + columnDefinition(dialect, table, desired) + dialect.statementTerminator());
            case SQLSERVER -> sqlServerDefaultStatements(dialect, tableName, desired, expression);
        };
    }

    private List<String> sqlServerDefaultStatements(
            Dialect dialect, String tableName, Column desired, String desiredExpression) {
        String columnLiteral = sqlLiteral(desired.name().value());
        String objectLiteral = sqlLiteral(tableName.replace("[", "").replace("]", ""));
        String variable = "@SchemaForgeDf_" + Integer.toUnsignedString(desired.name().normalized().hashCode(), 16);
        String sqlVariable = variable + "_sql";
        String dropBatch = "DECLARE " + variable + " sysname; "
                + "SELECT " + variable + " = dc.name FROM sys.default_constraints dc "
                + "JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id "
                + "WHERE dc.parent_object_id = OBJECT_ID(N'" + objectLiteral + "') "
                + "AND c.name = N'" + columnLiteral + "'; "
                + "IF " + variable + " IS NOT NULL BEGIN "
                + "DECLARE " + sqlVariable + " nvarchar(max); "
                + "SET " + sqlVariable + " = N'ALTER TABLE " + tableName
                + " DROP CONSTRAINT ' + QUOTENAME(" + variable + "); "
                + "EXEC sys.sp_executesql " + sqlVariable + "; END;";
        String drop = "EXEC sys.sp_executesql N'" + dropBatch.replace("'", "''") + "'"
                + dialect.statementTerminator();
        if (!desired.defaultValue().isPresent()) return List.of(drop);

        String constraint = deterministicDefaultConstraint(desired.name(), tableName);
        String add = "ALTER TABLE " + tableName + " ADD CONSTRAINT " + dialect.quote(Identifier.of(constraint))
                + " DEFAULT " + desiredExpression + " FOR " + dialect.quote(desired.name())
                + dialect.statementTerminator();
        return List.of(drop, add);
    }

    private static String deterministicDefaultConstraint(Identifier column, String renderedTableName) {
        String table = renderedTableName.replaceAll("[^A-Za-z0-9_$#]", "_")
                .replaceAll("_+", "_").replaceAll("^_|_$", "");
        String value = "DF_" + table + "_" + column.normalized();
        if (value.length() > 120) value = value.substring(0, 120);
        if (!Character.isLetter(value.charAt(0))) value = "DF_" + value;
        return value;
    }

    private String columnDefinition(Dialect dialect, Table table, Column column) {
        StringBuilder sql = new StringBuilder(dialect.quote(column.name()));
        if (!column.generated() || dialect.generatedColumnIncludesDataType()) {
            sql.append(" ").append(dialect.sqlType(table, column));
        }
        sql.append(dialect.columnPhysicalClause(column));
        if (column.generated()) {
            dialect.require(DialectFeature.GENERATED_COLUMN);
            sql.append(dialect.generatedColumnClause(column));
        } else if (column.identity() && !dialect.identityUsesNamedSequence()) {
            dialect.require(DialectFeature.IDENTITY_COLUMN);
            sql.append(dialect.identityClause(column));
        } else if (column.defaultValue().isPresent()) {
            sql.append(dialect.defaultClause(column));
        }
        if (!column.nullable() && (!column.generated() || dialect.generatedColumnIncludesNullability())) {
            sql.append(" NOT NULL");
        }
        sql.append(dialect.inlineColumnCommentClause(column));
        sql.append(dialect.inlineColumnConstraintClause(table, column));
        return sql.toString();
    }


    private static String safeComment(String value) {
        if (value == null || value.isBlank()) return "dialect expression mapping is unsupported";
        return value.replace("\r", " ").replace("\n", " ").replace("*/", "* /").trim();
    }

    private static String qualifiedName(Dialect dialect, QualifiedName name) {
        return name.schemaName()
                .map(schema -> dialect.quote(schema) + "." + dialect.quote(name.name()))
                .orElseGet(() -> dialect.quote(name.name()));
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void appendStatement(StringBuilder sql, String statement, boolean commented) {
        String normalized = statement.stripTrailing();
        if (!commented) {
            sql.append(normalized).append(NL);
            return;
        }
        for (String line : normalized.split("\\R", -1)) {
            sql.append("-- ").append(line).append(NL);
        }
    }
}
