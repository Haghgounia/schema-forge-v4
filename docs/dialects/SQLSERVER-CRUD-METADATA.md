# SQL Server metadata-based CRUD procedures

SchemaForge can read one existing Microsoft SQL Server table from the `sys.*` catalog views and generate five stored procedures. Word and Enterprise Architect input are not used by this endpoint.

## REST endpoint

```http
POST /api/v1/generate/sqlserver/crud
Content-Type: application/json
```

```json
{
  "schema": "BIM",
  "table": "PROVINCES"
}
```

The response file is:

```text
BIM.PROVINCES.sqlserver.crud-procedures.sql
```

## Generated procedures

```text
BIM.PROVINCES_CREATE
BIM.PROVINCES_UPDATE
BIM.PROVINCES_DELETE
BIM.PROVINCES_GET_BY_ID
BIM.PROVINCES_SEARCH
```

The generator uses `CREATE OR ALTER PROCEDURE` and separates procedures with `GO`. Execute the file with SSMS, sqlcmd, Azure Data Studio, DBeaver SQL Server script execution, or another client that recognizes SQL Server batches.

## Generation rules

- A primary key is required.
- SQL parameter types are derived from live SQL Server column metadata.
- Identity, sequence-default, `NEWID()` and `NEWSEQUENTIALID()` primary-key columns are omitted from the insert list.
- Generated keys are captured with `OUTPUT INSERTED` and returned through output parameters.
- Computed and rowversion columns are never insert or update parameters.
- Created/modified timestamp columns use `SYSDATETIME()`.
- Created/modified user columns use the corresponding actor parameter.
- Search filters are derived from primary-key, unique-key and standard status columns.
- Search uses bounded `OFFSET ... FETCH` pagination.
- Duplicate, not-found, invalid-page and dependent-row conditions use SQL Server `THROW` numbers `50002`, `50001`, `50003` and `50004`.
- Procedures do not issue `BEGIN TRANSACTION`, `COMMIT` or `ROLLBACK`; transaction ownership remains with the caller.
- Configured write-role grantees receive `GRANT EXECUTE` on all five procedures.

## Metadata configuration

```yaml
schemaforge:
  metadata:
    sqlserver:
      enabled: true
      url: jdbc:sqlserver://localhost:1433;databaseName=SchemaForgeTest;encrypt=true;trustServerCertificate=true
      username: schemaforge_test
      password: ${SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD}
      driver-class-name: com.microsoft.sqlserver.jdbc.SQLServerDriver
```

Environment variables are also supported:

```text
SCHEMAFORGE_METADATA_SQLSERVER_ENABLED=true
SCHEMAFORGE_METADATA_SQLSERVER_URL=jdbc:sqlserver://localhost:1433;databaseName=SchemaForgeTest;encrypt=true;trustServerCertificate=true
SCHEMAFORGE_METADATA_SQLSERVER_USERNAME=schemaforge_test
SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD=<password>
```

## Live integration test

The `sqlserver-live` Maven profile runs both the DDL integration test and CRUD integration test against an approved disposable database:

```cmd
mvnw.cmd -Psqlserver-live verify ^
 "-Dschemaforge.sqlserver.execution.confirm=I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE" ^
 "-Dschemaforge.sqlserver.url=jdbc:sqlserver://localhost:1433;databaseName=SchemaForgeTest;encrypt=true;trustServerCertificate=true" ^
 "-Dschemaforge.sqlserver.user=schemaforge_test" ^
 "-Dschemaforge.sqlserver.password=<password>"
```

`SqlServerCrudLiveIT` creates a temporary schema/table/sequence, reads live metadata, generates and executes the five procedures, exercises create/update/get/search/delete, verifies caller-owned rollback, and removes all temporary objects.
