# Enterprise Architect XML/XMI Import

## Configuration

EA XMI exports may omit the physical database schema. SchemaForge resolves the schema in this order:

1. EA `schema`/`owner` tagged value, when present.
2. `schemaforge.ea.default-schema` from `application.yml`.

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
