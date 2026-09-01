package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves and validates canonical foreign keys before integrated database deployment.
 *
 * <p>The analyzer is DBMS-neutral. It validates only physical references, treats logical
 * references as non-deployable documentation, resolves an omitted referenced schema to the owner
 * table schema, and verifies that referenced columns exist and form a canonical PK or UNIQUE key.
 * Cycles are reported but are not blockers because integrated deployment can create tables first
 * and foreign keys in a second phase.</p>
 */
public final class ForeignKeyAnalyzer {

    /** Analyzes all foreign keys in a canonical schema. */
    public ForeignKeyAnalysisResult analyze(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        schema = new SpecificationNormalizer().normalize(schema);
        Map<String, Table> tables = new LinkedHashMap<>();
        schema.tables().forEach(table -> tables.put(key(table.qualifiedName()), table));

        List<ForeignKeyAnalysisIssue> issues = new ArrayList<>();
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        tables.keySet().forEach(table -> graph.put(table, new LinkedHashSet<>()));

        int foreignKeys = 0;
        int physical = 0;
        int logical = 0;
        int resolved = 0;
        int selfReferences = 0;

        for (Table owner : schema.tables()) {
            for (ForeignKey foreignKey : owner.foreignKeys()) {
                foreignKeys++;
                QualifiedName targetName = resolveTargetName(owner, foreignKey);
                String ownerKey = key(owner.qualifiedName());
                String targetKey = key(targetName);
                String foreignKeyName = foreignKey.name() == null ? "" : foreignKey.name().value();

                if (!foreignKey.physicalReference()) {
                    logical++;
                    issues.add(issue(ForeignKeyAnalysisSeverity.INFO,
                            ForeignKeyAnalysisCode.LOGICAL_FOREIGN_KEY_SKIPPED,
                            owner, foreignKeyName, targetName,
                            "Logical foreign key is documentation only and will not be deployed"));
                    continue;
                }

                physical++;
                Table target = tables.get(targetKey);
                if (target == null) {
                    issues.add(issue(ForeignKeyAnalysisSeverity.ERROR,
                            ForeignKeyAnalysisCode.MISSING_REFERENCED_TABLE,
                            owner, foreignKeyName, targetName,
                            "Referenced table is not present in the integrated input"));
                    continue;
                }

                List<String> missingColumns = foreignKey.referencedColumns().stream()
                        .filter(column -> target.findColumn(column.value()).isEmpty())
                        .map(Identifier::value)
                        .toList();
                if (!missingColumns.isEmpty()) {
                    issues.add(issue(ForeignKeyAnalysisSeverity.ERROR,
                            ForeignKeyAnalysisCode.MISSING_REFERENCED_COLUMN,
                            owner, foreignKeyName, targetName,
                            "Referenced columns are missing: " + String.join(", ", missingColumns)));
                    continue;
                }

                if (!isCanonicalUniqueTarget(target, foreignKey.referencedColumns())) {
                    issues.add(issue(ForeignKeyAnalysisSeverity.ERROR,
                            ForeignKeyAnalysisCode.REFERENCED_COLUMNS_NOT_UNIQUE,
                            owner, foreignKeyName, targetName,
                            "Referenced columns do not match a canonical primary key or unique key"));
                    continue;
                }

                resolved++;
                graph.get(ownerKey).add(targetKey);
                if (ownerKey.equals(targetKey)) {
                    selfReferences++;
                    issues.add(issue(ForeignKeyAnalysisSeverity.INFO,
                            ForeignKeyAnalysisCode.SELF_REFERENCE,
                            owner, foreignKeyName, targetName,
                            "Self-referencing foreign key will be deployed after CREATE TABLE"));
                }
            }
        }

        List<Set<String>> cycles = stronglyConnectedCycles(graph);
        for (Set<String> cycle : cycles) {
            String members = String.join(" -> ", cycle);
            issues.add(new ForeignKeyAnalysisIssue(
                    ForeignKeyAnalysisSeverity.WARNING,
                    ForeignKeyAnalysisCode.CYCLIC_DEPENDENCY,
                    "", "", "", "Foreign-key dependency cycle: " + members));
        }

        return new ForeignKeyAnalysisResult(
                schema.tables().size(), foreignKeys, physical, logical, resolved,
                selfReferences, cycles.size(), issues);
    }

    private static QualifiedName resolveTargetName(Table owner, ForeignKey foreignKey) {
        QualifiedName referenced = foreignKey.referencedTable();
        if (referenced.schemaName().isPresent()) {
            return referenced;
        }
        String ownerSchema = owner.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        return QualifiedName.of(ownerSchema, referenced.name().value());
    }

    private static boolean isCanonicalUniqueTarget(Table table, List<Identifier> referencedColumns) {
        List<String> expected = normalizedColumns(referencedColumns);
        if (table.primaryKey().map(key -> normalizedColumns(key.columns()).equals(expected)).orElse(false)) {
            return true;
        }
        for (UniqueKey uniqueKey : table.uniqueKeys()) {
            if (normalizedColumns(uniqueKey.columns()).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizedColumns(List<Identifier> columns) {
        return columns.stream().map(Identifier::normalized).toList();
    }

    private static ForeignKeyAnalysisIssue issue(
            ForeignKeyAnalysisSeverity severity,
            ForeignKeyAnalysisCode code,
            Table table,
            String foreignKey,
            QualifiedName referencedTable,
            String message) {
        return new ForeignKeyAnalysisIssue(
                severity, code, table.qualifiedName().toString(), foreignKey,
                referencedTable.toString(), message);
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }

    private static List<Set<String>> stronglyConnectedCycles(Map<String, Set<String>> graph) {
        Tarjan tarjan = new Tarjan(graph);
        return tarjan.components().stream()
                .filter(component -> component.size() > 1)
                .toList();
    }

    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<Set<String>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private List<Set<String>> components() {
            for (String vertex : graph.keySet()) {
                if (!index.containsKey(vertex)) {
                    visit(vertex);
                }
            }
            return List.copyOf(components);
        }

        private void visit(String vertex) {
            index.put(vertex, nextIndex);
            lowLink.put(vertex, nextIndex);
            nextIndex++;
            stack.push(vertex);
            onStack.add(vertex);

            for (String target : graph.getOrDefault(vertex, Set.of())) {
                if (!index.containsKey(target)) {
                    visit(target);
                    lowLink.put(vertex, Math.min(lowLink.get(vertex), lowLink.get(target)));
                } else if (onStack.contains(target)) {
                    lowLink.put(vertex, Math.min(lowLink.get(vertex), index.get(target)));
                }
            }

            if (Objects.equals(lowLink.get(vertex), index.get(vertex))) {
                Set<String> component = new LinkedHashSet<>();
                String current;
                do {
                    current = stack.pop();
                    onStack.remove(current);
                    component.add(current);
                } while (!current.equals(vertex));
                components.add(component);
            }
        }
    }
}
