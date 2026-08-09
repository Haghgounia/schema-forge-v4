# PostgreSQL SQL Directory Execution Test

`PostgreSqlDirectoryExecutionTest` recursively executes generated `*.postgresql.sql` files through JDBC and records every PostgreSQL SQLSTATE without stopping the full run.

## Intended use

Use the test against a disposable PostgreSQL validation database. The default `HISTORICAL` mode validates historical table specifications independently: it skips cross-table foreign keys and grants. With `dropBeforeCreate=true`, each table is dropped before the corresponding historical script is executed.

Because multiple historical files can describe the same logical table, all 4,766 scripts cannot coexist as 4,766 distinct database objects. Historical mode executes all files; the final database contains the last successfully executed version of each repeated logical table.

## Required settings

```bat
set POSTGRESQL_JDBC_PASSWORD=<secret>

mvnw.cmd -Dtest=PostgreSqlDirectoryExecutionTest test ^
  -Dpostgresql.sql.root="D:\LegacyMultiDbSql\postgresql" ^
  -Dpostgresql.jdbc.url="jdbc:postgresql://localhost:5432/schemaforge_validation" ^
  -Dpostgresql.jdbc.user=sf_validation ^
  -Dpostgresql.sql.expectedSchema=tstshma ^
  -Dpostgresql.sql.executionMode=HISTORICAL ^
  -Dpostgresql.sql.dropBeforeCreate=true ^
  -Dpostgresql.sql.confirmDestructive=true ^
  -Dpostgresql.sql.failOnErrors=false
```

Do not place the password in source control. `POSTGRESQL_JDBC_PASSWORD` is preferred for local execution.

## Important behavior

- Recursively finds files ending in `.postgresql.sql`.
- Removes `psql` meta-commands such as `\encoding` and `\set` before JDBC execution.
- Uses JDBC auto-commit so one failed DDL statement does not leave the session in PostgreSQL's aborted-transaction state.
- Continues with the next file after SQL errors.
- Stops the remainder of one file after its `CREATE TABLE` fails by default, preventing cascaded comment/index errors.
- `HISTORICAL` mode skips `GRANT` and cross-table foreign keys.
- `FULL` mode executes all parsed SQL and is intended for a canonical one-version-per-table set.
- Destructive cleanup is allowed only when both `dropBeforeCreate=true` and `confirmDestructive=true` are set and the table owner matches `expectedSchema`.

## Reports

Each run creates a timestamped directory below:

```text
target/postgresql-sql-execution-report/
```

Files:

- `postgresql-sql-execution-summary.txt`
- `postgresql-sql-execution-errors.csv`
- `postgresql-sql-execution-files.csv`

The error report includes the source file, statement index, source line, statement type, object name, SQLSTATE, categorized error, message, and SQL excerpt.

## Useful optional settings

```text
-Dpostgresql.sql.maxFiles=100
-Dpostgresql.sql.progressEveryFiles=100
-Dpostgresql.sql.statementTimeoutSeconds=60
-Dpostgresql.sql.ignoreSqlStates=42704,42P01
-Dpostgresql.sql.skipStatementTypes=GRANT
-Dpostgresql.sql.fileSuffix=.postgresql.sql
```

Use ignored SQLSTATEs only for known environmental noise; do not hide syntax or datatype failures during parser/dialect validation.
