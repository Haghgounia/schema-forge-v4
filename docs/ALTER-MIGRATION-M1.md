# ALTER / Flyway-compatible migration generation — M1

M1 introduces the migration foundation for tables that already exist in a target database while a newer design document describes the desired state.

## Flow

1. Parse the new design document into the existing canonical `Table` model.
2. Read the current table through `MetadataRepository.findTable(schema, table)`.
3. `SchemaDiffEngine` compares live and desired column state.
4. `MigrationSqlRenderer` renders DBMS-specific `ALTER TABLE` SQL.
5. `FlywayMigrationNamer` creates a versioned file such as `V20260821220506000__APP_CUSTOMER_ALTER.sql`.
6. `MigrationFileWriter` refuses to overwrite an existing versioned migration.

## M1 column coverage

- add column
- drop column
- datatype change
- nullability change
- default change
- identity/generated-expression differences are reported for review and intentionally not auto-mutated in-place in M1

M1 never infers a rename. If `OLD_NAME` exists only in live metadata and `NEW_NAME` exists only in the desired document, M1 reports a destructive drop plus an additive column until an explicit rename/evidence rule is introduced.

## Risk policy

- `SAFE`: additive nullable columns and proven widening changes.
- `REVIEW`: changes that can fail against existing data or alter runtime semantics, including NOT NULL/default/type-family changes.
- `DESTRUCTIVE`: drop columns and proven narrowing changes.

Destructive SQL is rendered as comments by default. It becomes executable only when `MigrationRenderOptions(confirmDestructive=true)` is supplied.

Every generated SQL file contains its risk summary and DBA hints in the SQL itself.

## Platforms

M1 renders column ALTER syntax for:

- Oracle
- PostgreSQL
- Db2 for z/OS
- Microsoft SQL Server
- MySQL

MySQL now also has a JDBC metadata repository for live table/column comparison. Enable it with `schemaforge.metadata.mysql.enabled=true` (or `SCHEMAFORGE_METADATA_MYSQL_ENABLED=true`) and configure URL/user/password through the corresponding environment-backed properties.

## Deliberate M1 boundary

PK, FK, UK, CHECK and INDEX diff/dependency ordering is M2. M1 explicitly writes that boundary into generated SQL so a DBA does not mistake a column-only migration for a complete constraint migration.

## M1.1 output contract: CREATE is never replaced

SchemaForge treats full CREATE DDL and incremental migration SQL as two independent artifacts.

- The normal `.<platform>.sql` CREATE script is always generated from the desired document/canonical model, even when the table already exists in the target database.
- If an exact live table is found and the live-vs-desired column diff is non-empty, SchemaForge additionally writes a Flyway migration under `<platform>/migrations/`.
- If the live table does not exist, only the normal CREATE script is produced.
- If the live table already matches the desired M1 column state, no empty Flyway migration is written.
- Migration discovery or rendering never acts as a substitute for CREATE generation.

Flyway versions use `yyyyMMddHHmmssSSS` and a monotonic in-process guard so multiple table migrations produced in one request do not reuse the same Flyway version.
## M1.2 MySQL default-expression hardening

For MySQL, a legacy Word identity column may still carry its Oracle-style `SEQ_*.NEXTVAL` default in the canonical model. The ordinary MySQL CREATE path already translates the logical identity intent to `AUTO_INCREMENT`; migration diffing now uses the same effective semantics and does not report or map that NEXTVAL as a MySQL default.

If a non-identity default expression cannot be mapped by a target dialect, migration generation does not terminate the full artifact request. The change remains `REVIEW`, automatic ALTER SQL is blocked/commented with the mapper reason, and the ordinary CREATE artifact remains independent. No unsupported expression is silently rewritten.

