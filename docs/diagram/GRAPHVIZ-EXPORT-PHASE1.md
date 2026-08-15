# Graphviz DOT Export - Phase 1

SchemaForge V4 adds Graphviz as a second textual diagram target beside the frozen Mermaid exporter.

## Scope

Phase 1 generates DOT only. SchemaForge does not install or execute `dot`, `sfdp`, or any other Graphviz process.

Generated ZIP layout:

```text
graphviz/
  tables/
    <document>_<timestamp>.graphviz.dot
  batch/
    schema-dependency.dot
    schema-clustered.dot
    issues.csv
    summary.txt
```

Per-document DOT is generated from the same prepared canonical schema used by SQL and Mermaid generation. No source document is reparsed.

Batch dependency output follows the existing production duplicate policy:

- a qualified table name occurring exactly once is eligible for the graph;
- every definition of a duplicate qualified table is excluded;
- `INPUT_DUPLICATE_TABLE` is reported;
- foreign keys targeting an excluded duplicate are reported as `INPUT_DUPLICATE_TABLE_TARGET`;
- targets absent from the unique batch are reported as `MISSING_REFERENCED_TABLE`;
- no historical version is selected automatically.

`schema-dependency.dot` is a flat directed FK graph. `schema-clustered.dot` groups nodes by schema using Graphviz clusters while preserving the same FK edges.

## Optional external rendering

The generated DOT files can be rendered outside SchemaForge, for example:

```bat
dot -Tsvg schema-dependency.dot -o schema-dependency.svg
dot -Tpng schema-clustered.dot -o schema-clustered.png
```

External rendering is deliberately outside the Phase-1 Java runtime.
