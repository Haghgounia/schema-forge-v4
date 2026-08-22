# Testing and Regression Baseline

## 1. Current frozen baseline status

The current official source baseline is `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C1`, described in `CURRENT-RELEASE-BASELINE.md`.

The exact frozen source passed both the focused consolidation regression and the clean full Maven regression.

## 2. User-verified clean full regression

```text
Tests run: 467
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Total time: 01:54 min
Finished: 2026-08-22T07:01:53-07:00
```

Command:

```bash
mvn clean test
```

The four skipped normal-suite tests are `MySqlDirectoryExecutionTest`, `OracleSqlDirectoryExecutionTest`, `PostgreSqlDirectoryExecutionTest`, and `SqlServerDirectoryExecutionTest`; each is guarded by required SQL-root/JDBC properties and is intentionally inactive in an ordinary build without that environment.

The targeted C1 regression immediately before the full suite also passed 28 tests with 0 failures, 0 errors, and 0 skips.

The same clean build reported Standard Word regression: 9 documents, 9 passed, 0 failed, 9 tables, 117 columns.

## 3. Frozen source fingerprint

Current C1 frozen fingerprint:

```text
77e038a4acb5631d4a407174d9e075cc3d773d21b96a7e884410d9fbdc00525c
```

The fingerprint is the SHA-256 of the sorted per-file SHA-256 manifest for the complete frozen `src` tree. Any subsequent source/test change requires a new candidate baseline and a new fingerprint.

Previous P8-D/P8-C source fingerprint, retained only as historical evidence:

```text
a864b0f1db1099436a766b39ce9de651503ed217596f252ae3e2dc039ad73c3f
```

## 4. Physical milestone history

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

These counts document historical milestones and are not the current expected test count after MySQL, Migration M1/M2, and subsequent consolidation work.

## 5. Current opt-in live-test inventory

The current source contains database-dependent live or directory execution paths beyond the old three-skip P8 baseline. In particular, M2 live pilot harnesses exist for:

- Oracle;
- PostgreSQL;
- Db2 for z/OS;
- SQL Server;
- MySQL.

The frozen normal-suite skipped count is 4, as recorded above. This count does not include opt-in `*IT` live pilots that are outside the ordinary Surefire naming/execution path.

For live-validation evidence, classify each database test separately as one of:

```text
EXECUTED_AND_PASSED
SKIPPED_BY_CONFIGURATION
NOT_EXECUTED_ENVIRONMENT_UNAVAILABLE
FAILED
```

Db2 for z/OS additionally depends on the external IBM JCC environment and explicit destructive acknowledgement.

## 6. Freeze evidence and future baseline rule

The C1 baseline freeze evidence is complete for the standard regression gate: the exact source passed `mvn clean test`, result counts and skips are recorded, the frozen `src` fingerprint is recorded, and the project archive is integrity-checked.

Opt-in live pilots remain separately classified evidence; unavailable database environments must never be treated as successful execution. Any future source change requires a new candidate ID, a recalculated `src` fingerprint, and a new clean regression before promotion.

## 7. Regression interpretation

A green unit/regression suite does not authorize guessing unsupported vendor behavior. New database-specific physical or metadata behavior still requires explicit model/evidence rules and focused tests.
