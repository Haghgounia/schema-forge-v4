# Microsoft SQL Server validation

SQL Server validation is intentionally staged. Normal builds remain offline and database-independent. Live execution is enabled only through an explicit confirmation value and must target an approved disposable database.

## 1. Offline validation

`SqlServerOfflineDdlValidator` validates generated scripts without a database connection. It checks:

- supported SchemaForge statement families
- balanced parentheses, brackets, and string literals
- `DECIMAL`/`NUMERIC` precision from 1 through 38 and valid scale
- bounded `VARCHAR`/`VARBINARY` lengths up to 8000
- bounded `NVARCHAR`/`NCHAR` lengths up to 4000
- `TIME`, `DATETIME2`, and `DATETIMEOFFSET` precision from 0 through 7
- accidental Oracle, PostgreSQL, or Db2 syntax leakage

This static validation is deterministic and conservative. It does not replace execution on the target SQL Server version.

## 2. Validation runner modes

`SqlServerValidationRunner` supports three explicit modes.

### Generate

Generates SQL Server DDL for every `.docx` file in the input directory, runs the offline preflight, and writes a timestamped CSV report.

```text
SqlServerValidationRunner generate <input-directory> <output-directory>
```

Output report:

```text
sqlserver-offline-validation-report_<timestamp>.csv
```

### Probe

Loads the Microsoft JDBC driver, opens a read-only connection, and verifies:

- server name
- current database
- current principal default schema
- database and driver versions
- access to all `sys.*` catalog views required by the SQL Server metadata repository

```text
-Dschemaforge.sqlserver.url=jdbc:sqlserver://host:1433;databaseName=APPDB;encrypt=true;trustServerCertificate=true
-Dschemaforge.sqlserver.user=schemaforge_reader
-Dschemaforge.sqlserver.password=change-me
-Dschemaforge.sqlserver.driver=com.microsoft.sqlserver.jdbc.SQLServerDriver

SqlServerValidationRunner probe
```

Equivalent environment variables are supported:

```text
SCHEMAFORGE_SQLSERVER_URL
SCHEMAFORGE_SQLSERVER_USERNAME
SCHEMAFORGE_SQLSERVER_PASSWORD
SCHEMAFORGE_SQLSERVER_DRIVER
```

No DDL is executed by the probe.

### Execute

Generates and validates each document, then executes only scripts whose canonical specification and offline preflight are valid.

Live execution is blocked unless this exact confirmation value is supplied:

```text
SCHEMAFORGE_SQLSERVER_EXECUTION_CONFIRM=I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE
```

or:

```text
-Dschemaforge.sqlserver.execution.confirm=I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE
```

Invocation:

```text
SqlServerValidationRunner execute <input-directory> <output-directory>
```

Execution output:

```text
sqlserver-execution-validation-report_<timestamp>.csv
```

The runner does not automatically remove objects created from input documents. Therefore `execute` must be used only with an approved disposable database or schema.

## 3. Explicit live integration test

`SqlServerLiveIT` is excluded from normal Surefire discovery by its `*IT` suffix. The `sqlserver-live` Maven profile runs it through Maven Failsafe.

The test performs this lifecycle:

1. verifies the read-only connection and catalog probe
2. creates a uniquely named disposable schema
3. generates SQL Server DDL from an in-memory canonical model
4. runs the SQL Server offline validator
5. executes the generated sequence, tables, constraints, indexes, and descriptions
6. verifies tables, columns, primary keys, foreign keys, index, sequence, and extended properties through `sys.*`
7. reads the tables through `JdbcSqlServerMetadataRepository`
8. generates SQL Server comparison workbooks and requires supported rows and objects to report `SAME`
9. drops the disposable tables, sequence, and schema in a `finally` block

Run:

```text
mvn -Psqlserver-live verify \
  -Dschemaforge.sqlserver.execution.confirm=I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE \
  -Dschemaforge.sqlserver.url="jdbc:sqlserver://localhost:1433;databaseName=SchemaForgeTest;encrypt=true;trustServerCertificate=true" \
  -Dschemaforge.sqlserver.user=sa \
  -Dschemaforge.sqlserver.password=change-me
```

Optional schema prefix:

```text
-Dschemaforge.sqlserver.test.schema-prefix=SFV
```

The test principal needs permission to create and drop a schema and to create, inspect, and drop the disposable objects inside that schema.

## 4. Recommended validation sequence

Use the following order for a new SQL Server environment:

1. `mvn clean test`
2. run `SqlServerValidationRunner generate`
3. run `SqlServerValidationRunner probe`
4. run `mvn -Psqlserver-live verify` against the disposable validation database
5. run `SqlServerValidationRunner execute` only for approved document-level execution testing

Do not run the live profile or execute mode against production.
