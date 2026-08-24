# Testing and Regression Baseline

## 1. Current frozen baseline status

The current official source baseline is:

```text
SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.10
```

It is the exact user-verified C8.10 source described in `CURRENT-RELEASE-BASELINE.md`. It includes the prior R2 MySQL repair, C6 Standard Artifact Manifest V1, C7 REST contract, and the complete C8 service-decomposition sequence.

## 2. User-verified clean full regression

```text
Tests run: 554
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Total time: 02:30 min
Finished: 2026-08-23T06:22:23-07:00
```

Targeted C8.10-R1 gate:

```text
Tests run: 43
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Total time: 01:46 min
Finished: 2026-08-23T06:15:32-07:00
```

The four skipped normal-suite tests are:

- `MySqlDirectoryExecutionTest`;
- `OracleSqlDirectoryExecutionTest`;
- `PostgreSqlDirectoryExecutionTest`;
- `SqlServerDirectoryExecutionTest`.

Each is guarded by required SQL-root/JDBC properties and is intentionally inactive in an ordinary build without that environment.


## 3. C11 final consolidation verification candidate

C11 is source-unchanged. The exact C8.10 source remains `276` main / `189` test Java with fingerprint `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`. The final verification plan is [`../roadmap/C11-FINAL-CONSOLIDATION-VERIFICATION.md`](../roadmap/C11-FINAL-CONSOLIDATION-VERIFICATION.md). It requires a 95-test targeted consolidation gate followed by `mvnw.cmd clean test`; the official baseline remains C8.10 until both gates pass.

## 4. C5 verification sequence and R2 corrective verification

Targeted C5.3 naming/layout regression:

```text
Tests run: 50
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Finished: 2026-08-22T23:19:22-07:00
```

The first full C5.3 run executed 492 tests and found one failure in `DirectoryDualDatabaseGenerationRunnerTest`. The production runner had generated the scripts under the intentional C5 paths `ddl/oracle/` and `ddl/postgresql/`; the test still enumerated only root-level files. C5.3-R1 changed only that test to recurse and assert the canonical roots.

Repair verification:

```text
Tests run: 1
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Finished: 2026-08-22T23:31:05-07:00
```

The subsequent C5.3-R1 clean regression passed `492 / 0 / 0 / 4`. A later real-EA MySQL compatibility repair C5.3-R2 was then verified:

```text
Targeted R2: 38 tests, 0 failures, 0 errors, 0 skips
Full R2    : 496 tests, 0 failures, 0 errors, 4 skips
BUILD SUCCESS
Finished full run: 2026-08-23T00:18:45-07:00
```

The R2, C6.2, C7.2, and C8.1 through C8.9 results remain historical evidence; C8.10 is the authoritative current official regression shown in section 2.

## 5. Frozen source fingerprint

```text
03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
```

This is the SHA-256 of the sorted per-file SHA-256 manifest for the complete frozen C8.10 `src` tree. Any subsequent source/test change requires a new candidate fingerprint and regression evidence before promotion.

## Current C8 state

C8 is complete. C8.10 is official and frozen after the repaired user-verified targeted `43/43` and full `554 / 0 / 0 / 4` regression.

```text
Official source fingerprint : 03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
Main Java files             : 276
Test Java files             : 189
Full Surefire tests         : 554
```

The initial C8.10 candidate failed before Surefire because the still-used `PreparedSchema` import had been removed. C8.10-R1 restored only that import. No method body or test source changed in R1. See `../architecture/SERVICE-DECOMPOSITION-C8.md`.

C9 Test Matrix / Live-Validation Classification and C10 Documentation Consolidation are complete with source unchanged; C11 Final Consolidation Regression / Baseline Freeze is next.


## 6. Consolidation regression history

| Checkpoint | Tests | Failures | Errors | Skipped | Result | Fingerprint |
|---|---:|---:|---:|---:|---|---|
| C1 | 467 | 0 | 0 | 4 | SUCCESS | `77e038a...0525c` |
| C4.2 | 475 | 0 | 0 | 4 | SUCCESS | `8b76049f...21ad75` |
| C4.3 | 482 | 0 | 0 | 4 | SUCCESS | `2d75fbbc...129423` |
| C5.3 first full run | 492 | 1 | 0 | 4 | FAILURE - stale test path assumption | `5b600c90...21c1a6` |
| C5.3-R1 official | 492 | 0 | 0 | 4 | SUCCESS | `8566f221...bad37` |
| C5.3-R2 official corrective checkpoint | 496 | 0 | 0 | 4 | SUCCESS | `de0eaac6...29a42c` |
| C6.2 official | 504 | 0 | 0 | 4 | SUCCESS | `b9fa369b...af8969f` |
| C7.2 official | 525 | 0 | 0 | 4 | SUCCESS | `763dcea0...daa0a26` |
| C8.1 official | 527 | 0 | 0 | 4 | SUCCESS | `12890096...1cba8e` |
| C8.2 first targeted run | 45 | 0 | 3 | 0 | FAILURE - invalid new test fixture timestamp | `7b9b012c...31f79` |
| C8.2-R1 official | 530 | 0 | 0 | 4 | SUCCESS | `aa77b6bf...6f1e14` |
| C8.3 official | 533 | 0 | 0 | 4 | SUCCESS | `90b8fcb7...927cab` |
| C8.4 official | 536 | 0 | 0 | 4 | SUCCESS | `8f134a74...2e8c57` |
| C8.5 official | 539 | 0 | 0 | 4 | SUCCESS | `49eaad17...80ae0f` |
| C8.6 official | 542 | 0 | 0 | 4 | SUCCESS | `a5f01c4c...8e8afb` |
| C8.7 official | 545 | 0 | 0 | 4 | SUCCESS | `a8e0f2d9...add57d` |
| C8.8 first targeted run | 39 | 1 | 0 | 0 | FAILURE - new provenance assertion over-selected non-batch summary descriptors | `779e3751...a6dc31` |
| C8.8 official | 548 | 0 | 0 | 4 | SUCCESS; targeted `39/0/0/0` | `b8fb0a32...79b396` |
| C8.9 official | 551 | 0 | 0 | 4 | SUCCESS; targeted `42/0/0/0` | `9ef7e131...db6898` |
| C8.10 first targeted run | 0 | 0 | 0 | 0 | COMPILE FAILURE before Surefire - missing `PreparedSchema` import | `dfe57506...52db40` |
| C8.10-R1 official / C8 complete | 554 | 0 | 0 | 4 | SUCCESS; targeted `43/0/0/0` | `03d01cfd...d5102ba` |

Full candidate/repair/freeze details are recorded in `../roadmap/CONSOLIDATION-VERSION-HISTORY.md`.

## 7. Earlier physical milestone history

| Milestone | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| P0 object-scoped physical | 351 | 0 | 0 | 3 | SUCCESS |
| P1 Db2 table-space physical | 353 | 0 | 0 | 3 | SUCCESS |
| P2 Oracle physical | 356 | 0 | 0 | 3 | SUCCESS |
| P3 PostgreSQL physical | 359 | 0 | 0 | 3 | SUCCESS |
| P4 SQL Server physical | 363 | 0 | 0 | 3 | SUCCESS |
| P5 index build options | 368 | 0 | 0 | 3 | SUCCESS |
| P6 PostgreSQL column physical | 372 | 0 | 0 | 3 | SUCCESS |
| P7 Db2 index DEFINE/DEFER | 376 | 0 | 0 | 3 | SUCCESS |
| P8-A table physical comparison | 385 | 0 | 0 | 3 | SUCCESS |
| P8-B index/PK/UK comparison | 394 | 0 | 0 | 3 | SUCCESS |
| P8-C/P8-D final comparison | 399 | 0 | 0 | 3 | SUCCESS |

These counts are historical evidence, not the current expected test count.

## 8. Current opt-in live-test inventory

The current source contains database-dependent live or directory execution paths beyond the standard Surefire freeze. M2 live pilot harnesses exist for Oracle, PostgreSQL, Db2 for z/OS, SQL Server, and MySQL.

The frozen normal-suite skipped count is 4 as recorded above. This count does not imply execution of opt-in `*IT` live pilots.

For live-validation evidence, classify each database test separately as one of:

```text
EXECUTED_AND_PASSED
SKIPPED_BY_CONFIGURATION
NOT_EXECUTED_ENVIRONMENT_UNAVAILABLE
FAILED
```

Db2 for z/OS additionally depends on the external IBM JCC environment and explicit destructive acknowledgement.


## 9. C9 authoritative test matrix and live classification

C9 is complete and source-unchanged. The authoritative matrix is [`../testing/TEST-MATRIX-C9.md`](../testing/TEST-MATRIX-C9.md), with one CSV row for every Java file under `src/test/java`.

```text
Standard unit / contract      : 107
Standard offline integration  : 38
Directory execution           : 4
Opt-in offline *IT            : 25
Live DB *IT                   : 9
Test support/helper           : 6
Total                         : 189
```

The 34 `*IT` classes are not part of ordinary Surefire. The four normal-suite directory execution tests are discovered by Surefire and currently classify as `SKIPPED_BY_CONFIGURATION`. A live test existing in source is only `LIVE_TEST_AVAILABLE`; it becomes `LIVE_TEST_EXECUTED_AND_PASSED` only when exact command/date/environment evidence is recorded.

C9 also defines which focused, full-regression, corpus and live gates are required for ordinary changes, DBMS-specific changes, migration changes and C11 release freeze.

## 10. Future repair and freeze rule

For every later candidate or corrective version:

1. state the change scope before implementation;
2. update `docs/roadmap/CONSOLIDATION-VERSION-HISTORY.md` and `CHANGELOG.md`;
3. calculate a new `src` fingerprint when source or tests change;
4. run the focused regression appropriate to the change;
5. run `mvn clean test` before promoting a source-changing candidate to an official freeze;
6. keep unavailable live-database environments separately classified rather than treating them as successful execution.

A green regression suite does not authorize guessing unsupported vendor behavior. New database-specific physical or metadata behavior still requires explicit model/evidence rules and focused tests.
