package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.diagram.ConceptualErdCardinality;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mermaid exporter for canonical ER and table-dependency diagrams.
 *
 * <p>Entity/node identifiers are generated independently from physical SQL identifiers so that
 * schema-qualified names and Mermaid reserved words cannot make the output ambiguous.</p>
 */
public final class MermaidDiagramExporter implements DiagramExporter {

    @Override
    public String export(Collection<Table> tables, DiagramExportOptions options) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(options, "options must not be null");

        Map<String, Table> catalog = buildCatalog(tables);
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException("diagram input must contain at least one table");
        }

        LinkedHashSet<Table> selected = selectTables(catalog, options);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("diagram scope selected no tables");
        }

        return switch (options.type()) {
            case DEPENDENCY -> renderDependency(selected, catalog, options);
            case CONCEPTUAL_ERD -> renderConceptualErd(selected, catalog, options);
            case ER -> renderEr(selected, catalog, options);
        };
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

    private LinkedHashSet<Table> selectTables(Map<String, Table> catalog, DiagramExportOptions options) {
        return switch (options.scope()) {
            case ALL -> orderedSet(catalog.values());
            case SCHEMA -> orderedSet(catalog.values().stream()
                    .filter(table -> table.qualifiedName().schemaName()
                            .map(schema -> schema.normalized().equals(options.schema().normalized()))
                            .orElse(false))
                    .toList());
            case TABLE -> {
                Table root = requireTable(catalog, options.rootTable());
                yield orderedSet(List.of(root));
            }
            case SELECTED_TABLES -> {
                List<Table> result = new ArrayList<>();
                for (QualifiedName selected : options.selectedTables()) {
                    result.add(requireTable(catalog, selected));
                }
                yield orderedSet(result);
            }
            case TABLE_WITH_DEPENDENCIES -> dependencyClosure(catalog, options);
        };
    }

    private LinkedHashSet<Table> dependencyClosure(Map<String, Table> catalog, DiagramExportOptions options) {
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
        queue.addLast(new NodeDepth(rootKey, 0));
        selectedKeys.add(rootKey);

        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= depthLimit) {
                continue;
            }
            List<String> neighbours = adjacency.getOrDefault(current.key(), Set.of()).stream()
                    .sorted()
                    .toList();
            for (String neighbour : neighbours) {
                if (selectedKeys.add(neighbour)) {
                    queue.addLast(new NodeDepth(neighbour, current.depth() + 1));
                }
            }
        }

        return orderedSet(selectedKeys.stream().map(catalog::get).toList());
    }

    private String renderEr(Set<Table> selected, Map<String, Table> catalog, DiagramExportOptions options) {
        StringBuilder out = new StringBuilder("erDiagram\n");
        Map<String, String> ids = entityIds(selected);

        for (Table table : selected) {
            String id = ids.get(key(table.qualifiedName()));
            out.append("    %% ").append(id).append(" = ").append(table.qualifiedName()).append('\n');
            out.append("    ").append(id).append(" {\n");
            if (options.includeColumns()) {
                appendColumns(out, table, options);
            }
            out.append("    }\n\n");
        }

        if (options.includeForeignKeys()) {
            appendUnresolvedReferenceComments(out, selected, catalog, options, "    ");
            List<Relation> relations = relations(selected, catalog, options);
            for (Relation relation : relations) {
                String parentId = ids.get(key(relation.parent().qualifiedName()));
                String childId = ids.get(key(relation.child().qualifiedName()));
                if (parentId == null || childId == null) {
                    continue;
                }
                out.append("    ")
                        .append(parentId)
                        .append(' ')
                        .append(parentCardinality(relation.child(), relation.foreignKey()))
                        .append("--o{ ")
                        .append(childId)
                        .append(" : \"")
                        .append(escapeLabel(relationLabel(relation.foreignKey())))
                        .append("\"\n");
            }
        }
        return out.toString();
    }

    private String renderConceptualErd(
            Set<Table> selected, Map<String, Table> catalog, DiagramExportOptions options) {
        StringBuilder out = new StringBuilder("erDiagram\n");
        Map<String, String> ids = entityIds(selected);

        for (Table table : selected) {
            String id = ids.get(key(table.qualifiedName()));
            out.append("    %% ").append(id).append(" = ").append(table.qualifiedName()).append('\n');
            out.append("    ").append(id).append(" {\n    }\n\n");
        }

        if (options.includeForeignKeys()) {
            appendUnresolvedReferenceComments(out, selected, catalog, options, "    ");
            for (Relation relation : relations(selected, catalog, options)) {
                String parentId = ids.get(key(relation.parent().qualifiedName()));
                String childId = ids.get(key(relation.child().qualifiedName()));
                if (parentId == null || childId == null) {
                    continue;
                }
                ConceptualErdCardinality cardinality = ConceptualErdCardinality.resolve(
                        relation.child(), relation.foreignKey());
                out.append("    ")
                        .append(parentId)
                        .append(' ')
                        .append(cardinality.parentEnd().mermaid())
                        .append("--")
                        .append(cardinality.childEnd().mermaid())
                        .append(' ')
                        .append(childId)
                        .append(" : \"")
                        .append(escapeLabel(relationLabel(relation.foreignKey())))
                        .append("\"\n");
            }
        }
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
                        ? Integer.MAX_VALUE
                        : column.ordinalPosition()))
                .toList();

        for (Column column : columns) {
            String type = options.includeDataTypes() ? mermaidType(column.dataType()) : "column";
            List<String> keys = new ArrayList<>(2);
            if (primaryKeyColumns.contains(column.name().normalized())) {
                keys.add("PK");
            }
            if (foreignKeyColumns.contains(column.name().normalized())) {
                keys.add("FK");
            }

            String sourceColumnName = column.name().value();
            String diagramColumnName = sanitizeToken(sourceColumnName);
            out.append("        ")
                    .append(type)
                    .append(' ')
                    .append(diagramColumnName);
            if (!keys.isEmpty()) {
                out.append(' ').append(String.join(", ", keys));
            }
            if (!diagramColumnName.equals(sourceColumnName)) {
                out.append(" \"").append(escapeLabel("source=" + sourceColumnName)).append("\"");
            }
            out.append('\n');
        }
    }

    private String renderDependency(Set<Table> selected, Map<String, Table> catalog, DiagramExportOptions options) {
        StringBuilder out = new StringBuilder("flowchart LR\n");
        Map<String, String> ids = entityIds(selected);
        for (Table table : selected) {
            String id = ids.get(key(table.qualifiedName()));
            out.append("    ")
                    .append(id)
                    .append("[\"")
                    .append(escapeLabel(table.qualifiedName().toString()))
                    .append("\"]\n");
        }

        if (options.includeForeignKeys()) {
            appendUnresolvedReferenceComments(out, selected, catalog, options, "    ");
            for (Relation relation : relations(selected, catalog, options)) {
                String childId = ids.get(key(relation.child().qualifiedName()));
                String parentId = ids.get(key(relation.parent().qualifiedName()));
                if (childId == null || parentId == null) {
                    continue;
                }
                out.append("    ")
                        .append(childId)
                        .append(relation.foreignKey().physicalReference() ? " -->|" : " -.->|")
                        .append(escapeLabel(relationLabel(relation.foreignKey())))
                        .append("| ")
                        .append(parentId)
                        .append('\n');
            }
        }
        return out.toString();
    }

    private List<Relation> relations(Set<Table> selected, Map<String, Table> catalog, DiagramExportOptions options) {
        Set<String> selectedKeys = selected.stream().map(table -> key(table.qualifiedName())).collect(Collectors.toSet());
        List<Relation> relations = new ArrayList<>();
        for (Table child : selected) {
            for (ForeignKey fk : eligibleForeignKeys(child, options)) {
                String target = referencedKey(child, fk);
                Table parent = catalog.get(target);
                if (selectedKeys.contains(target) && parent != null) {
                    relations.add(new Relation(child, parent, fk));
                }
            }
        }
        relations.sort(Comparator
                .comparing((Relation relation) -> key(relation.child().qualifiedName()))
                .thenComparing(relation -> key(relation.parent().qualifiedName()))
                .thenComparing(relation -> relationLabel(relation.foreignKey())));
        return relations;
    }

    private void appendUnresolvedReferenceComments(
            StringBuilder out,
            Set<Table> selected,
            Map<String, Table> catalog,
            DiagramExportOptions options,
            String indent) {
        for (Table child : selected) {
            for (ForeignKey fk : eligibleForeignKeys(child, options)) {
                String target = referencedKey(child, fk);
                if (!catalog.containsKey(target)) {
                    out.append(indent)
                            .append("%% unresolved FK: ")
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

    private String parentCardinality(Table child, ForeignKey fk) {
        boolean nullable = fk.columns().stream()
                .map(identifier -> child.findColumn(identifier.value()).orElse(null))
                .anyMatch(column -> column == null || column.nullable());
        return nullable ? "o|" : "||";
    }

    private String mermaidType(DataType dataType) {
        StringBuilder value = new StringBuilder(sanitizeToken(dataType.name().value()));
        if (dataType.length() != null) {
            value.append('_').append(dataType.length());
            if (dataType.lengthSemantics() != null && !"DEFAULT".equals(dataType.lengthSemantics().name())) {
                value.append('_').append(dataType.lengthSemantics().name());
            }
        } else if (dataType.precision() != null) {
            value.append('_').append(dataType.precision());
            if (dataType.scale() != null) {
                value.append('_').append(dataType.scale());
            }
        }
        return value.toString();
    }

    private Map<String, String> entityIds(Collection<Table> tables) {
        Map<String, String> ids = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Table table : tables.stream().sorted(tableComparator()).toList()) {
            String base = "SF_" + sanitizeToken(table.qualifiedName().toString()).toUpperCase(Locale.ROOT);
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate)) {
                candidate = base + '_' + suffix++;
            }
            ids.put(key(table.qualifiedName()), candidate);
        }
        return ids;
    }

    private LinkedHashSet<Table> orderedSet(Collection<Table> values) {
        return values.stream()
                .sorted(tableComparator())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Comparator<Table> tableComparator() {
        return Comparator.comparing(table -> key(table.qualifiedName()));
    }

    private Table requireTable(Map<String, Table> catalog, QualifiedName name) {
        Table table = catalog.get(key(name));
        if (table != null) {
            return table;
        }
        if (name.schemaName().isEmpty()) {
            List<Table> matches = catalog.values().stream()
                    .filter(candidate -> candidate.qualifiedName().name().normalized().equals(name.name().normalized()))
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

    private String sanitizeToken(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isEmpty() || !Character.isLetter(sanitized.charAt(0))) {
            sanitized = "X_" + sanitized;
        }
        return sanitized;
    }

    private String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }

    private String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }

    private record NodeDepth(String key, int depth) { }
    private record Relation(Table child, Table parent, ForeignKey foreignKey) { }
}
