package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Renders a table migration plan as a Flyway-compatible versioned SQL body. */
public final class MigrationSqlRenderer {
    private static final String NL = System.lineSeparator();

    public String render(TableMigrationPlan plan, MigrationRenderOptions options) {
        Objects.requireNonNull(plan, "plan must not be null");
        options = options == null ? MigrationRenderOptions.safeDefaults() : options;
        Dialect dialect = DialectFactory.create(plan.platform());
        DdlGenerator ddlGenerator = new DdlGenerator(dialect);

        StringBuilder sql = new StringBuilder();
        appendHeader(sql, plan, options);
        if (plan.empty()) {
            sql.append("-- No table changes detected. This migration is intentionally empty.").append(NL);
            return sql.toString();
        }

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
            boolean blocked = change.risk() == MigrationRisk.DESTRUCTIVE && !options.confirmDestructive();
            if (blocked) {
                sql.append("-- BLOCKED: destructive SQL is commented out. Re-render with confirmDestructive=true after DBA approval.")
                        .append(NL);
            }
            for (String statement : statements) {
                if (statement == null || statement.isBlank()) continue;
                appendStatement(sql, statement, blocked);
            }
        }

        renderObjectAddPhase(sql, plan, dialect, ddlGenerator, options);
        return sql.toString();
    }

    private void renderObjectDropPhase(
            StringBuilder sql, TableMigrationPlan plan, Dialect dialect, MigrationRenderOptions options) {
        for (TableObjectChange change : plan.objectChanges()) {
            if (change.kind() == TableObjectChangeKind.ADD) continue;
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
        for (TableObjectChange change : plan.objectChanges()) {
            if (change.kind() == TableObjectChangeKind.DROP) continue;
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

    private static void appendObjectHeading(StringBuilder sql, TableObjectChange change, String phase) {
        sql.append(NL).append("-- [").append(change.risk()).append("] ")
                .append(change.kind()).append(' ').append(change.objectType()).append(' ')
                .append(change.objectName() == null ? "<unnamed>" : change.objectName().value())
                .append(" [").append(phase).append("]: ").append(change.rationale()).append(NL);
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
                .append("-- HINT: M2 orders owned-object DROP/REPLACE before column changes and owned-object ADD/REPLACE after them.")
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
            case POSTGRESQL, DB2_ZOS, MYSQL -> " ADD COLUMN ";
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
            case DB2_ZOS -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column + " SET DATA TYPE " + type
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
            case DB2_ZOS -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column
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
            case DB2_ZOS -> List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + column
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
        String drop = "DECLARE " + variable + " sysname; "
                + "SELECT " + variable + " = dc.name FROM sys.default_constraints dc "
                + "JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id "
                + "WHERE dc.parent_object_id = OBJECT_ID(N'" + objectLiteral + "') "
                + "AND c.name = N'" + columnLiteral + "'; "
                + "IF " + variable + " IS NOT NULL EXEC(N'ALTER TABLE " + tableName
                + " DROP CONSTRAINT ' + QUOTENAME(" + variable + "));";
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
