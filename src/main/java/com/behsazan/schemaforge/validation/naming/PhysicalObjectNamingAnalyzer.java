package com.behsazan.schemaforge.validation.naming;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.PhysicalObjectNamePolicy;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Audits logical-to-physical names before DDL rendering.
 *
 * <p>The audit is intentionally non-destructive. It reports DBMS-specific shortening and
 * physical-name collisions in the SQL issue header while the renderer uses the same stable
 * physical naming policy. Source table/column names are never silently shortened.</p>
 */
public final class PhysicalObjectNamingAnalyzer {

    public List<ValidationIssue> analyze(DatabaseSchema schema, Dialect dialect) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, String> occupied = new LinkedHashMap<>();
        Set<String> validatedSchemas = new LinkedHashSet<>();

        int sourceLimit = PhysicalObjectNamePolicy.maximumLength(dialect);
        for (Table table : schema.tables()) {
            table.qualifiedName().schemaName().ifPresent(schemaName -> {
                if (validatedSchemas.add(schemaName.normalized())) {
                    validateSourceIdentifier(issues, "schemas." + schemaName.value(),
                            "SCHEMA", schemaName, sourceLimit, dialect);
                }
            });
            validateSourceIdentifier(issues, "tables." + table.qualifiedName().name().value(),
                    "TABLE", table.qualifiedName().name(), sourceLimit, dialect);
            occupySourceTableName(issues, occupied, dialect, table);
            table.columns().forEach(column -> validateSourceIdentifier(issues,
                    "tables." + table.qualifiedName().name().value() + ".columns." + column.name().value(),
                    "COLUMN", column.name(), sourceLimit, dialect));

            table.primaryKey().ifPresent(pk -> {
                String logicalConstraint = logicalPrimaryKeyName(table, pk);
                auditObject(issues, occupied, dialect, table, "PRIMARY_KEY", logicalConstraint,
                        constraintScope(dialect, table, "PRIMARY_KEY"));
                String logicalIndex = logicalPrimaryKeyIndexName(dialect, table, pk);
                if (logicalIndex != null) {
                    auditObject(issues, occupied, dialect, table, "INDEX", logicalIndex,
                            indexScope(dialect, table));
                }
            });
            for (UniqueKey key : table.uniqueKeys()) {
                String logical = key.name() == null
                        ? "UK_" + table.qualifiedName().name().normalized() + "_" + identifiers(key.columns())
                        : key.name().value();
                auditObject(issues, occupied, dialect, table, "UNIQUE_KEY", logical,
                        constraintScope(dialect, table, "UNIQUE_KEY"));
                // Oracle sample convention uses the UK constraint name for its enforcing index.
                if (dialect.name().toUpperCase(Locale.ROOT).contains("ORACLE")) {
                    auditObject(issues, occupied, dialect, table, "INDEX", logical, indexScope(dialect, table));
                }
            }
            table.checkConstraints().forEach(check -> {
                String logical = check.name() == null
                        ? "CHK_" + table.qualifiedName().name().normalized()
                        : check.name().value();
                auditObject(issues, occupied, dialect, table, "CHECK", logical,
                        constraintScope(dialect, table, "CHECK"));
            });
            for (ForeignKey fk : table.foreignKeys()) {
                String logical = fk.name() == null
                        ? "FK_" + table.qualifiedName().name().normalized() + "_" + identifiers(fk.columns())
                        : fk.name().value();
                auditObject(issues, occupied, dialect, table, "FOREIGN_KEY", logical,
                        constraintScope(dialect, table, "FOREIGN_KEY"));
            }
            for (Index index : table.indexes()) {
                String logical = index.name() == null
                        ? "IX_" + table.qualifiedName().name().normalized() + "_" + indexColumns(index)
                        : index.name().value();
                auditObject(issues, occupied, dialect, table, "INDEX", logical, indexScope(dialect, table));
            }
        }

        for (Sequence sequence : schema.sequences()) {
            sequence.qualifiedName().schemaName().ifPresent(schemaIdentifier -> {
                if (validatedSchemas.add(schemaIdentifier.normalized())) {
                    validateSourceIdentifier(issues, "schemas." + schemaIdentifier.value(),
                            "SCHEMA", schemaIdentifier, sourceLimit, dialect);
                }
            });
            String schemaName = sequence.qualifiedName().schemaName().map(Identifier::normalized).orElse("");
            auditObject(issues, occupied, dialect, null, "SEQUENCE", sequence.qualifiedName().name().value(),
                    sequenceScope(dialect, schemaName));
        }
        return List.copyOf(issues);
    }


    private static void occupySourceTableName(
            List<ValidationIssue> issues, Map<String, String> occupied, Dialect dialect, Table table) {
        String scope = tableObjectScope(dialect, table);
        if (scope == null) return;
        String logicalName = table.qualifiedName().name().value();
        String collisionKey = scope + "|" + table.qualifiedName().name().normalized();
        String previous = occupied.putIfAbsent(collisionKey, "TABLE:" + logicalName);
        if (previous != null && !previous.equals("TABLE:" + logicalName)) {
            issues.add(new ValidationIssue("ERROR", "PHYSICAL_NAME_COLLISION",
                    "tables." + logicalName,
                    "TABLE logical name '" + logicalName + "' shares target namespace " + scope
                            + " with " + previous + "."));
        }
    }

    private static String indexColumns(Index index) {
        return index.columns().stream()
                .map(column -> column.expressionBased() ? "EXPR" : column.column().normalized())
                .collect(Collectors.joining("_"));
    }

    private static void validateSourceIdentifier(
            List<ValidationIssue> issues, String path, String type, Identifier identifier,
            int maximumLength, Dialect dialect) {
        if (identifier.value().length() <= maximumLength) return;
        issues.add(new ValidationIssue("ERROR", "SOURCE_IDENTIFIER_TOO_LONG", path,
                type + " identifier '" + identifier.value() + "' exceeds the " + dialect.name()
                        + " limit of " + maximumLength
                        + "; business table/column identifiers are never silently renamed."));
    }

    private static void auditObject(
            List<ValidationIssue> issues, Map<String, String> occupied, Dialect dialect, Table owner,
            String objectType, String logicalName, String namespaceScope) {
        Identifier logical = Identifier.of(logicalName);
        Identifier physical = PhysicalObjectNamePolicy.physicalIdentifier(dialect, logical);
        String path = owner == null ? "objects." + logicalName
                : "tables." + owner.qualifiedName().name().value() + ".objects." + logicalName;
        if (!logical.value().equals(physical.value())) {
            issues.add(new ValidationIssue("WARNING", "PHYSICAL_NAME_SHORTENED", path,
                    objectType + " logical name '" + logical.value() + "' is rendered as '"
                            + physical.value() + "' for " + dialect.name() + " (limit="
                            + PhysicalObjectNamePolicy.maximumLength(dialect) + ")."));
        }

        String collisionKey = namespaceScope + "|" + physical.normalized();
        String ownerKey = owner == null ? "<SCHEMA>" : owner.qualifiedName().toString().toUpperCase(Locale.ROOT);
        String descriptor = objectType + ":" + ownerKey + ":" + logical.value();
        String previous = occupied.putIfAbsent(collisionKey, descriptor);
        if (previous != null && !previous.equals(descriptor)) {
            issues.add(new ValidationIssue("ERROR", "PHYSICAL_NAME_COLLISION", path,
                    objectType + " logical name '" + logical.value() + "' renders to physical name '"
                            + physical.value() + "', already used by " + previous + " in namespace "
                            + namespaceScope + "."));
        }
    }

    private static String logicalPrimaryKeyName(Table table, PrimaryKey pk) {
        return pk.name() == null ? "PK_" + table.qualifiedName().name().normalized() : pk.name().value();
    }

    private static String logicalPrimaryKeyIndexName(Dialect dialect, Table table, PrimaryKey pk) {
        String db = dialect.name().toUpperCase(Locale.ROOT);
        if (db.contains("ORACLE")) {
            String constraint = logicalPrimaryKeyName(table, pk);
            String columns = identifiers(pk.columns());
            return constraint.toUpperCase(Locale.ROOT).endsWith("_" + columns.toUpperCase(Locale.ROOT))
                    ? constraint
                    : constraint + "_" + columns;
        }
        if (db.contains("DB2ZOS")) {
            return logicalPrimaryKeyName(table, pk) + "_IX";
        }
        return null;
    }

    private static String identifiers(List<Identifier> values) {
        return values.stream().map(Identifier::normalized).collect(Collectors.joining("_"));
    }

    private static String indexScope(Dialect dialect, Table table) {
        String db = dbKey(dialect);
        String schema = schemaName(table);
        if (db.contains("POSTGRES")) {
            // PostgreSQL tables, indexes and sequences are relations in a schema namespace.
            return schema + "|RELATION";
        }
        if (db.contains("SQLSERVER") || db.contains("MYSQL")) {
            // Index identifiers are table-local in SQL Server and MySQL.
            return schema + "|TABLE:" + table.qualifiedName().name().normalized() + "|INDEX";
        }
        // Oracle and both Db2 families require schema-wide index-name uniqueness.
        return schema + "|INDEX";
    }

    private static String constraintScope(Dialect dialect, Table table, String constraintType) {
        String db = dbKey(dialect);
        String schema = schemaName(table);
        if (db.contains("ORACLE")) {
            return schema + "|CONSTRAINT";
        }
        if (db.contains("SQLSERVER")) {
            return schema + "|OBJECT";
        }
        if (db.contains("MYSQL")) {
            // MySQL keeps constraint namespaces by constraint family; indexes remain table-local.
            return schema + "|CONSTRAINT:" + constraintType;
        }
        if (db.contains("POSTGRES")
                && (constraintType.equals("PRIMARY_KEY") || constraintType.equals("UNIQUE_KEY"))) {
            // PK/UK create an enforcing index whose relation name is the constraint name.
            return schema + "|RELATION";
        }
        // PostgreSQL CHECK/FK and Db2 constraints are table-scoped for our collision audit.
        return schema + "|TABLE:" + table.qualifiedName().name().normalized() + "|CONSTRAINT";
    }

    private static String tableObjectScope(Dialect dialect, Table table) {
        String db = dbKey(dialect);
        String schema = schemaName(table);
        if (db.contains("ORACLE")) return schema + "|ORACLE_SHARED";
        if (db.contains("POSTGRES")) return schema + "|RELATION";
        if (db.contains("SQLSERVER")) return schema + "|OBJECT";
        return null;
    }

    private static String sequenceScope(Dialect dialect, String schema) {
        String db = dbKey(dialect);
        if (db.contains("ORACLE")) return schema + "|ORACLE_SHARED";
        if (db.contains("POSTGRES")) return schema + "|RELATION";
        if (db.contains("SQLSERVER")) return schema + "|OBJECT";
        return schema + "|SEQUENCE";
    }

    private static String schemaName(Table table) {
        return table.qualifiedName().schemaName().map(Identifier::normalized).orElse("");
    }

    private static String dbKey(Dialect dialect) {
        return dialect.name().replace("_", "").replace("-", "").toUpperCase(Locale.ROOT);
    }

}
