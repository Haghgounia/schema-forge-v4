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
 * <p>The canonical analyzer remains DBMS-neutral. This validator runs only during integrated
 * rendering and therefore cannot change the historical generator baseline.</p>
 */
public final class DialectForeignKeyCompatibilityValidator {

    /** Returns all dialect-specific FK blockers for the supplied plan. */
    public List<DialectForeignKeyCompatibilityIssue> validate(
            DatabaseSchema schema, IntegratedSchemaDeploymentPlan plan, Dialect dialect) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");

        if (!(dialect instanceof ForeignKeyTypeCompatibilityPolicy policy)) {
            return List.of();
        }

        Map<String, Table> tables = new LinkedHashMap<>();
        for (Table table : schema.tables()) {
            tables.put(key(table.qualifiedName()), table);
        }

        List<DialectForeignKeyCompatibilityIssue> issues = new ArrayList<>();
        for (ForeignKeyDeployment deployment : plan.phase3ForeignKeys()) {
            Table owner = tables.get(key(deployment.table()));
            Table target = tables.get(key(deployment.referencedTable()));
            if (owner == null || target == null) {
                continue; // Canonical FK analysis already reports structural blockers.
            }

            ForeignKey foreignKey = deployment.foreignKey();
            for (int i = 0; i < foreignKey.columns().size(); i++) {
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
        }
        return List.copyOf(issues);
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }
}
