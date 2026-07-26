# Step 10 - DBMS-aware SQL output naming

SQL output files now include the selected database platform while the canonical JSON output remains platform-neutral.

## Naming policy

- JSON: `<base>_yyyyMMdd_HHmmss_SSS.json`
- Oracle SQL: `<base>_yyyyMMdd_HHmmss_SSS.oracle.sql`
- PostgreSQL SQL: `<base>_yyyyMMdd_HHmmss_SSS.postgresql.sql`

The JSON and SQL files produced by one generation run share the same timestamp.

## Implementation

- `OutputFileNamer.create(...)` now requires `DatabasePlatform`.
- `SchemaGenerationService` passes the selected platform to the naming policy.
- Oracle regression and integration tests explicitly request `DatabasePlatform.ORACLE`.
- PostgreSQL integration tests verify the `.postgresql.sql` suffix.
