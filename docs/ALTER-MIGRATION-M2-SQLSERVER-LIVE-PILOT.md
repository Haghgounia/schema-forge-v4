# ALTER/Migration M2 - SQL Server live pilot

`SqlServerMigrationM2LivePilotIT` is an opt-in destructive integration pilot for Microsoft SQL Server.

It operates only on `SF_M2_PARENT` and `SF_M2_CHILD` in the explicitly configured non-system schema. The test:

- creates an intentionally old live table state;
- proves the normal full CREATE DDL is still generated although the table already exists;
- plans column plus PK/FK/UK/CHECK/INDEX changes from live `sys.*` metadata;
- writes both SAFE/commented and explicitly confirmed Flyway-compatible migrations;
- executes the confirmed migration through JDBC;
- re-reads SQL Server metadata and requires zero residual drift;
- verifies the seed row is preserved;
- drops only the two pilot tables during cleanup.

The pilot refuses SQL Server system databases and protected schemas (`dbo`, `sys`, `guest`, `INFORMATION_SCHEMA`). Destructive SQL must be explicitly enabled with `schemaforge.sqlserver.migration.confirmDestructive=true`.

SQL Server PK and UNIQUE constraint backing indexes are excluded from standalone index metadata. Default-constraint discovery/drop is rendered as one `sys.sp_executesql` statement so its local T-SQL variable remains in one batch when the generated script is split for JDBC execution.

## M2-R10 dependency refresh

The first real SQL Server run reached `ALTER COLUMN PARENT_ID` and failed because the logically unchanged `IX_SF_M2_CHILD_PARENT` index still depended on that column. M2-R10 now derives operational dependency refreshes separately from semantic drift: unchanged owned dependencies are temporarily dropped before the affected `ALTER COLUMN` and recreated afterward. The live pilot asserts this ordering explicitly before executing the migration.
