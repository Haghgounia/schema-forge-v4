# SchemaForge Console V0.5.6 — Large-Batch Glossary Evidence Acceptance

Date: 2026-10-24. Baseline: SchemaForge V0.5.5 FULL source. Track: **UI-C10i**.

## Scope — only two agreed objectives

1. **Complete and accept read-only UI review of Glossary evidence in large generated Batch ZIPs.**
2. **Preserve Canonical Model, generated DDL, and behaviors of all seven supported DBMSs.**

**Explicitly deferred:** interpreting, expanding, validating, approving, or rewriting Legacy Word abbreviations; addressing failed Legacy Word documents, missing FKs, spelling dictionaries, DBMS engine/parser changes, and database permissions. These are NOT part of this change.

## Change and invariants

- `Generation > Results > Glossary > [term]` now scans the **entire** `reports/glossary/unknown-terms.csv` through JSZip's decompressed chunk stream and an incremental UTF-8 / RFC-4180 CSV reader. Does not materialize the CSV or all its rows.
- Exact normalized word match; exact occurrence count only after scanning to end; retains first **25** matching evidence records. Supports escaped quotes, embedded newlines, UTF-8 chunk boundaries, CRLF and unquoted fields. Fails closed for missing/invalid headers and corrupted/oversized fields; never reports an incomplete count as final.
- Bounded decompressed evidence scanning: **128 MiB**, with at most 25 retained matches and a maximum CSV field/record size. Aborts stale scans when users switch terms/archives. Error messages distinguish invalid CSV or exceeding the safety ceiling. The existing 2 MiB bound still applies to **generic report preview**; 32 MiB Manifest, 64 MiB individual download, 5 MiB explicit Glossary CSV import, and 100 MiB compressed-ZIP preview caps are unchanged.
- Only React TypeScript, Node tests and documentation/scripts changed. No Java classes, REST contracts, Spring YAML, H2 schema or files, parser, Canonical/DDL generation or DBMS metadata routing changed. **The absence of backend code changes is verified by byte-for-byte source comparison to V0.5.5**; this is not equivalent to a complete live-DBMS regression run.
- The existing 14 Glossary REST features, manual import path, and approval workflow remain unchanged. `DRY_RUN` is still read-only for automatic candidate registration.

## Evidence and acceptance gates

| Gate | Result / required evidence |
|---|---|
| UI-C10i-1 RFC-4180 streaming | Node targeted tests: BOM, UTF-8 split, escaped quotes, multiline, CRLF, exact term match |
| UI-C10i-2 large CSV | Synthetic over 5 MiB: full count, max 25 rows; file-size preview/import bounds unchanged |
| UI-C10i-3 cancellation and safety | Abort obsolete scan, reject malformed CSV, enforce 128 MiB decompressed scan ceiling |
| UI-C10i-4 real Legacy fixture | `schemaforge-legacy-word-batch-20261024-125105-476.zip` CSV: ACC=5, ISIC=2, TIMEX=452; inspected locally; no abbreviation classification |
| UI-C10i-5 Windows build/browser | **OPEN**: npm tests, TypeScript, Vite build, Chrome evidence selection and term switching |
| REG-7DBMS-1 backend source parity | **PASS (static):** no change in Java/main resources or tests versus V0.5.5 FULL |
| REG-7DBMS-2 Maven generators | **OPEN:** run `scripts\accept-console-v056.cmd`, or run focused Maven test suite listed below |
| REG-7DBMS-3 live DBMS | **OPEN/UNCHANGED:** no live seven-database integration acceptance is inferred from this UI change |

### Windows acceptance procedure

Apply V0.5.6 DELTA to the V0.5.5 project; restart **Vite only**, not Spring Boot:

```cmd
cd /d D:\Projects\schema-forge-v4
call scripts\accept-console-v056.cmd
cd ui
npm run dev
```

Open `Generation`, use **«باز کردن ZIP خروجی قبلی»** to select the previously downloaded `schemaforge-legacy-word-batch-20261024-125105-476.zip` (not an input Word ZIP). Choose `Glossary > NEW > ACC`, inspect 5 evidence rows; choose `ISIC` for 2 rows, `TIMEX` for 452 total with only first 25 shown. Verify no H2 write, no new REST call, browser remains responsive, switching terms cancels old scan, and red errors are explicit if CSV malformed/missing. Do not perform Glossary Import as part of this read-only acceptance.

The seven DBMS generator regression test names are:

```
CanonicalSnapshotMapperTest,UnresolvedCanonicalDatatypeGenerationTest,OracleDdlGeneratorTest,PostgreSqlDdlGeneratorTest,SqlServerDdlGeneratorTest,MySqlDdlGeneratorTest,MariaDbDdlGeneratorTest,Db2LuwDdlGeneratorTest,Db2ZosDdlGeneratorTest,IntegratedSqlRendererTest,MetadataRepositoryResolverTest
```

Tests above assert model and SQL generation contracts in isolation, **not** successful deployment to live databases. Preserve integration/live-DBMS gates separately; don't interpret existing PARTIAL_SUCCESS or SKIPPED artifacts as UI regressions.
