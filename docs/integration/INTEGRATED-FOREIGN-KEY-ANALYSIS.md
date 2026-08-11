# Integrated Foreign-Key Analysis

SchemaForge V4 keeps the already validated HISTORICAL DDL path unchanged and adds a separate,
DBMS-neutral analysis step before integrated schema deployment.

## Input contract

Integrated deployment expects one canonical definition per qualified table. A historical regression
corpus may intentionally contain several versions of the same table, but the integrated path does
not guess which version is current. Duplicate qualified table or sequence definitions are blockers.

## Analysis

`ForeignKeyAnalyzer` validates physical foreign keys against the merged canonical schema:

- omitted referenced schema is resolved to the owner-table schema;
- referenced table must exist;
- referenced columns must exist;
- referenced columns must match a canonical primary key or unique key;
- logical foreign keys are reported but are not deployed;
- self references are reported;
- multi-table dependency cycles are warnings, not blockers, because FKs will be added after all
  tables exist.

The analyzer does not generate SQL and does not change any dialect.

## Canonical JSON runner

Run only against an input set that represents one operational version of each table:

```bat
mvnw.cmd -Dtest=CanonicalJsonForeignKeyAnalysisIT test ^
  -Dschemaforge.fk.inputDir="D:\path\to\canonical-json" ^
  -Dschemaforge.fk.outputDir="D:\path\to\fk-analysis" ^
  -Dschemaforge.fk.failOnBlockers=true
```

Reports:

- `canonical-json-fk-summary_*.txt`
- `canonical-json-fk-issues_*.csv`
- `canonical-json-fk-duplicates_*.csv`
- `canonical-json-fk-snapshot-errors_*.csv`

A full historical corpus containing multiple versions is expected to stop at the duplicate-input
check. This is intentional; no "latest version" is silently selected.

## Integrated deployment planner

After a unique operational input has passed `ForeignKeyAnalyzer`,
`IntegratedSchemaDeploymentPlanner` creates a deterministic DBMS-neutral plan. It does not render SQL
and therefore does not modify the already validated dialect generators.

The plan is split into these collections:

1. pre-table sequences;
2. phase 1: tables, with the canonical primary key remaining part of CREATE TABLE;
3. phase 2: table-local check constraints, unique keys, and indexes;
4. phase 3: resolved physical foreign keys only;
5. phase 4: tables that contain comment/description or GRANTS metadata.

A physical FK is never scheduled before all tables. Consequently self references and multi-table
cycles do not require table reordering and do not block the plan. Missing target tables, missing
target columns, or non-unique referenced keys remain blockers and cause planning to stop with
`INTEGRATED_DEPLOYMENT_BLOCKED`.

The planner deliberately does not choose among historical versions. `IntegratedSchemaAssembler`
remains the gate that rejects duplicate qualified table/sequence definitions.
