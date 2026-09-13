# SQL Server SS-P10 External Database Audit Qualification

SS-P10 is the final SQL Server parity qualification gate against the MariaDB/PostgreSQL reference level.

The test creates an isolated schema and three tables directly with JDBC, outside SchemaForge generation:

- `SSP10_PARENT`: primary key plus unique key.
- `SSP10_CHILD`: primary key plus one valid FK to `SSP10_PARENT`.
- `SSP10_NO_PK`: deliberately has no primary key so the audit has a real finding to report.

The production `JdbcSqlServerMetadataRepository` reads the live objects and `SchemaConformanceAuditService` audits both the child table and the complete schema. The audit path is read-only. Before/after table sets must match, the valid FK must remain materialized, and the missing-PK finding must be detected.

The fixture schema name is safety-gated to the prefix `SFORGE_SSP10_` and destructive setup/cleanup requires explicit confirmation.

Suggested live invocation:

```cmd
set "SQLSERVER_JDBC_PASSWORD=<password>"

mvnw.cmd ^
  -Dtest=SqlServerExternalDatabaseAuditP10IT ^
  -Dschemaforge.sqlserver.p10.jdbc.url="jdbc:sqlserver://localhost:1433;databaseName=master;encrypt=true;trustServerCertificate=true" ^
  -Dschemaforge.sqlserver.p10.jdbc.user=sa ^
  -Dschemaforge.sqlserver.p10.audit.schema=SFORGE_SSP10_AUDIT ^
  -Dschemaforge.sqlserver.p10.audit.outputDir="D:\Sample-Docs-Scripts\SchemaForge-SqlServer-P10-Audit" ^
  -Dschemaforge.sqlserver.p10.audit.confirmDestructive=true ^
  -Dschemaforge.sqlserver.p10.audit.cleanup=true ^
  test
```

Qualification target:

- three direct-JDBC tables visible before audit,
- table set unchanged after audit,
- one valid live FK visible,
- table and schema audit executed,
- missing PK detected,
- datatype rule family executed,
- zero FK integrity errors,
- no audit mutation,
- cleanup succeeds,
- Maven build succeeds.
