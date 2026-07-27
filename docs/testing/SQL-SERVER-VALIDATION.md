# Microsoft SQL Server validation

## Offline validation

`SqlServerOfflineDdlValidator` validates generated scripts without a database connection. It checks:

- supported SchemaForge statement families
- balanced parentheses, brackets, and string literals
- `DECIMAL`/`NUMERIC` precision from 1 through 38 and valid scale
- bounded `VARCHAR`/`VARBINARY` lengths up to 8000
- bounded `NVARCHAR`/`NCHAR` lengths up to 4000
- `TIME`, `DATETIME2`, and `DATETIMEOFFSET` precision from 0 through 7
- accidental Oracle, PostgreSQL, or Db2 syntax leakage

This static validation is intentionally conservative and does not replace execution on SQL Server.

## Read-only connection probe

`SqlServerConnectionProbeService` loads the configured driver, opens a JDBC connection, requests read-only mode, and reads:

- server name
- current database
- current principal default schema
- database and driver versions

It then prepares/executes read-only projections against all catalog views required by the metadata repository, including `sys.sequences`.

Example settings:

```text
URL      = jdbc:sqlserver://host:1433;databaseName=APPDB;encrypt=true;trustServerCertificate=true
USER     = schemaforge_reader
DRIVER   = com.microsoft.sqlserver.jdbc.SQLServerDriver
```

The principal should have enough metadata visibility to read the target objects. No DDL is executed by the probe.

## Recommended later live test

On a disposable SQL Server database:

1. Run `mvn clean test`.
2. Enable SQL Server metadata configuration.
3. Start the REST service.
4. Generate one Word artifact and confirm the `.sqlserver.xlsx` workbook appears.
5. Execute the generated `.sqlserver.sql` script in SQLCMD or SSMS.
6. Generate the artifact again and verify that the comparison sheets report `SAME` for supported objects.
