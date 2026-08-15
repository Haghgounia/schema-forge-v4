package com.behsazan.schemaforge.diagram.graphviz;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Produces batch-level Graphviz DOT diagrams without selecting between historical duplicates.
 *
 * <p>The duplicate and missing-target policy intentionally mirrors the Mermaid batch exporter:
 * all definitions of a duplicated qualified table are excluded from the graph and reported.</p>
 */
public final class GraphvizBatchDiagramExporter {
    private final GraphvizDiagramExporter exporter;

    public GraphvizBatchDiagramExporter() {
        this(new GraphvizDiagramExporter());
    }

    GraphvizBatchDiagramExporter(GraphvizDiagramExporter exporter) {
        this.exporter = Objects.requireNonNull(exporter);
    }

    public Result export(Collection<Table> tableDefinitions) {
        Objects.requireNonNull(tableDefinitions, "tableDefinitions must not be null");

        Map<String, List<Table>> grouped = new TreeMap<>();
        for (Table table : tableDefinitions) {
            Objects.requireNonNull(table, "table must not be null");
            grouped.computeIfAbsent(key(table.qualifiedName()), ignored -> new ArrayList<>()).add(table);
        }
        if (grouped.isEmpty()) {
            throw new IllegalArgumentException("batch diagram input must contain at least one table");
        }

        List<Issue> issues = new ArrayList<>();
        Map<String, Table> uniqueCatalog = new LinkedHashMap<>();
        Set<String> duplicateKeys = grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        for (Map.Entry<String, List<Table>> entry : grouped.entrySet()) {
            List<Table> definitions = entry.getValue();
            if (definitions.size() == 1) {
                uniqueCatalog.put(entry.getKey(), definitions.getFirst());
            } else {
                issues.add(new Issue(
                        "INPUT_DUPLICATE_TABLE",
                        entry.getKey(),
                        "",
                        definitions.size(),
                        "All duplicate definitions were excluded; no version was selected"));
            }
        }

        int physicalFks = 0;
        int resolvedPhysicalFks = 0;
        for (Table source : uniqueCatalog.values()) {
            for (ForeignKey fk : source.foreignKeys()) {
                if (!fk.physicalReference()) {
                    continue;
                }
                physicalFks++;
                String targetKey = referencedKey(source, fk);
                if (uniqueCatalog.containsKey(targetKey)) {
                    resolvedPhysicalFks++;
                } else if (duplicateKeys.contains(targetKey)) {
                    issues.add(new Issue(
                            "INPUT_DUPLICATE_TABLE_TARGET",
                            key(source.qualifiedName()),
                            targetKey,
                            grouped.get(targetKey).size(),
                            "Foreign-key target has duplicate definitions and was not selected"));
                } else {
                    issues.add(new Issue(
                            "MISSING_REFERENCED_TABLE",
                            key(source.qualifiedName()),
                            targetKey,
                            0,
                            "Foreign-key target is not present as a unique table in this batch"));
                }
            }
        }

        List<Table> exportedTables = uniqueCatalog.values().stream()
                .sorted(Comparator.comparing(table -> key(table.qualifiedName())))
                .toList();

        String dependency;
        String clustered;
        String compact;
        String overview;
        int connectedTables = connectedTableCount(exportedTables);
        if (exportedTables.isEmpty()) {
            dependency = "digraph SchemaForge_Dependency {\n  // no unique tables available for batch diagram\n}\n";
            clustered = "digraph SchemaForge_Clustered_Dependency {\n  // no unique tables available for batch diagram\n}\n";
            compact = "digraph SchemaForge_Clustered_Dependency {\n  // no connected unique tables available for compact batch diagram\n}\n";
            overview = "digraph SchemaForge_Clustered_Dependency {\n  // no connected unique tables available for overview batch diagram\n}\n";
        } else {
            DiagramExportOptions dependencyOptions = DiagramExportOptions.builder()
                    .type(DiagramType.DEPENDENCY)
                    .build();
            dependency = exporter.export(exportedTables, dependencyOptions);
            clustered = exporter.export(exportedTables, dependencyOptions, GraphvizRenderOptions.fullClustered());
            compact = exporter.export(exportedTables, dependencyOptions, GraphvizRenderOptions.compact());
            overview = exporter.export(exportedTables, dependencyOptions, GraphvizRenderOptions.overview());
        }

        issues.sort(Comparator
                .comparing(Issue::code)
                .thenComparing(Issue::sourceTable)
                .thenComparing(Issue::targetTable));

        return new Result(
                dependency,
                clustered,
                compact,
                overview,
                tableDefinitions.size(),
                grouped.size(),
                duplicateKeys.size(),
                exportedTables.size(),
                connectedTables,
                physicalFks,
                resolvedPhysicalFks,
                List.copyOf(issues));
    }

    private static int connectedTableCount(List<Table> exportedTables) {
        Set<String> catalog = exportedTables.stream()
                .map(table -> key(table.qualifiedName()))
                .collect(Collectors.toSet());
        Set<String> connected = new java.util.LinkedHashSet<>();
        for (Table source : exportedTables) {
            String sourceKey = key(source.qualifiedName());
            for (ForeignKey fk : source.foreignKeys()) {
                if (!fk.physicalReference()) {
                    continue;
                }
                String targetKey = referencedKey(source, fk);
                if (catalog.contains(targetKey)) {
                    connected.add(sourceKey);
                    connected.add(targetKey);
                }
            }
        }
        return connected.size();
    }

    private static String referencedKey(Table child, ForeignKey fk) {
        QualifiedName referenced = fk.referencedTable();
        if (referenced.schemaName().isPresent()) {
            return key(referenced);
        }
        return child.qualifiedName().schemaName()
                .map(schema -> key(QualifiedName.of(schema.value(), referenced.name().value())))
                .orElseGet(() -> key(referenced));
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }

    public record Issue(
            String code,
            String sourceTable,
            String targetTable,
            int occurrences,
            String detail) { }

    public record Result(
            String dependency,
            String clusteredDependency,
            String compactDependency,
            String overviewDependency,
            int tableDefinitions,
            int distinctTableNames,
            int duplicateTableNames,
            int exportedTables,
            int connectedTables,
            int physicalForeignKeys,
            int resolvedPhysicalForeignKeys,
            List<Issue> issues) {
        public Result {
            issues = List.copyOf(issues);
        }
    }
}
