# Enterprise Architect XML/XMI Import

## Configuration

EA XMI exports may omit the physical database schema. SchemaForge resolves the schema in this order:

1. REST multipart parameter `schema`, when supplied.
2. EA `schema`/`owner` tagged value, when present.
3. `schemaforge.ea.default-schema` from `application.yml`.

```yaml
schemaforge:
  ea:
    default-schema: FEE
```

The same value can be overridden with `SCHEMAFORGE_EA_DEFAULT_SCHEMA`.

## EA-to-canonical mapping

| EA XMI element | Canonical model |
|---|---|
| `UML:Class` with stereotype `table` | `Table` |
| `UML:Attribute` with stereotype `column` | `Column` |
| Column tagged values `type`, `length`, `precision`, `scale` | `DataType` |
| `lowerBound` | Nullable/required |
| Column `style`/alias | Column description |
| `UML:Operation` stereotype `PK` | `PrimaryKey` |
| `UML:Operation` stereotype `FK` + FK association | `ForeignKey` |
| `UML:Operation` stereotype `index` | `Index` |
| `UK`/`unique` operation | `UniqueKey` |
| `unique index`/`UI`/`UX` operation | Unique `Index` |
| `check`/`CK` operation with expression tagged value | `CheckConstraint` |

Operation input parameters define key/index column order. Composite keys and indexes are preserved.

## REST

```text
POST /api/v1/generate/ea-xml
multipart field: file
optional query parameter: schema
optional query parameter: includeAuditFields=true|false
optional query parameter: auditProfile=AUTO|CREATED_UPDATED|CREATED_LAST_MODIFIED
accepted extensions: .xml, .xmi
```

The response ZIP is organized per EA table:

```text
oracle/<SCHEMA>.<TABLE>.oracle.sql
postgresql/<schema>.<table>.postgresql.sql
comparison/oracle/<SCHEMA>.<TABLE>.oracle.xlsx
comparison/postgresql/<schema>.<table>.postgresql.xlsx
oracle/run_all.sql
postgresql/run_all.sql
model.json
manifest.json
```

`run_all.sql` lists table scripts in internal foreign-key dependency order. If the EA model contains a dependency cycle, the run-all header identifies the affected tables so the foreign keys can be reviewed before execution. Comparison workbooks are emitted only for tables visible through the configured database metadata connections.

EA imports performed through this REST endpoint may infer identity only for integer-compatible primary-key columns. Character, date and other non-integer-compatible primary keys are never converted to identity solely because they are primary keys. An inferred identity replaces any default or generated expression on that primary-key column and is emitted `NOT NULL`.

## Audit enrichment

EA generation uses the same request-level audit contract as Word and ZIP generation. When `includeAuditFields=false`, the parsed source columns are not audit-enriched. When enabled, `AUTO` detects `CREATED_AT/CREATED_BY/UPDATED_AT/UPDATED_BY` or `CREATED_DATE/CREATED_BY/LAST_MODIFIED_DATE/LAST_MODIFIED_BY` and completes only the detected family. Existing audit columns are preserved exactly; they are not overwritten by configured defaults. Mixed families in one table are rejected as `AUDIT_PROFILE_CONFLICT`.

## Table name, Persian name and description

SchemaForge treats these EA values as separate metadata fields:

- `UML:Class/@name` -> technical table name
- table tagged value `alias` -> Persian table name (`Table.persianName`)
- table tagged value `documentation`, `notes` or `description` -> full table description

For generated database table comments, SchemaForge uses `alias` first and falls back to the full description only when Alias is empty. The Excel `COMMENT_STATUS` comparison applies the same rule. For backward compatibility, when an older EA export contains only `alias`, that value is also used as the table description. When both `alias` and `documentation` exist, they remain separate.
