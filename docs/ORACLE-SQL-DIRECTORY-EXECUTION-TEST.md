# Oracle SQL Directory Execution Test

`OracleSqlDirectoryExecutionTest` recursively discovers and executes every `.sql` file below a directory. Execution continues after statement-level Oracle errors and produces a detailed CSV report.

## Safety

Use a dedicated disposable Oracle schema. The scripts contain DDL and Oracle commits DDL implicitly.

The optional `dropBeforeCreate` mode executes:

```sql
DROP TABLE <table> CASCADE CONSTRAINTS PURGE
```

before each script that contains `CREATE TABLE`. This mode is useful when the directory contains many historical versions of the same physical table. It is disabled by default and requires both explicit destructive confirmation and `oracle.sql.expectedSchema`. The test refuses to drop a qualified table outside that schema.

## Required properties

```text
oracle.sql.root
oracle.jdbc.url
oracle.jdbc.user
oracle.jdbc.password
```

The password can be supplied through `ORACLE_JDBC_PASSWORD` instead of a Maven command-line property.

## Recommended Windows execution

First extract the SQL ZIP into a directory. Then run:

```bat
set ORACLE_JDBC_PASSWORD=your_password

mvn -Dtest=OracleSqlDirectoryExecutionTest test ^
  -Doracle.sql.root="D:\OracleValidation\LegacyOracleSql-fixed2" ^
  -Doracle.jdbc.url="jdbc:oracle:thin:@//localhost:1521/FREEPDB1" ^
  -Doracle.jdbc.user=TSTSHMA ^
  -Doracle.sql.expectedSchema=TSTSHMA ^
  -Doracle.sql.executionMode=HISTORICAL ^
  -Doracle.sql.dropBeforeCreate=true ^
  -Doracle.sql.confirmDestructive=true ^
  -Doracle.sql.stopAfterCreateTableFailure=true ^
  -Doracle.sql.failOnErrors=false ^
  -Doracle.sql.statementTimeoutSeconds=60
```

In `HISTORICAL` mode, cross-table foreign keys and grants are skipped automatically. This validates each historical table version without dependency-order and missing-role noise. Use `FULL` only for a curated integrated schema containing one canonical version of each table:

```text
-Doracle.sql.executionMode=FULL
```

Additional statement types can still be skipped explicitly:

```text
-Doracle.sql.skipStatementTypes=GRANT,COMMENT,ALTER_FOREIGN_KEY
```

For an initial pilot run:

```text
-Doracle.sql.maxFiles=100
```

## Reports

A timestamped directory is created below:

```text
target/oracle-sql-execution-report
```

It contains:

- `oracle-sql-execution-errors.csv`: one row per Oracle/JDBC error, including file, statement, start line, ORA code, category and SQL excerpt.
- `oracle-sql-execution-files.csv`: status and execution counts per SQL file.
- `oracle-sql-execution-summary.txt`: totals and grouping by ORA code and category.

## Important options

| Property | Default | Meaning |
|---|---:|---|
| `oracle.sql.executionMode` | `HISTORICAL` | `HISTORICAL` skips cross-table FK and GRANT; `FULL` executes every statement. |
| `oracle.sql.stopAfterCreateTableFailure` | `true` | Stop the current file after its root `CREATE TABLE` failure and count remaining statements as skipped. |
| `oracle.sql.dropBeforeCreate` | `false` | Drop each table before executing its historical script version. |
| `oracle.sql.confirmDestructive` | `false` | Mandatory confirmation for destructive mode. |
| `oracle.sql.failOnErrors` | `false` | Fail JUnit after all reports are written when actionable errors exist. |
| `oracle.sql.ignoreErrorCodes` | empty | Comma-separated ORA codes retained in reports but excluded from actionable failures. |
| `oracle.sql.skipStatementTypes` | empty | Comma-separated types such as `GRANT,COMMENT`. |
| `oracle.sql.maxFiles` | `0` | Maximum files; zero means all. |
| `oracle.sql.statementTimeoutSeconds` | `60` | JDBC timeout per statement. |
| `oracle.sql.progressEveryFiles` | `100` | Console progress interval. |

## Historical versions

Executing all historical scripts in one unchanged schema produces duplicate-object errors such as `ORA-00955`. For syntax-oriented validation of every version, use `executionMode=HISTORICAL` with `dropBeforeCreate=true` in a disposable schema. This mode intentionally does not execute cross-table foreign keys or grants; those belong to a separate integrated-schema run using one canonical version of each logical table.
