package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

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

/** Test-only historical dependency coverage analysis for canonical JSON regression corpora. */
final class HistoricalDependencyCoverage {

    Result analyze(List<Definition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");

        Map<String, Integer> tableDefinitionsByName = new LinkedHashMap<>();
        Set<String> distinctTables = new LinkedHashSet<>();
        for (Definition definition : definitions) {
            for (Table table : definition.schema().tables()) {
                String owner = key(table.qualifiedName());
                distinctTables.add(owner);
                tableDefinitionsByName.merge(owner, 1, Integer::sum);
            }
        }

        int tableDefinitions = tableDefinitionsByName.values().stream().mapToInt(Integer::intValue).sum();
        int duplicateOccurrences = tableDefinitions - distinctTables.size();
        int foreignKeys = 0;
        int physicalForeignKeys = 0;
        int logicalForeignKeys = 0;
        int missingTargets = 0;

        Set<String> distinctPhysicalRelations = new LinkedHashSet<>();
        Set<DependencyEdge> dependencyEdges = new LinkedHashSet<>();
        List<SelfReference> selfReferences = new ArrayList<>();
        Set<String> distinctSelfReferenceRelations = new LinkedHashSet<>();
        List<MissingTarget> missingTargetEdges = new ArrayList<>();

        for (Definition definition : definitions) {
            for (Table table : definition.schema().tables()) {
                String owner = key(table.qualifiedName());
                for (ForeignKey foreignKey : table.foreignKeys()) {
                    foreignKeys++;
                    QualifiedName resolvedTarget = resolveTargetName(table, foreignKey);
                    String target = key(resolvedTarget);
                    String fkName = foreignKey.name() == null ? "" : foreignKey.name().value();
                    String signature = relationSignature(owner, foreignKey, target);

                    if (!foreignKey.physicalReference()) {
                        logicalForeignKeys++;
                        continue;
                    }
                    physicalForeignKeys++;
                    distinctPhysicalRelations.add(signature);

                    if (owner.equals(target)) {
                        selfReferences.add(new SelfReference(
                                definition.snapshot(), definition.source(), owner, fkName,
                                joinColumns(foreignKey.columns()), target,
                                joinColumns(foreignKey.referencedColumns())));
                        distinctSelfReferenceRelations.add(signature);
                    }

                    if (!distinctTables.contains(target)) {
                        missingTargets++;
                        missingTargetEdges.add(new MissingTarget(
                                definition.snapshot(), definition.source(), owner, fkName, target));
                        continue;
                    }
                    if (!owner.equals(target)) {
                        dependencyEdges.add(new DependencyEdge(owner, target));
                    }
                }
            }
        }

        Map<String, Set<String>> graph = new LinkedHashMap<>();
        distinctTables.forEach(table -> graph.put(table, new LinkedHashSet<>()));
        dependencyEdges.forEach(edge -> graph.get(edge.owner()).add(edge.target()));

        List<CycleGroup> cycles = stronglyConnectedCycles(graph).stream()
                .map(component -> new CycleGroup(component.stream().sorted().toList()))
                .sorted((left, right) -> String.join("|", left.members())
                        .compareTo(String.join("|", right.members())))
                .toList();
        int cycleTables = cycles.stream().mapToInt(cycle -> cycle.members().size()).sum();

        return new Result(
                definitions.size(), tableDefinitions, distinctTables.size(), duplicateOccurrences,
                foreignKeys, physicalForeignKeys, logicalForeignKeys,
                distinctPhysicalRelations.size(), dependencyEdges.size(), missingTargets,
                selfReferences.size(), distinctSelfReferenceRelations.size(),
                cycles.size(), cycleTables,
                List.copyOf(selfReferences), cycles, List.copyOf(dependencyEdges),
                List.copyOf(missingTargetEdges));
    }

    private static QualifiedName resolveTargetName(Table owner, ForeignKey foreignKey) {
        QualifiedName referenced = foreignKey.referencedTable();
        if (referenced.schemaName().isPresent()) {
            return referenced;
        }
        String ownerSchema = owner.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        return QualifiedName.of(ownerSchema, referenced.name().value());
    }

    private static String relationSignature(String owner, ForeignKey foreignKey, String target) {
        return owner + "(" + joinColumns(foreignKey.columns()).toUpperCase(Locale.ROOT) + ")->"
                + target + "(" + joinColumns(foreignKey.referencedColumns()).toUpperCase(Locale.ROOT) + ")";
    }

    private static String joinColumns(List<Identifier> columns) {
        return columns.stream().map(Identifier::value).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }

    private static List<Set<String>> stronglyConnectedCycles(Map<String, Set<String>> graph) {
        Tarjan tarjan = new Tarjan(graph);
        return tarjan.components().stream().filter(component -> component.size() > 1).toList();
    }

    record Definition(String snapshot, String source, DatabaseSchema schema) {
        Definition {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            source = source == null ? "" : source;
            Objects.requireNonNull(schema, "schema must not be null");
        }
    }

    record SelfReference(
            String snapshot,
            String source,
            String table,
            String foreignKey,
            String columns,
            String referencedTable,
            String referencedColumns) {
    }

    record MissingTarget(String snapshot, String source, String table, String foreignKey, String referencedTable) {
    }

    record DependencyEdge(String owner, String target) {
    }

    record CycleGroup(List<String> members) {
        CycleGroup {
            members = List.copyOf(members);
        }
    }

    record Result(
            int snapshotDefinitions,
            int tableDefinitions,
            int distinctTables,
            int duplicateOccurrences,
            int foreignKeys,
            int physicalForeignKeys,
            int logicalForeignKeys,
            int distinctPhysicalRelations,
            int aggregateDependencyEdges,
            int missingReferencedTableDefinitions,
            int selfReferenceDefinitions,
            int distinctSelfReferenceRelations,
            int aggregateCycleGroups,
            int aggregateCycleTables,
            List<SelfReference> selfReferences,
            List<CycleGroup> cycles,
            List<DependencyEdge> dependencyEdges,
            List<MissingTarget> missingTargets) {
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
