# ALTER/Migration M2 Oracle live pilot

`OracleMigrationM2LivePilotIT` is an opt-in destructive integration test for the Oracle M2 migration path.
It runs only when dedicated Oracle migration JDBC properties are supplied.

The pilot deliberately uses two fixed tables, `SF_M2_PARENT` and `SF_M2_CHILD`, inside the connected test schema.
For safety, the configured schema must equal the connected Oracle user and `SYS`/`SYSTEM` are refused.
The test never creates or drops a schema/user.

The old live child table is intentionally different from the desired model in:

- column type, nullability, default, add and drop,
- primary key,
- foreign key and delete action,
- unique key,
- check constraint,
- standalone indexes.

The pilot proves that full CREATE DDL is still generated even though the table already exists. It then writes a
safe Flyway-compatible migration with destructive statements commented, renders the same plan with explicit
destructive confirmation, executes it on Oracle, reads the live table again through `JdbcOracleMetadataRepository`,
and requires an empty residual diff. A seeded data row must survive the migration.

Oracle PK/UK backing indexes are represented as physical implementation of those constraints, not as independent
standalone indexes. `JdbcOracleMetadataRepository` therefore excludes indexes referenced by Oracle `P`/`U`
constraints from its standalone `indexes()` collection; their physical catalog data remains attached to the
`PrimaryKey`/`UniqueKey` objects.

Required properties:

```text
schemaforge.oracle.migration.jdbc.url
schemaforge.oracle.migration.jdbc.user
schemaforge.oracle.migration.confirmDestructive=true
```

Password may be supplied as `schemaforge.oracle.migration.jdbc.password` or `ORACLE_JDBC_PASSWORD`.
The schema defaults to the JDBC user and may be set explicitly with `schemaforge.oracle.migration.schema`.
Cleanup is enabled by default and drops only the two fixed `SF_M2_*` pilot tables in `finally`.

Artifacts are written under `target/oracle-migration-m2-live-pilot/` by default:

- `oracle-m2-pilot-create-reference.oracle.sql`
- `SAFE__V...__SF_M2_CHILD_ALTER.sql`
- confirmed `V...__SF_M2_CHILD_ALTER.sql`
- `oracle-m2-live-pilot-summary.txt`
- `residual-diff.txt` only when the post-migration diff is non-empty
