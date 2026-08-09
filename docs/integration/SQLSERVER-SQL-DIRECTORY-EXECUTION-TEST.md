# SQL Server recursive DDL execution test

`SqlServerDirectoryExecutionTest` executes generated `*.sqlserver.sql` files recursively through JDBC and writes durable CSV/text reports. It is an explicit integration test and stays skipped during ordinary builds unless the required SQL Server properties are provided.

## Recommended historical validation mode

Use only a disposable database/schema when `dropBeforeCreate=true`.

```bat
set SQLSERVER_JDBC_PASSWORD=your-password

mvnw.cmd -Dtest=SqlServerDirectoryExecutionTest test ^
  -Dsqlserver.sql.root="D:\get-git-doc-files-master\LegacyMultiDbSql-json\sqlserver" ^
  -Dsqlserver.jdbc.url="jdbc:sqlserver://localhost:1433;databaseName=schemaforge_validation;encrypt=true;trustServerCertificate=true" ^
  -Dsqlserver.jdbc.user=sa ^
  -Dsqlserver.sql.expectedSchema=TSTSHMA ^
  -Dsqlserver.sql.executionMode=HISTORICAL ^
  -Dsqlserver.sql.dropBeforeCreate=true ^
  -Dsqlserver.sql.confirmDestructive=true ^
  -Dsqlserver.sql.failOnErrors=false
```

In `HISTORICAL` mode, cross-table foreign keys and grants are skipped. Each qualified table may be dropped before its script is executed so historical versions can be validated independently.

## Reports

Reports are written below:

```text
target/sqlserver-sql-execution-report/<timestamp>/
```

Files:

```text
sqlserver-sql-execution-summary.txt
sqlserver-sql-execution-errors.csv
sqlserver-sql-execution-files.csv
```

The error CSV records both JDBC `SQLSTATE` and the SQL Server vendor error code. Common categories include syntax errors, invalid columns/objects, duplicate objects/indexes, FK target problems, type mismatches, permission problems, numeric overflow, and truncation.

## SQL Server batch separators

A standalone `GO` line is a client batch separator, not a T-SQL statement. The runner removes `GO`/`GO n` lines before sending statements through JDBC.

## Useful controls

```text
-Dsqlserver.sql.maxFiles=100
-Dsqlserver.sql.statementTimeoutSeconds=60
-Dsqlserver.sql.ignoreSqlStates=...
-Dsqlserver.sql.ignoreErrorCodes=...
-Dsqlserver.sql.skipStatementTypes=GRANT,ALTER_FOREIGN_KEY
```

`dropBeforeCreate=true` requires both `sqlserver.sql.confirmDestructive=true` and `sqlserver.sql.expectedSchema`. The runner refuses to drop an unqualified table or a table outside the expected schema.
