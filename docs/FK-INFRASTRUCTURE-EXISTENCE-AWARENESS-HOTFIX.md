# FK / Infrastructure Existence-Awareness Hotfix

Baseline: `schema-forge-v4-2026-09-14-1239.zip`.

## SQL Server per-table FK fail-closed

EA per-table SQL now uses the same `DialectForeignKeyCompatibilityValidator` contract as the
integrated run-all renderer. If an owned FK renders to incompatible SQL Server column types, the
owning DDL artifact is recorded as `BLOCKED` and no SQL file is emitted. The reason includes
`SQLSERVER_FK_TYPE_MISMATCH` and both rendered SQL types. Comparison/migration processing remains
independent.

Known motivating case: `VARCHAR(30)` referencing `VARCHAR(50)`. No coercion is performed.

## Oracle schema/tablespace existence awareness

The metadata contract now carries authoritative tablespace existence when the repository supports
it. Oracle reads `USER_TABLESPACES` read-only. DDL generation suppresses the optional Oracle
infrastructure template only when:

1. metadata is available,
2. the target schema is verified to exist, and
3. every table/index tablespace actually required by the generated table set is verified to exist.

If a tablespace is missing or existence is unknown, the existing commented DBA provisioning
guidance remains. No `CREATE USER` or `CREATE TABLESPACE` statement is executed automatically.

## No-Guess / No-Lossy

The hotfix does not change canonical datatypes, does not resize FK columns, and does not create
infrastructure resources automatically.
