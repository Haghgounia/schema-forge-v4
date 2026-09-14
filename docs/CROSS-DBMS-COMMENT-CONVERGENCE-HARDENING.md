# Cross-DBMS Comment Convergence Hardening

Date: 2026-09-14
Baseline: CROSSDB-description-migration-hotfix

## Scope

This hotfix completes comment/description handling across Oracle, PostgreSQL, SQL Server, MySQL, MariaDB, DB2 LUW and DB2 z/OS.

## Behaviour

- Canonical table `description` is the database table comment. `persianName` is used only when description is empty.
- Table and column comment changes participate in CREATE, Compare, Migration and convergence checks.
- Unicode, Persian text, line breaks and embedded single quotes are retained; SQL literals escape single quotes by doubling them.
- MySQL/MariaDB comment-only column migration preserves the live native column type, character set, collation, default, nullability and supported extra metadata instead of rebuilding the column from a lossy logical definition.
- Unknown MySQL/MariaDB live column extras fail closed rather than risk changing unrelated physical attributes.
- Metadata readers now retain MySQL/MariaDB per-column character set and collation for safe comment-only migration.

## Verification gates

- CrossDbmsDescriptionMigrationTest
- DescriptionBeforeForeignKeyOrderingTest
- JdbcMySqlMetadataRepositoryTest
- JdbcMariaDbMetadataRepositoryTest
- SchemaDiffEngineTest
- MigrationSqlRendererTest
- SchemaCompareExcelWriterTest

A standalone Java probe was also compiled and executed for all seven DBMS in the packaging environment. Full Maven execution is delegated to the Windows project environment because the packaging environment cannot download the Maven wrapper distribution.
