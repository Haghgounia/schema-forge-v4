# SchemaForge V4 - Official Consolidated Baseline

**Baseline ID:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.10`  
**Project version:** `4.0.0-SNAPSHOT`  
**Freeze date:** 2026-08-23  
**Previous official baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.9`  
**Status:** OFFICIAL / FROZEN C8.10 CHECKPOINT / C8 COMPLETE / CLEAN REGRESSION VERIFIED

## 1. Baseline purpose

This is the current official SchemaForge V4 source checkpoint. It contains the completed C4 Artifact
Contract V1, completed C5 Artifact Naming/Layout consolidation, the user-verified C5.3-R2 MySQL
`NUMBER(19,0)` AutoNum/FK compatibility repair, the completed C6 Standard Artifact Manifest V1, the completed C7 REST Response/Error Contract, and the complete C8 service-decomposition set (`DiagramArtifactProducer`, `MigrationArtifactProducer`, `ComparisonArtifactProducer`, `CrudArtifactProducer`, `BatchArchiveSupport`, `ArtifactPackageBuilder`, `DocumentGenerationOrchestrator`, `BatchGenerationOrchestrator`, `EaGenerationOrchestrator`, and `ArtifactGenerationService`).

C8.10-R1 passed its targeted and full Maven gates and the exact source is now the official frozen C8.10 baseline. C8 API/Application Service Decomposition is complete. C9 and C10 are source-unchanged completed checkpoints. C11 verification is now active on this exact source; C8.10 remains the official baseline until the C11 targeted and full gates pass.

## 2. Current functional state

| Area | Official C8.10 state |
|---|---|
| Canonical model | Active shared DBMS-neutral model |
| Standard Word parsing | Implemented and regression-covered |
| Legacy Word parsing | Implemented and regression-covered |
| Enterprise Architect XML/XMI import | Implemented |
| Logical DDL | Oracle, PostgreSQL, Db2 for z/OS, SQL Server, MySQL |
| JDBC metadata repository | All five DBMS |
| Logical/object comparison workbook | All five DBMS when metadata is available |
| Physical DDL/comparison | Oracle, PostgreSQL, Db2 for z/OS, SQL Server |
| MySQL physical contract | Deferred |
| ALTER/Flyway migration M2 | Implemented for all five DBMS |
| Metadata-based CRUD | Oracle package; SQL Server procedures |
| Mermaid / Graphviz / conceptual diagrams | Implemented |
| REST endpoints | 7 current endpoints |
| Artifact Contract V1 | Implemented and production-path tracked |
| Artifact naming/layout | C5 artifact-first contract implemented and verified |
| MySQL `NUMBER(19,0)` AutoNum portability | C5.3-R2 verified: identity -> `BIGINT UNSIGNED AUTO_INCREMENT`; proven internal FK propagation -> `BIGINT UNSIGNED` |
| Standard Manifest V1 | C6.2 implemented, user-verified, and frozen |
| REST Response/Error Contract | C7.2 implemented, user-verified, and frozen |
| Service decomposition | C8.1 through C8.10 user-verified and frozen; C8 is complete and `SchemaForgeApiService` is a thin facade over application services/orchestrators |

## 3. User-verified regression for this baseline

Targeted C8.10-R1 verification:

```text
Tests run: 43
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Total time: 01:46 min
Finished: 2026-08-23T06:15:32-07:00
```

Full clean C8.10 regression:

```text
Tests run: 554
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Total time: 02:30 min
Finished: 2026-08-23T06:22:23-07:00
```

The four normal-suite skips remain the environment-gated directory execution tests:

- `MySqlDirectoryExecutionTest`;
- `OracleSqlDirectoryExecutionTest`;
- `PostgreSqlDirectoryExecutionTest`;
- `SqlServerDirectoryExecutionTest`.

The standard Word regression remained green within the full build.

Official C8.10 source inventory:

```text
src/main/java .java files : 276
src/test/java .java files : 189
Surefire tests executed    : 554
```

## 4. Frozen source fingerprint

```text
03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
```

This is the SHA-256 of the sorted per-file SHA-256 manifest for the complete official C8.10 `src` tree.
Any later source/test change is a candidate until it receives its own regression evidence and freeze.

## 5. R2 corrective scope

R2 changes only the MySQL cross-dialect identity/type compatibility path required by real EA input:

- `NUMBER(19,0)` AutoNum -> `BIGINT UNSIGNED AUTO_INCREMENT`;
- internal canonical FK columns proven to reference such identity keys -> `BIGINT UNSIGNED`;
- unrelated `NUMBER(19,0)` remains `DECIMAL(19)`;
- external/unknown parents are not guessed;
- DBA-visible SQL comments explain the portability adaptation;
- parser semantics, C5 naming/layout, REST endpoint URLs, and other DBMS mappings remain unchanged.

Detailed repair evidence is in
`../maintenance/2026-08-23-MYSQL-NUMBER19-AUTOINCREMENT-R2.md`.

A separate existing MySQL limitation remains: timezone-aware Oracle-style timestamp types such as
`TIMESTAMP WITH TIME ZONE` do not currently have a declared lossless MySQL mapping. That limitation
is intentionally outside R2.

## 6. REST state

Current endpoints:

```text
POST /api/v1/generate/word
POST /api/v1/generate/legacy-word
POST /api/v1/generate/zip
POST /api/v1/generate/ea-xml
POST /api/v1/generate/oracle/crud
POST /api/v1/generate/sqlserver/crud
POST /api/v1/diagram/mermaid/canonical-json
```

The official C7.2 baseline uses the versioned `schemaforge-rest-error/v1` error envelope and adds `X-SchemaForge-Request-Id` correlation while preserving endpoint URLs and successful payload types.

## 7. Migration/live-validation state

Opt-in M2 live pilot harnesses exist for all five DBMS:

- `OracleMigrationM2LivePilotIT`
- `PostgreSqlMigrationM2LivePilotIT`
- `Db2ZosMigrationM2LivePilotIT`
- `SqlServerMigrationM2LivePilotIT`
- `MySqlMigrationM2LivePilotIT`

Their existence means `LIVE_TEST_AVAILABLE`, not `LIVE_TEST_EXECUTED_AND_PASSED`. Standard Surefire
freeze results do not imply execution of opt-in `*IT` pilots.

## 8. Completed C6 Standard Artifact Manifest

C6.1 design and C6.2 implementation are complete and user-verified. The common root `manifest.json`
is now used by Word, Legacy Word, ZIP Batch, and EA package-producing paths. The contract is
`schemaforge-manifest/v1` and includes generation/source/model/validation/outcome metadata, exact
artifact relative paths, SHA-256 and byte size for generated artifacts, deterministic ordering, and
a manifest self-entry without recursive self-integrity.

The former EA-only manifest was replaced by the common contract, with EA-specific dependency/cycle
metadata retained under `extensions.enterpriseArchitect`.

Current manifest contract documentation: `../architecture/ARTIFACT-MANIFEST.md`.

## 9. Deferred / intentionally incomplete areas

Major current boundaries include:

- MySQL physical DDL and physical comparison contract;
- PostgreSQL, Db2 for z/OS, and MySQL metadata-based CRUD generators;
- Oracle LOB-specific physical storage;
- partition/subpartition physical modeling;
- SQL Server `TEXTIMAGE_ON` / `FILESTREAM_ON` / partition schemes;
- PostgreSQL explicit table access-method design;
- additional Db2 recovery/cluster/storage semantics;
- incoming-FK migration/deployment planning across externally owned tables;
- timezone-aware Oracle-style timestamp mapping to MySQL where no lossless mapping is defined.

## 10. Completed C7 REST Response/Error Contract

C7.1 design and C7.2 implementation are complete and user-verified. The frozen contract:

- adds `schemaforge-rest-error/v1`;
- adds `X-SchemaForge-Request-Id` to `/api/**` responses;
- removes duplicated controller-local exception handlers;
- centralizes exception mapping with `@RestControllerAdvice`;
- preserves successful endpoint payloads, media types, attachment filenames, and URLs;
- uses dedicated `ServiceUnavailableException` for genuine required-service outages;
- returns generic public text for unexpected 500 errors while logging the correlated exception.

C8 API/Application Service Decomposition is complete. C8.1 through C8.10 are user-verified and frozen, with C8.10 `ArtifactGenerationService` completing the final named boundary. C9 Test Matrix / Live-Validation Classification and C10 Documentation Consolidation are complete as source-unchanged documentation checkpoints. C11 Final Consolidation Regression / Baseline Freeze is the next roadmap stage.

Candidate/repair/freeze traceability is maintained in
`../roadmap/CONSOLIDATION-VERSION-HISTORY.md` and every corrective version must be documented there
and in `../roadmap/CHANGELOG.md`.

## 11. Completed C8 decomposition sequence

C8.1 extracted diagram artifact production; C8.2 migration orchestration; C8.3 comparison workbooks; C8.4 metadata-based Oracle/SQL Server CRUD artifacts; C8.5 ZIP-batch filesystem/ledger helpers; C8.6 common package/cleanup helpers; C8.7 shared Standard/Legacy Word orchestration; C8.8 ZIP-batch orchestration; C8.9 EA XML/XMI multi-table orchestration; and C8.10 shared single-document workspace/manifest/package orchestration. All ten boundaries are user-verified and frozen.

Official C8.10 inventory:

```text
Main Java files            : 276
Test Java files            : 189
Full Surefire tests        : 554
Targeted C8.10-R1 tests    : 43
Source fingerprint         : 03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
```

C8 has no remaining named decomposition boundary. C9 Test Matrix / Live-Validation Classification and C10 Documentation Consolidation are complete and did not alter source; C11 Final Consolidation Regression / Baseline Freeze is next in the authoritative roadmap.

## 12. C8.10 completion evidence

C8.10 moves only the Standard Word/Legacy Word workspace, upload transfer, Standard Manifest V1 write, ZIP packaging and cleanup workflow into `ArtifactGenerationService`. The first user-side targeted run stopped during main compilation before tests because `SchemaForgeApiService` still declared a `PreparedSchema` local in the EA path after the import was removed. C8.10-R1 restored only that import. The repaired targeted gate passed `43 / 0 / 0 / 0`, the full clean gate passed `554 / 0 / 0 / 4`, and the exact repaired source is now the official C8.10 freeze.

```text
Main Java files            : 276
Test Java files            : 189
New focused tests          : 3
Expected targeted tests    : 43
Full Surefire              : 554
R1 fingerprint             : 03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
First C8.10 run            : COMPILE FAILURE before tests (missing PreparedSchema import)
Maven regression           : TARGETED 43/43 + FULL 554/0/0/4 BUILD SUCCESS
```

R1 production delta is exactly one import in `SchemaForgeApiService`; there is no method-body change. No intended change: endpoint signatures, input validation, source-name normalization, generation context, Word/Legacy parsers, five-DBMS DDL, migration/comparison/CRUD/diagram producers, C5 paths, C6 manifest contract, C7 REST contract, artifact Ledger semantics or ZIP contents.
## 13. Completed C9 test/live-evidence governance

C9 inventories all `189` Java files under `src/test/java` and separates normal Surefire coverage from opt-in and live evidence:

```text
Standard unit / contract      : 107
Standard offline integration  : 38
Directory execution           : 4  (default-Surefire, configuration-gated)
Opt-in offline *IT            : 25
Live DB *IT                   : 9
Test support/helper           : 6
```

The authoritative source-derived matrix is `../testing/TEST-MATRIX-C9.md` with row-level data in `../testing/TEST-MATRIX-C9.csv`. C9 explicitly distinguishes `LIVE_TEST_AVAILABLE` from `LIVE_TEST_EXECUTED_AND_PASSED`. The C8.10 full regression did not execute the 34 `*IT` classes; its four skipped tests are the configuration-gated Oracle, PostgreSQL, SQL Server and MySQL directory-execution tests.

C9 changes documentation only, so the official source baseline, Java inventory, full regression evidence and source fingerprint remain C8.10.
## 14. Completed C10 documentation consolidation

C10 audited the authoritative current-reference set after C8/C9. It corrected stale current-state facts without rewriting historical phase evidence:

- Architecture now states five logical-DDL platforms and includes `MySqlDialect`;
- the reference entry point now reports the C8.10 `554 / 0 / 0 / 4` regression rather than an older C8.2 count;
- Artifact Contract V1, Naming/Layout, Standard Manifest, REST Contract, C8 decomposition, C9 test matrix, current baseline and roadmap are linked from the authoritative entry point;
- four-DBMS wording is retained only where the frozen physical-DDL/physical-comparison scope intentionally excludes MySQL;
- current-stage wording now points to C11 as the only remaining consolidation stage.

C10 is documentation-only. The official source baseline remains C8.10 with the same Java inventory, regression evidence and fingerprint.

