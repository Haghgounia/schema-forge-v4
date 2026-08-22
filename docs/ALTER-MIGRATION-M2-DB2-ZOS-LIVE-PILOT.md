# ALTER/Migration M2 Db2 for z/OS live pilot

`Db2ZosMigrationM2LivePilotIT` is the opt-in live validation for the existing-table migration path on Db2 for z/OS.

The pilot creates only `SF_M2_PARENT` and `SF_M2_CHILD` in the explicitly configured schema. It requires both the existing Db2 DDL acknowledgement and `schemaforge.db2zos.migration.confirmDestructive=true` because Db2 DDL can commit implicitly.

The test proves that:

- the normal full CREATE DDL is generated even though the table already exists;
- column add/drop/type/nullability/default drift is detected;
- PK/FK/UK/CHECK/standalone-index drift is detected;
- safe output comments destructive SQL;
- explicitly confirmed migration SQL is executed against the live subsystem;
- seed data survives the migration;
- live catalog metadata is re-read after execution and must produce zero residual drift;
- the two pilot tables are removed in `finally` when cleanup is enabled.

Constraint-enforcing indexes that Db2 associates with a primary-key or unique constraint are not exposed as separate standalone indexes by `JdbcDb2ZosMetadataRepository`; their physical metadata continues to be attached to the PK/UK model.

The IBM JCC driver is not bundled. Use the existing `db2zos-live` Maven profile and an organization-approved local driver JAR.

Example:

```bat
mvnw.cmd -Pdb2zos-live ^
  -Ddb2zos.jcc.path="D:\drivers\db2jcc4.jar" ^
  -Dtest=Db2ZosMigrationM2LivePilotIT ^
  -Dschemaforge.db2zos.url="jdbc:db2://host:446/LOCATION" ^
  -Dschemaforge.db2zos.user=SCHEMAFORGE ^
  -Dschemaforge.db2zos.password=change-me ^
  -Dschemaforge.db2zos.test.schema=SFTEST ^
  -Dschemaforge.db2zos.execution.confirm=I_UNDERSTAND_DB2_DDL_MAY_COMMIT ^
  -Dschemaforge.db2zos.migration.confirmDestructive=true ^
  test
```

Artifacts are written under `target/db2zos-migration-m2-live-pilot` by default.
