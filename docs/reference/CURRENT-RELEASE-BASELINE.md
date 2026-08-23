# SchemaForge V4 - Official Consolidated Baseline

**Baseline ID:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C5.3`  
**Project version:** `4.0.0-SNAPSHOT`  
**Freeze date:** 2026-08-22  
**Previous official baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C4.3`  
**Status:** OFFICIAL / FROZEN - C5 ARTIFACT NAMING/LAYOUT COMPLETE / CLEAN REGRESSION VERIFIED

## 1. Why this baseline exists

The 2026-08-17 P8-D documentation baseline is no longer the current source state. Since that freeze, SchemaForge V4 has added MySQL as the fifth registered DBMS, live metadata support for MySQL, Flyway-compatible ALTER/Migration M1 and M2, five-database migration pilot harnesses, and subsequent dialect-specific migration hardening.

This document describes the current official consolidated source baseline after completion of C5 Artifact Naming and Layout Consolidation. C5 builds on the completed C4 Artifact Contract V1 and standardizes consumer-visible artifact paths/names across Word, Legacy Word, ZIP Batch, EA, and offline generation while preserving SQL/business semantics. The earlier C4.3 baseline remains the frozen pre-naming/layout checkpoint; C1 remains the pre-C4 consolidation checkpoint. Older P8/P8-D documents remain historical validation evidence and must not be interpreted as the current code baseline.

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
| Artifact contract | V1 core metadata model and production-path tracking implemented and regression-verified across all 7 REST paths |
| Artifact naming/layout | C5 canonical artifact-first layout implemented and regression-verified |

## 3. Current dialect capability contract

`DialectFeature` is the single active optional-DDL capability contract. The obsolete unused `DatabaseCapability` enum was removed during consolidation.

MySQL now explicitly declares `DialectFeature.GRANT`, so the standard configured table grants pass through the same existing `GrantSchemaEnricher` and `DdlGenerator` path used by the other supported grant-capable dialects.

## 4. User-verified regression for this baseline

C5 standardized artifact naming and package layout without changing parser behavior, canonical semantics, DDL/CRUD SQL semantics, metadata recovery, physical DDL, migration diff/safety rules, REST endpoint URLs, or HTTP response bodies. The exact C5.3-R1 source was verified with targeted naming/layout tests, the repaired legacy directory-runner assertion, and a clean full Maven regression.

Targeted C5.3 regression:

```text
Tests run: 50
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Finished: 2026-08-22T23:19:22-07:00
```

Targeted C5.3-R1 repair verification:

```text
Tests run: 1
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Finished: 2026-08-22T23:31:05-07:00
```

Full clean C5.3-R1 regression:

```text
Tests run: 492
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Total time: 02:13 min
Finished: 2026-08-22T23:33:56-07:00
```

The four normal-suite skips remain the environment-gated directory execution tests whose SQL-root/JDBC configuration was not supplied:

- `MySqlDirectoryExecutionTest`;
- `OracleSqlDirectoryExecutionTest`;
- `PostgreSqlDirectoryExecutionTest`;
- `SqlServerDirectoryExecutionTest`.

The standard Word regression remained green within the full build.

Repository/test source inventory for this C5.3 freeze:

```text
src/main/java .java files : 253
src/test/java .java files : 172
Surefire tests executed    : 492
```

The first full C5.3 regression found one stale test-only assumption in `DirectoryDualDatabaseGenerationRunnerTest`: it enumerated only root-level output even though C5 intentionally places DDL under `ddl/oracle/` and `ddl/postgresql/`. Repair R1 changed only that test to recurse into the canonical layout; production Java source and artifact semantics were unchanged.

## 5. Consolidation candidate and repair traceability

The authoritative candidate/repair/freeze history is maintained in [`docs/roadmap/CONSOLIDATION-VERSION-HISTORY.md`](../roadmap/CONSOLIDATION-VERSION-HISTORY.md). It records C1, C4.2, C4.3, C5.1, C5.2, C5.3, C5.3-R1, the pending C5.3-R2 repair, C6.1 design, verification evidence, source fingerprints, and promotion/supersession state.

From this baseline forward, every corrective version must update that history plus `CHANGELOG.md` before the next official freeze. Documentation-only changes must state that source fingerprints remain unchanged; source/test changes require a new fingerprint and regression evidence.

## 6. Frozen source fingerprint

```text
8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37
```

This fingerprint is the SHA-256 of the sorted per-file SHA-256 manifest for the complete `src` tree after C5.3-R1 Artifact Naming/Layout consolidation and its regression-test repair. Any subsequent production or test source change creates a new candidate state and requires a new fingerprint plus regression evidence before the next freeze.

## 7. Migration/live-validation state

The source tree contains opt-in M2 live pilot harnesses for all five DBMS:

- `OracleMigrationM2LivePilotIT`
- `PostgreSqlMigrationM2LivePilotIT`
- `Db2ZosMigrationM2LivePilotIT`
- `SqlServerMigrationM2LivePilotIT`
- `MySqlMigrationM2LivePilotIT`

The presence of a live pilot class means `LIVE_TEST_AVAILABLE`; it must not be treated as proof that the pilot was executed by the standard `mvn clean test` freeze command. Opt-in `*IT` live pilots remain separate evidence and must be classified by their own execution records.

Db2 for z/OS execution additionally depends on the external IBM JCC setup and explicit destructive acknowledgement; the driver is not bundled.

## 8. Current REST state

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

REST generation remains functionally unchanged. Artifact Contract V1 now provides a common metadata model plus request-local production-path tracking for all 7 REST generation paths. Generated descriptors are internal metadata and do not alter current HTTP response bodies, attachment filenames, or archive layout. Artifact naming/layout is now standardized by C5. Common manifest semantics remain C6, and the unified REST error contract remains C7.

## 9. Deferred / intentionally incomplete areas

Major current boundaries include:

- MySQL physical DDL and physical comparison contract;
- PostgreSQL, Db2 for z/OS, and MySQL metadata-based CRUD generators;
- a unified manifest for all generation paths;
- a unified REST error contract;
- Oracle LOB-specific physical storage;
- partition/subpartition physical modeling;
- SQL Server `TEXTIMAGE_ON` / `FILESTREAM_ON` / partition schemes;
- PostgreSQL explicit table access-method design;
- Db2 recovery/cluster/null-key semantics and shared storage-object provisioning;
- incoming foreign-key deployment planning across tables for M2 migration.

See `KNOWN-LIMITATIONS.md` and historical phase documents for detailed boundaries.

## 10. Controlled next-stage roadmap

All work after this freeze is controlled by [`docs/roadmap/SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md`](../roadmap/SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md). The roadmap records stage order, status, scope, exit criteria, and the mandatory rule that each stage must be explained with its exact change list before implementation starts.

The next planned stage is `C6 - Standard Artifact Manifest`.

### Unfrozen corrective candidate after this baseline

Real EA input subsequently exposed a MySQL `NUMBER(19,0)` AutoNum portability gap. The corrective
candidate `C5.3-R2` maps such identity columns to `BIGINT UNSIGNED AUTO_INCREMENT` and propagates the
same target type only through proven internal canonical FK relationships. The candidate is documented
in `docs/maintenance/2026-08-23-MYSQL-NUMBER19-AUTOINCREMENT-R2.md` and is **not part of this official
baseline until Maven regression is user-verified**.

R2 candidate source fingerprint:

```text
de0eaac67c9488f71d8a57fe36a55459b6b558dcc61161976def3b25aa29a42c
```

C6 design may proceed while this repair is pending, but C6 production-source changes must not be
stacked on an unverified R2 source state.

## 11. Freeze status and change rule

The C5.3 baseline is now frozen because:

1. C4 Artifact Contract V1 was already complete and regression-verified;
2. C5.1 source-derived naming/path inventory was completed;
3. C5.2 fixed the naming/layout design decisions, including artifact-first roots, shared request timestamp, deterministic collision handling, and preservation of Flyway filename grammar;
4. C5.3 implemented `ArtifactNamingPolicy` and standardized Word, Legacy Word, ZIP Batch, EA, and offline generation on the canonical layout;
5. targeted C5 regression passed with 50 tests, 0 failures, 0 errors, and 0 skips;
6. the single stale regression assertion found by the first full run was repaired without production-source changes and passed its dedicated 1-test verification;
7. the exact C5.3-R1 source passed `mvnw.cmd clean test` with 492 tests, 0 failures, 0 errors, and 4 configuration-based skips;
8. the C5.3-R1 `src` fingerprint is frozen and recorded above.

Opt-in live database pilots remain separate evidence and are not implied by the standard-suite result. C5 Artifact Naming and Layout Consolidation is complete. C6 may now introduce a common `manifest.json`, but it must consume the already-frozen Artifact Contract and canonical C5 paths rather than introducing a competing naming/layout model.
