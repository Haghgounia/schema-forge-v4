# Testing and Regression Baseline

## 1. Current frozen baseline status

The current official source baseline is:

```text
SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C5.3
```

It is the exact C5.3-R1 source described in `CURRENT-RELEASE-BASELINE.md`. C5.3-R1 repaired one stale test-only path assumption discovered by the first C5 full regression; production source was unchanged by that repair.

## 2. User-verified clean full regression

```text
Tests run: 492
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Total time: 02:13 min
Finished: 2026-08-22T23:33:56-07:00
```

Command:

```bash
mvn clean test
```

The four skipped normal-suite tests are:

- `MySqlDirectoryExecutionTest`;
- `OracleSqlDirectoryExecutionTest`;
- `PostgreSqlDirectoryExecutionTest`;
- `SqlServerDirectoryExecutionTest`.

Each is guarded by required SQL-root/JDBC properties and is intentionally inactive in an ordinary build without that environment.

The same full build kept Standard Word regression green: 9 documents, 9 passed, 0 failed, 9 tables, 117 columns.

## 3. C5 verification sequence

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

The subsequent clean full regression is the authoritative current result shown in section 2.

## 4. Frozen source fingerprint

```text
8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37
```

This is the SHA-256 of the sorted per-file SHA-256 manifest for the complete frozen `src` tree after C5.3-R1. Any subsequent source/test change requires a new candidate fingerprint and regression evidence before promotion.

## Pending corrective candidate: C5.3-R2

The official C5.3 baseline above is unchanged. A later repair candidate addresses MySQL
`NUMBER(19,0)` AutoNum compatibility for real EA input.

```text
Candidate source fingerprint : de0eaac67c9488f71d8a57fe36a55459b6b558dcc61161976def3b25aa29a42c
Main Java files             : 253
Test Java files             : 173
Expected full test count    : 496
Local Java 21 core compile  : PASS
Real EA compatibility probe : PASS
Maven regression            : PENDING
```

The exact repair scope and separate MySQL timezone-aware timestamp limitation are documented in
`docs/maintenance/2026-08-23-MYSQL-NUMBER19-AUTOINCREMENT-R2.md`.

## 5. Consolidation regression history

| Checkpoint | Tests | Failures | Errors | Skipped | Result | Fingerprint |
|---|---:|---:|---:|---:|---|---|
| C1 | 467 | 0 | 0 | 4 | SUCCESS | `77e038a...0525c` |
| C4.2 | 475 | 0 | 0 | 4 | SUCCESS | `8b76049f...21ad75` |
| C4.3 | 482 | 0 | 0 | 4 | SUCCESS | `2d75fbbc...129423` |
| C5.3 first full run | 492 | 1 | 0 | 4 | FAILURE - stale test path assumption | `5b600c90...21c1a6` |
| C5.3-R1 official | 492 | 0 | 0 | 4 | SUCCESS | `8566f221...bad37` |

Full candidate/repair/freeze details are recorded in `../roadmap/CONSOLIDATION-VERSION-HISTORY.md`.

## 6. Earlier physical milestone history

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

## 7. Current opt-in live-test inventory

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

## 8. Future repair and freeze rule

For every later candidate or corrective version:

1. state the change scope before implementation;
2. update `docs/roadmap/CONSOLIDATION-VERSION-HISTORY.md` and `CHANGELOG.md`;
3. calculate a new `src` fingerprint when source or tests change;
4. run the focused regression appropriate to the change;
5. run `mvn clean test` before promoting a source-changing candidate to an official freeze;
6. keep unavailable live-database environments separately classified rather than treating them as successful execution.

A green regression suite does not authorize guessing unsupported vendor behavior. New database-specific physical or metadata behavior still requires explicit model/evidence rules and focused tests.
