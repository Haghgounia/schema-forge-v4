# Oracle parity qualification: ORA-P7 through ORA-P10

This qualification track brings Oracle to the same end-to-end evidence model already used for MariaDB, PostgreSQL and SQL Server without weakening SchemaForge No-Guess / No-Lossy behavior.

## ORA-P7 - dependency-closed integrated cohort

`CanonicalJsonOracleClosedSubsetP7IT` reuses the existing selection manifest and auto-selected canonical snapshots. It removes only tables that cannot be rendered safely for Oracle or whose owned physical foreign keys prevent a dependency-closed cohort. It emits:

- a closed-selected CSV;
- an exclusions CSV with exact reasons;
- closure-iteration evidence;
- contract mismatch evidence;
- one integrated Oracle script.

No database connection or mutation occurs in ORA-P7.

ORA-P7 preflights every selected table across the same executable phases used by the integrated renderer: CREATE, table-local objects, and Phase-4 metadata/comments/grants. A table whose documentation cannot be represented losslessly (for example an unsupported C0 control character such as U+001F) is recorded as a local render exclusion before FK dependency closure. The text is not sanitized or rewritten.

## ORA-P8 - persistent integrated deployment

`OracleIntegratedPersistentDeploymentP8IT` executes the exact ORA-P7 integrated script against a disposable Oracle user/schema. Destructive cleanup is restricted to objects declared by the integrated script and requires explicit confirmation. A successful deployment is intentionally left in place for ORA-P9.

Safety invariants:

- connected Oracle user must equal the configured expected schema;
- destructive cleanup requires `confirmDestructive=true`;
- cleanup refuses objects owned by another schema;
- every executable statement must succeed.

## ORA-P9 - persistent catalog convergence

`OraclePersistentCatalogConvergenceP9IT` reloads the exact ORA-P7 canonical selection, rereads the persistent Oracle catalog through `JdbcOracleMetadataRepository`, and runs `SchemaDiffEngine` table by table. The gate is read-only and emits residual column/object drift plus missing/extra table evidence.

The qualification target is residual column/object drift = 0.

## ORA-P10 - external DBA-created audit

`OracleExternalDatabaseAuditP10IT` creates three fixture tables directly through JDBC, bypassing SchemaForge DDL generation, then audits them through production Oracle metadata and `SchemaConformanceAuditService`.

The test requires a dedicated empty Oracle user/schema beginning with `SFORGE_P10_`. It proves:

- direct-JDBC tables are discoverable;
- a valid physical FK is materialized and reread;
- missing-PK and datatype warning rules execute;
- valid FK integrity has no semantic error;
- audit does not mutate the database;
- cleanup succeeds when requested.

Legacy malformed/documentation-only FKs remain non-blocking evidence and are never repaired by guessed constraints.
