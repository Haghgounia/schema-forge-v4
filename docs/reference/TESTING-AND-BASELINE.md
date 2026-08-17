# Testing and Regression Baseline

## 1. Current verified baseline

Latest user-verified full Maven result used by this documentation finalization:

```text
Tests run: 399
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Total time: 02:21 min
Finished: 2026-08-17T08:40:09-07:00
```

Command:

```bash
mvn clean test
```

## 2. Current source fingerprint

P8-D/P8-C source tree fingerprint:

```text
a864b0f1db1099436a766b39ce9de651503ed217596f252ae3e2dc039ad73c3f
```

The documentation-finalization package must retain this exact source-tree fingerprint.

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

## 4. Skipped integration tests

The three skipped tests in the normal baseline are environment-dependent live database execution tests for:

- Oracle;
- PostgreSQL;
- SQL Server.

They require explicit connection configuration and are intentionally guarded in the ordinary regression run.

Db2 for z/OS validation has separate documented workflows and is not one of these three normal skipped integration tests.

## 5. Documentation-only freeze validation

For this documentation finalization, Java source and test code must not change.

Validation procedure:

1. hash all files under `src` before documentation edits;
2. finalize documentation only;
3. hash all files under `src` again;
4. require byte-for-byte equality;
5. package the project;
6. verify ZIP integrity.

Because source remains unchanged, the latest user-verified 399-test result remains the code regression evidence for this package.

## 6. Regression interpretation

A green unit/regression suite does not authorize guessing unsupported vendor behavior. New database-specific physical or metadata behavior still requires explicit model/evidence rules and focused tests.
