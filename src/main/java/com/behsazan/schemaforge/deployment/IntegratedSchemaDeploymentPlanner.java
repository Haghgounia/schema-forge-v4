package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds a deterministic, DBMS-neutral deployment plan from one unique integrated canonical schema.
 *
 * <p>The planner does not select between historical versions. Duplicate input must already have
 * been rejected by {@link IntegratedSchemaAssembler}. It also does not render SQL and therefore
 * does not change any of the stable Oracle, PostgreSQL or SQL Server generators.</p>
 */
public final class IntegratedSchemaDeploymentPlanner {
    private final ForeignKeyAnalyzer foreignKeyAnalyzer;

    public IntegratedSchemaDeploymentPlanner() {
        this(new ForeignKeyAnalyzer());
    }

    IntegratedSchemaDeploymentPlanner(ForeignKeyAnalyzer foreignKeyAnalyzer) {
        this.foreignKeyAnalyzer = Objects.requireNonNull(foreignKeyAnalyzer);
    }

    /** Validates foreign keys and builds the ordered four-phase integrated deployment plan. */
    public IntegratedSchemaDeploymentPlan plan(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        schema = new SpecificationNormalizer().normalize(schema);
        ForeignKeyAnalysisResult analysis = foreignKeyAnalyzer.analyze(schema);
        if (!analysis.deployable()) {
            String blockers = analysis.issues().stream()
                    .filter(issue -> issue.severity() == ForeignKeyAnalysisSeverity.ERROR)
                    .map(issue -> issue.code() + ": " + issue.message())
                    .distinct()
                    .limit(5)
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("foreign-key validation failed");
            throw new IllegalStateException("INTEGRATED_DEPLOYMENT_BLOCKED: " + blockers);
        }

        List<Sequence> sequences = schema.sequences().stream()
                .sorted(Comparator.comparing(sequence -> key(sequence.qualifiedName())))
                .toList();
        List<Table> tables = schema.tables().stream()
                .sorted(Comparator.comparing(table -> key(table.qualifiedName())))
                .toList();

        List<TableOwnedObject<CheckConstraint>> checks = new ArrayList<>();
        List<TableOwnedObject<UniqueKey>> uniqueKeys = new ArrayList<>();
        List<TableOwnedObject<Index>> indexes = new ArrayList<>();
        List<ForeignKeyDeployment> foreignKeys = new ArrayList<>();
        List<Table> metadataTables = new ArrayList<>();

        for (Table table : tables) {
            table.checkConstraints().stream()
                    .sorted(Comparator.comparing(IntegratedSchemaDeploymentPlanner::checkKey))
                    .map(check -> new TableOwnedObject<>(table.qualifiedName(), check))
                    .forEach(checks::add);
            table.uniqueKeys().stream()
                    .sorted(Comparator.comparing(IntegratedSchemaDeploymentPlanner::uniqueKey))
                    .map(unique -> new TableOwnedObject<>(table.qualifiedName(), unique))
                    .forEach(uniqueKeys::add);
            table.indexes().stream()
                    .sorted(Comparator.comparing(IntegratedSchemaDeploymentPlanner::indexKey))
                    .map(index -> new TableOwnedObject<>(table.qualifiedName(), index))
                    .forEach(indexes::add);
            table.foreignKeys().stream()
                    .filter(ForeignKey::physicalReference)
                    .sorted(Comparator.comparing(IntegratedSchemaDeploymentPlanner::foreignKeyKey))
                    .map(foreignKey -> new ForeignKeyDeployment(
                            table.qualifiedName(), foreignKey, resolveTargetName(table, foreignKey)))
                    .forEach(foreignKeys::add);
            if (containsMetadata(table)) {
                metadataTables.add(table);
            }
        }

        return new IntegratedSchemaDeploymentPlan(
                analysis, sequences, tables, checks, uniqueKeys, indexes, foreignKeys, metadataTables);
    }

    private static QualifiedName resolveTargetName(Table owner, ForeignKey foreignKey) {
        QualifiedName referenced = foreignKey.referencedTable();
        if (referenced.schemaName().isPresent()) {
            return referenced;
        }
        String ownerSchema = owner.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        return QualifiedName.of(ownerSchema, referenced.name().value());
    }

    private static boolean containsMetadata(Table table) {
        if (!table.persianName().isEmpty() || !table.description().isEmpty()) {
            return true;
        }
        if (table.columns().stream().anyMatch(column -> !column.description().isEmpty())) {
            return true;
        }
        return table.physicalOptions().entrySet().stream()
                .anyMatch(entry -> "GRANTS".equalsIgnoreCase(entry.getKey())
                        && entry.getValue() != null && !entry.getValue().isBlank());
    }

    private static String checkKey(CheckConstraint check) {
        return nameOrFallback(check.name(), check.expression());
    }

    private static String uniqueKey(UniqueKey unique) {
        String fallback = unique.columns().stream().map(Identifier::normalized).reduce((a, b) -> a + "," + b).orElse("");
        return nameOrFallback(unique.name(), fallback);
    }

    private static String indexKey(Index index) {
        String fallback = index.columns().toString() + "|" + index.includeColumns() + "|" + index.predicate();
        return nameOrFallback(index.name(), fallback);
    }

    private static String foreignKeyKey(ForeignKey foreignKey) {
        String fallback = foreignKey.columns() + "->" + foreignKey.referencedTable() + foreignKey.referencedColumns();
        return nameOrFallback(foreignKey.name(), fallback);
    }

    private static String nameOrFallback(Identifier name, String fallback) {
        return (name == null ? fallback : name.normalized()).toUpperCase(Locale.ROOT);
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }
}
