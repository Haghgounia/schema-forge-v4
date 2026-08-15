package com.behsazan.schemaforge.diagram.graphviz;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramExporter;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Graphviz DOT exporter for canonical ER and dependency diagrams.
 *
 * <p>This exporter writes textual DOT only. It does not execute Graphviz or depend on a local
 * {@code dot} executable, which keeps schema generation portable across build and runtime
 * environments.</p>
 */
public final class GraphvizDiagramExporter implements DiagramExporter {

    @Override
    public String export(Collection<Table> tables, DiagramExportOptions options) {
        return export(tables, options, GraphvizRenderOptions.defaults());
    }

    /**
     * Exports Graphviz DOT with Graphviz-specific readability controls while preserving the
     * generic diagram scope/type contract.
     */
    public String export(
            Collection<Table> tables,
            DiagramExportOptions options,
            GraphvizRenderOptions renderOptions) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(renderOptions, "renderOptions must not be null");

        Map<String, Table> catalog = buildCatalog(tables);
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException("diagram input must contain at least one table");
        }

        LinkedHashSet<Table> selected = selectTables(catalog, options);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("diagram scope selected no tables");
        }

        if (options.type() == DiagramType.DEPENDENCY && !renderOptions.includeDisconnectedTables()) {
            selected = connectedTables(selected, catalog, options);
        }

        return options.type() == DiagramType.DEPENDENCY
                ? renderDependency(selected, catalog, options, renderOptions)
                : renderEr(selected, catalog, options);
    }

    /**
     * Renders a dependency graph with one Graphviz cluster per schema.
     *
     * <p>The input must already satisfy the one-definition-per-qualified-table production rule.</p>
     */
    public String exportClusteredDependency(
            Collection<Table> tables, boolean includeLogicalForeignKeys) {
        Objects.requireNonNull(tables, "tables must not be null");
        Map<String, Table> catalog = buildCatalog(tables);
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException("diagram input must contain at least one table");
        }
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.ALL)
                .includeLogicalForeignKeys(includeLogicalForeignKeys)
                .build();
        return renderDependency(
                orderedSet(catalog.values()),
                catalog,
                options,
                GraphvizRenderOptions.fullClustered());
    }

    private Map<String, Table> buildCatalog(Collection<Table> tables) {
        Map<String, Table> catalog = new LinkedHashMap<>();
        for (Table table : tables) {
            Objects.requireNonNull(table, "table must not be null");
            String key = key(table.qualifiedName());
            Table previous = catalog.putIfAbsent(key, table);
            if (previous != null) {
                throw new IllegalArgumentException("INPUT_DUPLICATE_TABLE: " + table.qualifiedName());
            }
        }
        return catalog;
    }

    private LinkedHashSet<Table> selectTables(
            Map<String, Table> catalog, DiagramExportOptions options) {
        return switch (options.scope()) {
            case ALL -> orderedSet(catalog.values());
            case SCHEMA -> orderedSet(catalog.values().stream()
                    .filter(table -> table.qualifiedName().schemaName()
                            .map(schema -> schema.normalized().equals(options.schema().normalized()))
                            .orElse(false))
                    .toList());
            case TABLE -> orderedSet(List.of(requireTable(catalog, options.rootTable())));
            case SELECTED_TABLES -> {
                List<Table> selected = new ArrayList<>();
                for (QualifiedName name : options.selectedTables()) {
                    selected.add(requireTable(catalog, name));
                }
                yield orderedSet(selected);
            }
            case TABLE_WITH_DEPENDENCIES -> dependencyClosure(catalog, options);
        };
    }

    private LinkedHashSet<Table> dependencyClosure(
            Map<String, Table> catalog, DiagramExportOptions options) {
        Table root = requireTable(catalog, options.rootTable());
        int depthLimit = options.dependencyDepth();

        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Table table : catalog.values()) {
            String source = key(table.qualifiedName());
            adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>());
            for (ForeignKey fk : eligibleForeignKeys(table, options)) {
                String target = referencedKey(table, fk);
                if (!catalog.containsKey(target)) {
                    continue;
                }
                adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
                adjacency.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
            }
        }

        LinkedHashSet<String> selectedKeys = new LinkedHashSet<>();
        Deque<NodeDepth> queue = new ArrayDeque<>();
        String rootKey = key(root.qualifiedName());
        selectedKeys.add(rootKey);
        queue.addLast(new NodeDepth(rootKey, 0));

        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= depthLimit) {
                continue;
            }
            for (String neighbour : adjacency.getOrDefault(current.key(), Set.of()).stream().sorted().toList()) {
                if (selectedKeys.add(neighbour)) {
                    queue.addLast(new NodeDepth(neighbour, current.depth() + 1));
                }
            }
        }

        return orderedSet(selectedKeys.stream().map(catalog::get).toList());
    }

    private String renderEr(
            Set<Table> selected, Map<String, Table> catalog, DiagramExportOptions options) {
        StringBuilder out = new StringBuilder();
        appendHeader(out, "SchemaForge_ER");
        out.append("  node [shape=plain];\n\n");

        for (Table table : selected) {
            out.append("  ").append(quote(table.qualifiedName().toString()))
                    .append(" [label=<\n")
                    .append("    <TABLE BORDER=\"0\" CELLBORDER=\"1\" CELLSPACING=\"0\" CELLPADDING=\"4\">\n")
                    .append("      <TR><TD COLSPAN=\"3\"><B>")
                    .append(html(table.qualifiedName().toString()))
                    .append("</B></TD></TR>\n");
            if (options.includeColumns()) {
                appendColumns(out, table, options);
            }
            out.append("    </TABLE>\n")
                    .append("  >];\n\n");
        }

        if (options.includeForeignKeys()) {
            appendUnresolvedComments(out, selected, catalog, options);
            appendEdges(out, selected, catalog, options, GraphvizRenderOptions.defaults());
        }
        out.append("}\n");
        return out.toString();
    }

    private void appendColumns(StringBuilder out, Table table, DiagramExportOptions options) {
        Set<String> primaryKeyColumns = options.includePrimaryKeys()
                ? table.primaryKey().map(pk -> pk.columns().stream()
                        .map(Identifier::normalized)
                        .collect(Collectors.toSet()))
                .orElseGet(Set::of)
                : Set.of();
        Set<String> foreignKeyColumns = options.includeForeignKeys()
                ? eligibleForeignKeys(table, options).stream()
                        .flatMap(fk -> fk.columns().stream())
                        .map(Identifier::normalized)
                        .collect(Collectors.toSet())
                : Set.of();

        List<Column> columns = table.columns().stream()
                .sorted(Comparator.comparing(column -> column.ordinalPosition() == null
                        ? Integer.MAX_VALUE : column.ordinalPosition()))
                .toList();

        for (Column column : columns) {
            List<String> roles = new ArrayList<>(2);
            if (primaryKeyColumns.contains(column.name().normalized())) {
                roles.add("PK");
            }
            if (foreignKeyColumns.contains(column.name().normalized())) {
                roles.add("FK");
            }
            String role = roles.isEmpty() ? "" : String.join(",", roles);
            String type = options.includeDataTypes() ? dataType(column.dataType()) : "";
            out.append("      <TR><TD ALIGN=\"LEFT\">")
                    .append(html(role))
                    .append("</TD><TD ALIGN=\"LEFT\">")
                    .append(html(column.name().value()))
                    .append("</TD><TD ALIGN=\"LEFT\">")
                    .append(html(type))
                    .append("</TD></TR>\n");
        }
    }

    private String renderDependency(
            Set<Table> selected,
            Map<String, Table> catalog,
            DiagramExportOptions options,
            GraphvizRenderOptions renderOptions) {
        boolean clustered = renderOptions.clusterBySchema();
        StringBuilder out = new StringBuilder();
        appendHeader(out, clustered ? "SchemaForge_Clustered_Dependency" : "SchemaForge_Dependency");
        out.append("  node [shape=box];\n\n");

        if (clustered) {
            appendClusteredNodes(out, selected);
        } else {
            for (Table table : selected) {
                appendDependencyNode(out, table, "  ");
            }
            out.append('\n');
        }

        if (options.includeForeignKeys()) {
            appendUnresolvedComments(out, selected, catalog, options);
            appendEdges(out, selected, catalog, options, renderOptions);
        }
        out.append("}\n");
        return out.toString();
    }

    private void appendClusteredNodes(StringBuilder out, Set<Table> selected) {
        Map<String, List<Table>> schemas = new TreeMap<>();
        for (Table table : selected) {
            String schema = table.qualifiedName().schemaName()
                    .map(Identifier::value)
                    .orElse("NO_SCHEMA");
            schemas.computeIfAbsent(schema, ignored -> new ArrayList<>()).add(table);
        }

        int clusterIndex = 0;
        for (Map.Entry<String, List<Table>> entry : schemas.entrySet()) {
            clusterIndex++;
            out.append("  subgraph cluster_")
                    .append(sanitizeId(entry.getKey()))
                    .append('_')
                    .append(clusterIndex)
                    .append(" {\n")
                    .append("    label=").append(quote(entry.getKey())).append(";\n")
                    .append("    style=rounded;\n");
            for (Table table : entry.getValue().stream().sorted(tableComparator()).toList()) {
                appendDependencyNode(out, table, "    ");
            }
            out.append("  }\n\n");
        }
    }

    private void appendDependencyNode(StringBuilder out, Table table, String indent) {
        out.append(indent)
                .append(quote(table.qualifiedName().toString()))
                .append(" [label=")
                .append(quote(table.qualifiedName().toString()))
                .append("];\n");
    }

    private void appendEdges(
            StringBuilder out,
            Set<Table> selected,
            Map<String, Table> catalog,
            DiagramExportOptions options,
            GraphvizRenderOptions renderOptions) {
        Set<String> selectedKeys = selected.stream()
                .map(table -> key(table.qualifiedName()))
                .collect(Collectors.toSet());

        List<Relation> relations = new ArrayList<>();
        for (Table child : selected) {
            for (ForeignKey fk : eligibleForeignKeys(child, options)) {
                String targetKey = referencedKey(child, fk);
                Table parent = catalog.get(targetKey);
                if (parent != null && selectedKeys.contains(targetKey)) {
                    relations.add(new Relation(child, parent, fk));
                }
            }
        }
        relations.sort(Comparator
                .comparing((Relation relation) -> key(relation.child().qualifiedName()))
                .thenComparing(relation -> key(relation.parent().qualifiedName()))
                .thenComparing(relation -> relationLabel(relation.foreignKey())));

        for (Relation relation : relations) {
            out.append("  ")
                    .append(quote(relation.child().qualifiedName().toString()))
                    .append(" -> ")
                    .append(quote(relation.parent().qualifiedName().toString()));

            List<String> attributes = new ArrayList<>(2);
            if (renderOptions.showFkLabels()) {
                attributes.add("label=" + quote(relationLabel(relation.foreignKey())));
            }
            if (!relation.foreignKey().physicalReference()) {
                attributes.add("style=dashed");
            }
            if (!attributes.isEmpty()) {
                out.append(" [").append(String.join(", ", attributes)).append(']');
            }
            out.append(";\n");
        }
    }

    private LinkedHashSet<Table> connectedTables(
            Set<Table> selected,
            Map<String, Table> catalog,
            DiagramExportOptions options) {
        Set<String> selectedKeys = selected.stream()
                .map(table -> key(table.qualifiedName()))
                .collect(Collectors.toSet());
        Set<String> connected = new LinkedHashSet<>();

        for (Table child : selected) {
            String childKey = key(child.qualifiedName());
            for (ForeignKey fk : eligibleForeignKeys(child, options)) {
                String targetKey = referencedKey(child, fk);
                if (catalog.containsKey(targetKey) && selectedKeys.contains(targetKey)) {
                    connected.add(childKey);
                    connected.add(targetKey);
                }
            }
        }

        return orderedSet(connected.stream().map(catalog::get).toList());
    }

    private void appendUnresolvedComments(
            StringBuilder out,
            Set<Table> selected,
            Map<String, Table> catalog,
            DiagramExportOptions options) {
        for (Table child : selected.stream().sorted(tableComparator()).toList()) {
            for (ForeignKey fk : eligibleForeignKeys(child, options).stream()
                    .sorted(Comparator.comparing(this::relationLabel))
                    .toList()) {
                String target = referencedKey(child, fk);
                if (!catalog.containsKey(target)) {
                    out.append("  // unresolved FK: ")
                            .append(child.qualifiedName())
                            .append('.')
                            .append(relationLabel(fk))
                            .append(" -> ")
                            .append(fk.referencedTable())
                            .append('\n');
                }
            }
        }
    }

    private List<ForeignKey> eligibleForeignKeys(Table table, DiagramExportOptions options) {
        return table.foreignKeys().stream()
                .filter(fk -> fk.physicalReference() || options.includeLogicalForeignKeys())
                .toList();
    }

    private Table requireTable(Map<String, Table> catalog, QualifiedName name) {
        Table table = catalog.get(key(name));
        if (table != null) {
            return table;
        }
        if (name.schemaName().isEmpty()) {
            List<Table> matches = catalog.values().stream()
                    .filter(candidate -> candidate.qualifiedName().name().normalized()
                            .equals(name.name().normalized()))
                    .toList();
            if (matches.size() == 1) {
                return matches.getFirst();
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("ambiguous unqualified diagram table: " + name);
            }
        }
        throw new IllegalArgumentException("diagram table not found: " + name);
    }

    private String referencedKey(Table child, ForeignKey fk) {
        QualifiedName referenced = fk.referencedTable();
        if (referenced.schemaName().isPresent()) {
            return key(referenced);
        }
        return child.qualifiedName().schemaName()
                .map(schema -> key(QualifiedName.of(schema.value(), referenced.name().value())))
                .orElseGet(() -> key(referenced));
    }

    private String relationLabel(ForeignKey fk) {
        if (fk.name() != null) {
            return fk.name().value();
        }
        return fk.columns().stream().map(Identifier::value).collect(Collectors.joining(","));
    }

    private LinkedHashSet<Table> orderedSet(Collection<Table> values) {
        return values.stream()
                .sorted(tableComparator())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Comparator<Table> tableComparator() {
        return Comparator.comparing(table -> key(table.qualifiedName()));
    }

    private static void appendHeader(StringBuilder out, String name) {
        out.append("digraph ").append(name).append(" {\n")
                .append("  graph [rankdir=LR, overlap=false, splines=polyline];\n")
                .append("  edge [fontsize=10];\n");
    }

    private static String dataType(DataType type) {
        StringBuilder value = new StringBuilder(type.name().value());
        if (type.length() != null) {
            value.append('(').append(type.length());
            if (type.lengthSemantics() != null && !"DEFAULT".equals(type.lengthSemantics().name())) {
                value.append(' ').append(type.lengthSemantics().name());
            }
            value.append(')');
        } else if (type.precision() != null) {
            value.append('(').append(type.precision());
            if (type.scale() != null) {
                value.append(',').append(type.scale());
            }
            value.append(')');
        }
        return value.toString();
    }

    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ") + "\"";
    }

    private static String html(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String sanitizeId(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isEmpty() || !Character.isLetter(sanitized.charAt(0))) {
            return "SF_" + sanitized;
        }
        return sanitized;
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }

    private record NodeDepth(String key, int depth) { }
    private record Relation(Table child, Table parent, ForeignKey foreignKey) { }
}
