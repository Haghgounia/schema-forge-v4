# SchemaForge V4 - Official Consolidated Baseline

**Baseline ID:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C1`  
**Project version:** `4.0.0-SNAPSHOT`  
**Freeze date:** 2026-08-22  
**Previous user-verified baseline:** `SCHEMAFORGE-V4-DOCFINAL-20260817`  
**Status:** OFFICIAL / FROZEN - CLEAN REGRESSION VERIFIED

## 1. Why this baseline exists

The 2026-08-17 P8-D documentation baseline is no longer the current source state. Since that freeze, SchemaForge V4 has added MySQL as the fifth registered DBMS, live metadata support for MySQL, Flyway-compatible ALTER/Migration M1 and M2, five-database migration pilot harnesses, and subsequent dialect-specific migration hardening.

This document describes the official consolidated source baseline frozen after the user-verified 2026-08-22 clean regression. The older P8/P8-D documents remain historical validation evidence and must not be interpreted as the current code baseline.

## 2. Current functional state

| Area | Current source state |
|---|---|
| Canonical model | Active shared DBMS-neutral model |
| Standard Word parsing | Implemented and regression-covered |
| Legacy Word parsing | Implemented and regression-covered |
| Enterprise Architect XML/XMI import | Implemented |
| Logical DDL | Oracle, PostgreSQL, Db2 for z/OS, SQL Server, MySQL |
| JDBC metadata repository | Oracle, PostgreSQL, Db2 for z/OS, SQL Server, MySQL |
| Logical/object comparison workbook | Five DBMS, when metadata repository is enabled |
| Physical DDL/comparison | Oracle, PostgreSQL, Db2 for z/OS, SQL Server |
| MySQL physical contract | Deferred; metadata evidence exists but no frozen physical renderer/comparator |
| ALTER/Flyway migration M2 | Implemented for all five DBMS |
| Metadata-based CRUD | Oracle package; SQL Server stored procedures |
| Mermaid / Graphviz / conceptual diagrams | Implemented |
| REST endpoints | 7 current endpoints |
| Artifact contract | Not yet unified across all REST generation paths |

## 3. Current dialect capability contract

`DialectFeature` is the single active optional-DDL capability contract. The obsolete unused `DatabaseCapability` enum was removed during consolidation.

MySQL now explicitly declares `DialectFeature.GRANT`, so the standard configured table grants pass through the same existing `GrantSchemaEnricher` and `DdlGenerator` path used by the other supported grant-capable dialects.

## 4. User-verified clean regression for this baseline

The exact C1 source was verified with a targeted regression first and then a clean full Maven regression.

Targeted consolidation regression:

```text
Tests run: 28
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Finished: 2026-08-22T06:36:43-07:00
```

Full clean regression:

```text
Tests run: 467
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Total time: 01:54 min
Finished: 2026-08-22T07:01:53-07:00
```

The four normal-suite skips are environment-gated directory execution tests and were skipped because their required SQL-root/JDBC configuration was not supplied:

- `MySqlDirectoryExecutionTest`;
- `OracleSqlDirectoryExecutionTest`;
- `PostgreSqlDirectoryExecutionTest`;
- `SqlServerDirectoryExecutionTest`.

The standard Word regression inside the same clean build also completed with 9 documents passed, 0 failed, 9 tables, and 117 columns.

Repository/test source inventory for this freeze:

```text
src/main/java .java files : 242
Maven main compilation units: 243
src/test/java .java files : 167
Surefire tests executed      : 467
```

The Maven compilation-unit count is recorded separately from the repository file count because the compiler reported 243 main source units while 242 `.java` files are physically present under `src/main/java`.

## 5. Frozen source fingerprint

```text
77e038a4acb5631d4a407174d9e075cc3d773d21b96a7e884410d9fbdc00525c
```

This fingerprint is the SHA-256 of the sorted per-file SHA-256 manifest for the complete `src` tree frozen by C1 after the MySQL GRANT, capability cleanup, and MySQL physical-comparison guard changes. Any subsequent production or test source change creates a new baseline and requires a new fingerprint.

## 6. Migration/live-validation state

The source tree contains opt-in M2 live pilot harnesses for all five DBMS:

- `OracleMigrationM2LivePilotIT`
- `PostgreSqlMigrationM2LivePilotIT`
- `Db2ZosMigrationM2LivePilotIT`
- `SqlServerMigrationM2LivePilotIT`
- `MySqlMigrationM2LivePilotIT`

The presence of a live pilot class means `LIVE_TEST_AVAILABLE`; it must not be treated as proof that the pilot was executed by the standard `mvn clean test` freeze command. Opt-in `*IT` live pilots remain separate evidence and must be classified by their own execution records.

Db2 for z/OS execution additionally depends on the external IBM JCC setup and explicit destructive acknowledgement; the driver is not bundled.

## 7. Current REST state

The source currently exposes these endpoints:

```text
POST /api/v1/generate/word
POST /api/v1/generate/legacy-word
POST /api/v1/generate/zip
POST /api/v1/generate/ea-xml
POST /api/v1/generate/oracle/crud
POST /api/v1/generate/sqlserver/crud
POST /api/v1/diagram/mermaid/canonical-json
```

REST generation is functional, but artifact naming/layout/manifest/error contracts are not yet unified across all endpoints. That work is intentionally deferred to the next consolidation stage after the source baseline is stabilized.

## 8. Deferred / intentionally incomplete areas

Major current boundaries include:

- MySQL physical DDL and physical comparison contract;
- PostgreSQL, Db2 for z/OS, and MySQL metadata-based CRUD generators;
- a unified REST artifact contract and manifest for all generation paths;
- a unified REST error contract;
- Oracle LOB-specific physical storage;
- partition/subpartition physical modeling;
- SQL Server `TEXTIMAGE_ON` / `FILESTREAM_ON` / partition schemes;
- PostgreSQL explicit table access-method design;
- Db2 recovery/cluster/null-key semantics and shared storage-object provisioning;
- incoming foreign-key deployment planning across tables for M2 migration.

See `KNOWN-LIMITATIONS.md` and historical phase documents for detailed boundaries.

## 9. Controlled next-stage roadmap

All work after this freeze is controlled by [`docs/roadmap/SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md`](../roadmap/SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md). The roadmap records stage order, status, scope, exit criteria, and the mandatory rule that each stage must be explained with its exact change list before implementation starts.

The next planned stage is `C4 - Artifact Contract V1`.

## 10. Freeze status and change rule

The C1 consolidation baseline is now frozen because:

1. the identified C1 source/documentation gaps were closed;
2. the targeted 28-test regression passed;
3. the exact source passed `mvn clean test` with 467 tests, 0 failures, 0 errors, and 4 configuration-based skips;
4. the skip set is explicitly recorded;
5. the `src` fingerprint is frozen and recorded above.

Opt-in live database pilots remain separate evidence and are not implied by the standard-suite result. Any source change after this freeze must be treated as a new candidate baseline with a new fingerprint and regression record.
