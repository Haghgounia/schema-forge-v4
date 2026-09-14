package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.ForeignKeyTypeCompatibilityPolicy;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Applies optional DBMS-specific FK column compatibility rules to an integrated deployment plan.
 *
 * <p>The canonical analyzer remains DBMS-neutral. This validator is shared by integrated and
 * per-table rendering so both paths enforce the same DBMS-specific FK safety contract.</p>
 */
public final class DialectForeignKeyCompatibilityValidator {

    /** Returns all dialect-specific FK blockers for the supplied plan. */
    public List<DialectForeignKeyCompatibilityIssue> validate(
            DatabaseSchema schema, IntegratedSchemaDeploymentPlan plan, Dialect dialect) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");

        if (!(dialect instanceof ForeignKeyTypeCompatibilityPolicy)) {
            return List.of();
        }

        Map<String, Table> tables = tablesByName(schema);
        List<DialectForeignKeyCompatibilityIssue> issues = new ArrayList<>();
        for (ForeignKeyDeployment deployment : plan.phase3ForeignKeys()) {
            Table owner = tables.get(key(deployment.table()));
            Table target = tables.get(key(deployment.referencedTable()));
            if (owner == null || target == null) {
                continue; // Canonical FK analysis already reports structural blockers.
            }
            issues.addAll(validateForeignKey(owner, deployment.foreignKey(), target, dialect));
        }
        return List.copyOf(issues);
    }

    /**
     * Validates only the foreign keys owned by one table against the broader canonical schema.
     * This is used by per-table artifact generation so it cannot emit an FK that the integrated
     * renderer would reject for the same DBMS.
     */
    public List<DialectForeignKeyCompatibilityIssue> validateTable(
            DatabaseSchema schema, Table owner, Dialect dialect) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");

        if (!(dialect instanceof ForeignKeyTypeCompatibilityPolicy)) {
            return List.of();
        }

        Map<String, Table> tables = tablesByName(schema);
        List<DialectForeignKeyCompatibilityIssue> issues = new ArrayList<>();
        for (ForeignKey foreignKey : owner.foreignKeys()) {
            if (!foreignKey.physicalReference()) {
                continue;
            }
            QualifiedName referencedTable = resolveTargetName(owner, foreignKey);
            Table target = tables.get(key(referencedTable));
            if (target == null) {
                continue; // Structural validation owns missing targets/columns.
            }
            issues.addAll(validateForeignKey(owner, foreignKey, target, dialect));
        }
        return List.copyOf(issues);
    }

    private List<DialectForeignKeyCompatibilityIssue> validateForeignKey(
            Table owner, ForeignKey foreignKey, Table target, Dialect dialect) {
        ForeignKeyTypeCompatibilityPolicy policy = (ForeignKeyTypeCompatibilityPolicy) dialect;
        List<DialectForeignKeyCompatibilityIssue> issues = new ArrayList<>();
        int pairs = Math.min(foreignKey.columns().size(), foreignKey.referencedColumns().size());
        for (int i = 0; i < pairs; i++) {
            Identifier ownerColumnName = foreignKey.columns().get(i);
            Identifier targetColumnName = foreignKey.referencedColumns().get(i);
            Column ownerColumn = owner.findColumn(ownerColumnName.value()).orElse(null);
            Column targetColumn = target.findColumn(targetColumnName.value()).orElse(null);
            if (ownerColumn == null || targetColumn == null) {
                continue;
            }

            String ownerType = policy.foreignKeyComparableType(ownerColumn);
            String targetType = policy.foreignKeyComparableType(targetColumn);
            if (!ownerType.equals(targetType)) {
                DialectForeignKeyCompatibilityCode code =
                        DialectForeignKeyCompatibilityCode.SQLSERVER_FK_TYPE_MISMATCH;
                String foreignKeyName = foreignKey.name() == null ? "" : foreignKey.name().value();
                issues.add(new DialectForeignKeyCompatibilityIssue(
                        code,
                        owner.qualifiedName().toString(),
                        foreignKeyName,
                        ownerColumnName.value(),
                        target.qualifiedName().toString(),
                        targetColumnName.value(),
                        dialect.sqlType(ownerColumn),
                        dialect.sqlType(targetColumn),
                        "Foreign-key columns render to incompatible SQL types for "
                                + dialect.getClass().getSimpleName() + ": "
                                + owner.qualifiedName() + "." + ownerColumnName.value() + "="
                                + dialect.sqlType(ownerColumn) + " versus "
                                + target.qualifiedName() + "." + targetColumnName.value() + "="
                                + dialect.sqlType(targetColumn)));
            }
        }
        return List.copyOf(issues);
    }

    private static Map<String, Table> tablesByName(DatabaseSchema schema) {
        Map<String, Table> tables = new LinkedHashMap<>();
        for (Table table : schema.tables()) {
            tables.put(key(table.qualifiedName()), table);
        }
        return tables;
    }

    private static QualifiedName resolveTargetName(Table owner, ForeignKey foreignKey) {
        QualifiedName referenced = foreignKey.referencedTable();
        if (referenced.schemaName().isPresent()) {
            return referenced;
        }
        String ownerSchema = owner.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        return QualifiedName.of(ownerSchema, referenced.name().value());
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }
}
