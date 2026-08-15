# SchemaForge Mermaid Diagram Export - Phase 1

This extension renders the existing DBMS-neutral canonical `Table` model as Mermaid text. It does not change the Legacy Word parser, canonical snapshot format, DDL generators, dialects, FK analyzer, or database execution path.

## Supported diagram types

- `ER` - Mermaid `erDiagram` with canonical columns, data types, PK/FK markers and FK relationships.
- `DEPENDENCY` - Mermaid `flowchart LR` where the child table points to the referenced parent table.

## Supported scopes

- `ALL`
- `SCHEMA`
- `TABLE`
- `TABLE_WITH_DEPENDENCIES`
- `SELECTED_TABLES`

`TABLE_WITH_DEPENDENCIES` traverses both outgoing and incoming FK relationships and is bounded by `dependencyDepth`. This makes it suitable for large schemas where one diagram containing every table would be unusable.

## Example

```java
DiagramExportOptions options = DiagramExportOptions.builder()
        .type(DiagramType.DEPENDENCY)
        .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
        .rootTable("TSTSHMA", "CTACCOUNTS")
        .dependencyDepth(2)
        .build();

Path output = Path.of("output/diagrams/CTACCOUNTS-dependencies.mmd");
new MermaidDiagramFileWriter().write(output, tables, options);
```

## FK behavior

Physical foreign keys are included by default. Logical foreign keys are excluded unless `includeLogicalForeignKeys(true)` is set. Logical references use dashed arrows in dependency diagrams.

An unqualified referenced table is resolved against the child table schema. A referenced table missing from the supplied catalog is not invented; the exporter writes a Mermaid comment such as:

```text
%% unresolved FK: TSTSHMA.CHILD.FK_CHILD_PARENT -> PARENT
```

Duplicate qualified table input is rejected with `INPUT_DUPLICATE_TABLE`, preserving the production one-version-per-table rule.

## Identifier safety

Mermaid node/entity identifiers are generated independently from SQL identifiers and are prefixed with `SF_`. Schema-qualified source names remain visible in comments or node labels. Column identifiers that contain Mermaid-unsafe characters are sanitized only in the diagram token and retain the original value in an attribute comment.

## Current boundary

Phase 1 intentionally generates textual `.mmd` files only. Rendering to SVG/PNG/PDF is left to Mermaid-compatible external tooling. PlantUML and Graphviz can be added later behind the existing `DiagramExporter` abstraction without changing the canonical model.
