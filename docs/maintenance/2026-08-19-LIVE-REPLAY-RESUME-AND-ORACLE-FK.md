# Live replay resume + Oracle FK validation

This maintenance patch adds no production behavior to Oracle/PostgreSQL/SQL Server DDL generation.

## PostgreSQL resume

Property:
`postgresql.sql.startFileNumber`

Use `5210` to resume the 2026-08-19 corpus run from the file where the JDBC connection was lost.

## SQL Server resume

Property:
`sqlserver.sql.startFileNumber`

Use `1273` to resume the 2026-08-19 corpus run from the file where the JDBC connection was lost.

## Oracle FK-only live validation

Class:
`OracleForeignKeyDirectoryExecutionIT`

The historical Oracle replay intentionally skipped foreign keys. The FK-only runner reads the same generated `.oracle.sql` corpus and validates foreign keys against the final replay state without recreating all 5,296 tables.

For duplicate historical table documents, only the FK set belonging to the final deterministic file-order definition is validated, matching the database state left by the historical replay. Missing referenced tables are reported as dependency skips, not syntax success. Each successfully added FK is dropped immediately after validation.
