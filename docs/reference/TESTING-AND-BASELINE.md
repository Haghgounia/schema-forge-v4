# Testing and Regression Baseline

## 1. Current verified baseline

Latest user-verified full Maven result for the current R4 maintenance baseline:

```text
Tests run: 402
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Total time: 02:29 min
Finished: 2026-08-17T23:19:54-07:00
```

Command:

```bash
mvn clean test
```

## 2. Current source fingerprint

Current R4 source-tree fingerprint:

```text
e2b6969837c6a8ce8c34b75b51126bc9fb7cfcad37d4d0795371c99196510a35
```

The R4 documentation-finalization package must retain this exact source-tree fingerprint.

## 3. Physical milestone history

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
| R4 identity/EA graph/no-PK maintenance | 402 | 0 | 0 | 3 | SUCCESS |

## 4. Skipped integration tests

The three skipped tests in the normal baseline are environment-dependent live database execution tests for:

- Oracle;
- PostgreSQL;
- SQL Server.

They require explicit connection configuration and are intentionally guarded in the ordinary regression run.

Db2 for z/OS validation has separate documented workflows and is not one of these three normal skipped integration tests.

## 5. Documentation-only freeze validation

For this R4 baseline-documentation finalization, Java source and test code must not change.

Validation procedure:

1. hash all files under `src` before documentation edits;
2. finalize documentation only;
3. hash all files under `src` again;
4. require byte-for-byte equality;
5. package the project;
6. verify ZIP integrity.

Because this packaging step changes documentation only, the user-verified 402-test R4 result remains the code regression evidence for this package.

## 6. Regression interpretation

A green unit/regression suite does not authorize guessing unsupported vendor behavior. New database-specific physical or metadata behavior still requires explicit model/evidence rules and focused tests.
