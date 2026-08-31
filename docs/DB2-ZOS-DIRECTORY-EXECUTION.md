# Db2 z/OS generated SQL directory execution

`Db2ZosDirectoryExecutionTest` recursively executes already-generated SchemaForge Db2 z/OS SQL files through IBM JCC/JDBC.

It does not read Legacy Word, regenerate Canonical JSON, or rewrite SQL.

## HISTORICAL mode

This is the default for the accepted 4,693-file Legacy/Canonical corpus. Each script is treated as independent historical replay evidence:

- drop the table/sequence before execution;
- execute local table/index/PK/UK/comment DDL;
- skip cross-table foreign keys by default;
- skip grants by default;
- clean created table/sequence after each file;
- continue after SQL errors unless `db2zos.sql.failOnErrors=true`;
- write per-file and per-statement evidence under `target/db2zos-sql-execution-report`.

## FULL mode

Use only for a coherent final-state directory. `FULL` mode does not default to destructive per-file cleanup and does not allow foreign keys to be skipped.

## Required live properties

- `db2zos.sql.root`
- `schemaforge.db2zos.url`
- `schemaforge.db2zos.user`
- `schemaforge.db2zos.execution.confirm=I_UNDERSTAND_DB2_DDL_MAY_COMMIT`
- `db2zos.sql.confirmDestructive=I_UNDERSTAND_DB2_ZOS_DDL_MAY_COMMIT_AND_DROP_TABLES` when drop/cleanup is enabled
- `db2zos.jcc.path` for Maven profile `db2zos-live`

Optional properties include `schemaforge.db2zos.password`, `db2zos.sql.expectedServer`, `db2zos.sql.startFileNumber`, `db2zos.sql.maxFiles`, `db2zos.sql.progressEveryFiles`, `db2zos.sql.statementTimeoutSeconds`, `db2zos.sql.failOnErrors`, and `db2zos.sql.fileSuffix`.
