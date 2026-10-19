# SchemaForge V4.14.2-HF5 — Consistent glossary list/count retrieval

## Empirical evidence
Two different database-level pagination forms produced a nonzero `PHRASE` COUNT (4) and an empty page in successive Windows Maven runs: HF3's `LIMIT/OFFSET` and HF4's `OFFSET/FETCH`. H2 accepted both statements without throwing an exception. The cause cannot be proven from logs alone; blaming either syntax was premature.

## Change
`GlossaryRepository.search` now scans one JDBC result set over glossary rows, applies the same Java filters for active rows, kind/status, and case-insensitive literal substring, then sorts by `(term_key,kind)` and slices the page. Count and list are derived from **one snapshot**. No SQL-level pagination or separate count statement is executed.

- Only non-deleted entries are visible.
- Legacy `ECOBANK_XLSX` PHRASE entries remain excluded, while Atomic terms and explicitly reimported Atomic worksheet phrases remain included.
- Search treats `%`, `_` and `!` literally and handles English and Persian strings.
- Page size 1..1000, page >=0, and checked multiplication are retained.
- Total memory is proportional to active matching entries (804 baseline). Inactive historical entries are streamed and not materialized.
- This is acceptable for the deliberately compact glossary. If the active dictionary grows to hundreds of thousands of entries, a benchmarked database-native pagination implementation can be reconsidered after isolated H2/JDBC diagnostics.

## No migration
The H2 file under `data/schemaforge-glossary.mv.db` remains untouched. Do not delete it; do not re-import Excel. Existing status edits remain authoritative.

## Acceptance gate on Windows

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=GlossaryAtomicBootstrapTest,GlossarySearchConsistencyTest,GlossaryH2AndReportTest,GlossaryOfflineH2ScriptTest,GlossaryRestControllerTest,GlossaryScannerTest,GlossaryStartupWiringTest ^
  test
```

Expect 15 tests, no failures or errors, and BUILD SUCCESS, then `mvnw.cmd clean test`.

The producing environment did not execute H2 integration tests. Source and packaging validation must not be described as a successful full integration test.
