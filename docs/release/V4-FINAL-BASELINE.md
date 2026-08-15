# SchemaForge V4 Final Baseline

**Baseline ID:** `SCHEMAFORGE-V4-FINAL-20260814`  
**Project version in `pom.xml`:** `4.0.0-SNAPSHOT`  
**Freeze date:** 2026-08-14  
**Status:** FROZEN BASELINE

## Scope

This baseline freezes the SchemaForge V4 implementation after successful regression and real-database validation of the historical DDL path and the integrated foreign-key deployment path for Oracle, PostgreSQL, and Microsoft SQL Server.

No production or test Java source is changed by this final packaging step. The freeze package only adds release documentation/changelog metadata and removes IDE/transient packaging files.

## Full regression

Latest full local regression result supplied for this baseline:

- Tests discovered: 270
- Failures: 0
- Errors: 0
- Skipped: 3
- Maven result: `BUILD SUCCESS`

The three skipped tests are the database execution integration tests guarded by JUnit assumptions in the normal regression run:

- `OracleSqlDirectoryExecutionTest`
- `PostgreSqlDirectoryExecutionTest`
- `SqlServerDirectoryExecutionTest`

They are intentionally executed only when their database execution configuration is enabled. All three were also exercised explicitly during V4 validation as described below.

## Historical DDL validation

### Oracle

Historical Oracle DDL was validated on a real Oracle database. The main run executed 4,766 generated files with 115,804 successful statements and zero failures. After output-collision handling was added, the four affected collision-pair scripts were executed separately with 86/86 successful statements and zero failures. Together this covers all 4,768 historical source definitions.

### PostgreSQL

- Files validated: 4,768
- Statements executed: 120,614
- Statements succeeded: 120,614
- Statements failed: 0
- Cleanup succeeded: 4,768 / 4,768
- Mode: `HISTORICAL`

The earlier 2,904 PostgreSQL `CREATE INDEX` failures caused by schema-qualified index names were eliminated and confirmed by the final database run.

### Microsoft SQL Server

The full historical run validated all 4,768 files. The first run exposed 1,285 runner-only `CHECK CONSTRAINT` errors in 518 files after intentionally skipped historical foreign keys. After the runner fix, those 518 files were re-executed:

- Retry files: 518
- Statements executed: 17,491
- Statements succeeded: 17,491
- Statements failed: 0
- Cleanup succeeded: 518 / 518

Combined coverage: 4,250 files already passed in the full run + 518 corrected retries = 4,768 / 4,768 validated historical SQL Server scripts.

## Integrated foreign-key deployment validation

The integrated path is separate from historical execution and enforces one effective definition per `schema.table`.

Validated flow:

`Canonical JSON -> ForeignKeyAnalyzer -> IntegratedSchemaDeploymentPlanner -> IntegratedSqlRenderer -> CREATE TABLES -> local objects -> ADD FOREIGN KEYS -> metadata`

### Cross-dialect pilot

A real canonical pilot with two tables and one physical foreign key was executed in `FULL` mode on all three DBMS:

- Oracle: PASS
- PostgreSQL: PASS
- SQL Server: PASS

The SQL Server pilot also exposed a real portability issue where the FK column types were compatible in Oracle/PostgreSQL but not in SQL Server. V4 now reports this pre-deployment as `SQLSERVER_FK_TYPE_MISMATCH` instead of silently changing the model.

### Integrated large pilot

The larger real-data pilot selected:

- 15 tables
- 13 physical foreign keys
- 13 resolved physical foreign keys
- FK chain depth: 2
- Connected components: 3
- FK blockers: 0

Real database results in `FULL` mode:

| DBMS | Statements | Failures | Result |
|---|---:|---:|---|
| Oracle | 260 | 0 | PASS |
| PostgreSQL | 261 | 0 | PASS |
| SQL Server | 274 | 0 | PASS |

SQL Server full cleanup also successfully removed 13 existing foreign keys before dropping the 15 pilot tables.

## Canonical dependency coverage

Historical aggregate analysis of the complete canonical snapshot corpus:

- Snapshots discovered: 4,768
- Snapshots loaded: 4,768
- Snapshot failures: 0
- Distinct table names: 2,391
- Duplicate historical occurrences: 2,377
- Foreign-key definitions: 1,285
- Physical FK definitions: 1,285
- Logical FK definitions: 0
- Distinct physical FK relations: 605
- Aggregate dependency edges: 317
- Missing target definitions in historical aggregate corpus: 527
- Self-reference definitions: 25
- Distinct self-reference relations: 5
- Aggregate cycle candidate groups: 2
- Tables in aggregate cycles: 4

The 527 missing targets are historical aggregate coverage findings. In normal integrated input, unresolved parent tables/columns are blockers and are not silently repaired.

## Self-reference and cycle classification

Self-reference is present in the historical corpus, with five distinct relations. The available historical definitions of `TSTSHMA.DTORGANIZATION` could not form a complete cross-dialect deployable closure because unrelated historical foreign-key targets are unresolved. This is recorded as `PRESENT_BUT_NOT_DEPLOYABLE`; it is not treated as evidence of a production deployment failure.

The two multi-table aggregate cycle candidates were classified as `HISTORICAL_AGGREGATE_ONLY`:

1. `TSTSHMA.CTACCOUNTS <-> TSTSHMA.MSCUSTOMERS`
2. `TSTSHMA.CTPLICENSEDUPNID <-> TSTSHMA.JTDTOCUSTOMERS`

No one-version-per-table compatible combination preserves either cycle, so no real deployable cycle exists in this historical corpus and no cycle database pilot is required for this baseline.

## Frozen contracts

The following V4 behavior is considered frozen:

- Legacy Word -> canonical model mapping
- Canonical JSON snapshot cache
- Oracle historical DDL generation
- PostgreSQL historical DDL generation
- SQL Server historical DDL generation
- collision-safe per-source output naming
- integrated input duplicate-table blocking
- foreign-key resolution/validation
- integrated two-phase table/FK deployment planning
- cross-dialect FK portability validation
- Oracle/PostgreSQL/SQL Server integrated rendering
- historical and full execution runner behavior validated during this release

## Known constraints

- Historical regression corpora may contain multiple versions of the same table. This is allowed only in historical/test workflows.
- Integrated/production input must contain at most one definition for a `schema.table`; duplicates are blockers.
- Historical aggregate missing-target and cycle findings must not be interpreted as production-schema findings unless the same relations coexist in a one-version-per-table integrated input.
- SQL Server FK type compatibility can be stricter than Oracle/PostgreSQL; V4 reports such portability mismatches before integrated deployment.
- Database credentials in local configuration are environment-specific and must be externalized before distribution outside the controlled development environment.

## Freeze rule

Future feature work must not modify this baseline in place. Changes should be developed after this baseline and must preserve the V4 regression suite and the validated historical/integrated contracts above.
