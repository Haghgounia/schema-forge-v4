# Glossary V4.14.4-HF1: test-fixture performance alignment

Baseline: `schema-forge-v4-2026-10-21-glossary-v4144-full.zip` (already passed both Windows test runs).

## Windows evidence

| Run | Tests | Failures | Errors | Skipped | Maven | Glossary XLSX/spelling test |
|---|---:|---:|---:|---:|---|---|
| Targeted | 23 | 0 | 0 | 1 | BUILD SUCCESS | 1425 s |
| Full | 1008 | 0 | 0 | 11 | BUILD SUCCESS | 1321 s |

The original test `exportDoesNotStopAtTheFirstThousandEntries` inserted 1003 fixture terms using individual `GlossaryRepository.create()` calls. The repository's `DriverManagerDataSource` for a file-backed H2 database opens a new connection on each operation. Each create does a restore attempt, INSERT and lookup. The repeated connection churn explains the fixture's expensive setup and is **not** evidence that actual XLSX export is equally slow.

## Change

The test builds the exact same 1003 unique `TERM0000` through `TERM1002` candidate entries as `GlossaryImportTerm` records and calls `GlossaryRepository.importNew()` once. That existing production API uses a single JDBC transaction and preserves the same logical test rows. The XLSX assertion still checks 1004 physical rows (header plus all terms), Overview total=1003, and now inspects `TERM0000`, the first second-page key `TERM1000`, and the final `TERM1002`.

No changes to production classes, REST endpoints, glossary schema, H2 database contents, normalization, human approval or export logic. The source database is deliberately excluded from both ZIPs.

## Acceptance on Windows

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean -Dtest=GlossaryXlsxExportAndSpellingTest test
mvnw.cmd clean test
```

Expected: zero failures/errors, passing >1000-term XLSX test, and materially reduced `GlossaryXlsxExportAndSpellingTest` elapsed time (the exact elapsed time must be measured on the user's Windows system). An occasional skipped external corpus/live DB test is independently controlled by its environment/profile.

Read-only live service smoke tests remain separate:

```cmd
curl.exe -f -o glossary-export.xlsx "http://localhost:9090/api/v1/glossary/export.xlsx"
curl.exe -i "http://localhost:9090/api/v1/glossary/spelling-suggestions?term=CUTOMER&objectType=COLUMN"
```

The runtime server must be started with `SCHEMAFORGE_GLOSSARY_ENABLED=true`; these GET requests do not mutate H2.
