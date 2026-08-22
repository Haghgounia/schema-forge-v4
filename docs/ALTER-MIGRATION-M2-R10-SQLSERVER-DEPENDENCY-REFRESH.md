# ALTER/Migration M2-R10 - SQL Server ALTER COLUMN dependency refresh

The SQL Server M2 live pilot exposed a real engine dependency rule:

```text
The index 'IX_SF_M2_CHILD_PARENT' is dependent on column 'PARENT_ID'.
```

The index was intentionally unchanged between the live table and the desired design, so it did not appear in the semantic `SchemaDiffEngine` object-change list. SQL Server still requires the index to be removed before the affected `ALTER COLUMN` can execute.

M2-R10 keeps that distinction explicit:

- semantic diff reports only real design changes;
- SQL Server rendering derives an additional operational dependency-refresh set for datatype/nullability changes;
- unchanged table-owned PK, UK, FK, CHECK and standalone INDEX dependencies can be dropped before the column change and recreated from the desired model afterward;
- changed/dropped objects continue to use the ordinary M2 structural phases and are not duplicated;
- DROP ordering is `FK -> INDEX -> CHECK -> UK -> PK`;
- ADD/recreate ordering is `PK -> UK -> CHECK -> INDEX -> FK`;
- SAFE output blocks the guarded `ALTER COLUMN` whenever its required dependency DROP is still commented;
- explicit `confirmDestructive=true` is required for dependency refresh execution;
- incoming FKs owned by other tables cannot be discovered from one table model and remain a deployment-wide DBA responsibility.

CREATE DDL behavior is unchanged: the complete CREATE script is still generated independently even when the table already exists.
