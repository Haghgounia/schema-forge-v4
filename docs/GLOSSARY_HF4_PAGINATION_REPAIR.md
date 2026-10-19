# SchemaForge V4.14.2-HF4 — Glossary H2 paged retrieval correction

## Evidence from the 2026-10-19 Windows regression run

All 343 Production and 339 Test Java files compiled. Of 14 selected Glossary tests, 13 passed. The only failure was `GlossaryAtomicBootstrapTest` line 40, where the repository returned a nonzero `PHRASE` count (4) but an empty `items` list when the same data was requested with `page=0,size=1000`. The importer returned 946 Atomic source rows and 804 unique entries and the test confirmed 800 ATOMIC and 4 PHRASE rows in search COUNT queries. Other H2, REST, scanner, manifest and Spring wiring tests passed.

This evidence isolates the issue to result retrieval/pagination, not proof of failed XLSX parsing or persistence. Without JDBC SQL tracing, the exact behavior of H2's former LIMIT/OFFSET parameter binding is not definitively established.

## Targeted change

`GlossaryRepository.search` now uses H2's documented SQL-standard pagination:

```sql
SELECT ... FROM glossary_term
WHERE is_deleted=FALSE AND ...
ORDER BY term_key ASC, kind ASC
OFFSET <validated page * size> ROWS
FETCH NEXT <validated size> ROWS ONLY
```

Only the bounded integer offset and size are appended as numeric SQL literals; user-supplied query text and kind/status filters remain safely bound in PreparedStatement parameters. The existing guard `page >= 0`, `1 <= size <= 1000`, and integer-overflow validation remains unchanged. Pagination ordering remains stable because `(kind,term_key)` has a unique constraint.

The baseline acceptance test explicitly checks the 4 expected PHRASE keys, returns pages 0, 1, and 2 of size 2, checks `q=LEDGER` and literal underscore filtering, and verifies all 800 ATOMIC terms are returned on page 0 size 1000. This exercises the formerly untested `GlossaryPage.items()` branch.

H2 reference: https://h2database.github.io/html/commands.html (SELECT -> OFFSET/FETCH).

## No migration needed

This patch does not change import logic, seed SQL, table schema, row statuses, source tags, artifact reporting, or REST JSON shapes. **Do not delete/recreate `data/schemaforge-glossary.mv.db`, and do not re-import Excel.** If already present, back up production data before application maintenance as usual.

## Validation on Windows

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=GlossaryAtomicBootstrapTest,GlossaryH2AndReportTest,GlossaryOfflineH2ScriptTest,GlossaryRestControllerTest,GlossaryScannerTest,GlossaryStartupWiringTest ^
  test
```

Acceptance: `Tests run: 14, Failures: 0, Errors: 0` and `BUILD SUCCESS`. Then execute `mvnw.cmd clean test` for the broader regression gate.

The authoring environment does not have H2 and Maven runtime dependencies; the final result of the H2 integration tests is not claimed until confirmed by the Windows run.
