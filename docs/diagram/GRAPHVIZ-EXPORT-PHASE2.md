# Graphviz DOT Export - Phase 2 Readability Profiles

Graphviz Phase 2 adds readability controls on top of the Phase-1 DOT exporter. It does not execute Graphviz binaries and does not alter the canonical model or any DDL generator.

## Render options

`GraphvizRenderOptions` exposes three independent controls:

- `includeDisconnectedTables`: when `false`, dependency diagrams retain only tables that participate in at least one resolved relationship inside the selected graph.
- `showFkLabels`: when `false`, FK names are omitted from rendered edges.
- `clusterBySchema`: when `true`, dependency nodes are placed inside Graphviz schema clusters.

The default options preserve Phase-1 behavior.

## Batch profiles

The normal ZIP pipeline now emits four dependency views:

```text
graphviz/batch/
  schema-dependency.dot   # flat, all unique tables, FK labels
  schema-clustered.dot    # FULL: all unique tables, FK labels, schema clusters
  schema-compact.dot      # connected tables only, FK labels, schema clusters
  schema-overview.dot     # connected tables only, no FK labels, schema clusters
  issues.csv
  summary.txt
```

The strict production duplicate policy remains unchanged: all duplicate definitions of a qualified table are excluded and never auto-selected. Missing or duplicate FK targets remain visible in `issues.csv`.

## External rendering

Examples:

```bat
dot -Tsvg schema-clustered.dot -o schema-clustered.svg
dot -Tsvg schema-compact.dot -o schema-compact.svg
dot -Tsvg schema-overview.dot -o schema-overview.svg
```

`schema-compact.dot` is intended for detailed dependency analysis with less visual noise. `schema-overview.dot` is intended for a higher-level architecture view where FK names would otherwise dominate the layout.
