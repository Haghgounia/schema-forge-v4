# MariaDB M7 - ALTER/Migration Rendering

## Scope

M7 activates offline MariaDB ALTER/Migration SQL generation. M3-M6 already provide platform registration, MariaDB DDL/physical rendering, live metadata mapping, datatype equivalence, physical comparison, schema diff, and comparison workbook support.

## Safety contract

- `MODIFY COLUMN` always renders the complete desired column definition.
- Type, nullability, and default drift for one column collapse into one statement.
- Destructive SQL stays commented unless `MigrationRenderOptions.confirmDestructive=true`.
- Identity and generated-expression transitions remain manual/review operations.
- Incoming foreign keys owned by other tables remain outside per-table automatic migration scope.

## MariaDB structural DROP syntax

- Primary key: `ALTER TABLE ... DROP PRIMARY KEY`
- Foreign key: `ALTER TABLE ... DROP FOREIGN KEY <name>`
- Unique key: `ALTER TABLE ... DROP INDEX <name>`
- Check constraint: `ALTER TABLE ... DROP CONSTRAINT <name>`
- Standalone index: `ALTER TABLE ... DROP INDEX <name>`

## Naming contract note

- ADD operations use SchemaForge-owned deterministic logical names, not source-provided object names.
- For example, a desired FK on `CHILDREN(PARENT_ID)` renders as `FK_CHILDREN_PARENT_ID`.
- DROP/REPLACE operations address the actual live object name discovered from metadata.

## Acceptance gate

Run on the project workstation:

```cmd
mvnw.cmd clean ^
  -Dtest=MariaDbMigrationSqlRendererTest,MigrationGenerationServiceTest,ComprehensiveAlterAcceptanceTest,SchemaDiffEngineTest,DataTypeCanonicalizerTest,NumericTypeEquivalenceServiceTest,JdbcMariaDbMetadataRepositoryTest,PhysicalMetadataComparatorTest,SchemaCompareExcelWriterTest ^
  test
```

M8 is the next milestone and must execute create/alter/catalog re-read/convergence against a real MariaDB instance before MariaDB migration support is considered live-closed.
