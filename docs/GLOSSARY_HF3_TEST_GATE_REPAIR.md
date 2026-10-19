# SchemaForge V4.14.2-HF3 | Targeted Glossary test-gate repair

## Source and observed run

This patch applies to V4.14.2-HF2 (the full archive or its applied delta). The user-side test run on 2026-10-19 compiles 343 production and 339 test files but reports **2 execution errors from 14 tests**:

1. `GlossaryAtomicBootstrapTest:40` — lookup `PHRASE:GENERAL_LEDGER` returns empty after the 946-row/804-distinct Atomic XLSX import.
2. `GlossaryH2AndReportTest:76` — strict Artifact Manifest detects the separate H2 file `term-db.mv.db` under the artifact output root.

No failure in production code compilation was reported in this run.

## Fixes

**Import persistence:** `GlossaryRepository.importNew` now executes explicit kind/key lookups and inserts within one JDBC transaction rather than batched `INSERT ... SELECT ... WHERE NOT EXISTS` operations. The import retains all existing statuses, translations and manual source markers, and reactivates only legacy phrase-source tags explicitly found in the Atomic sheet. Records previously soft-deleted stay deleted. A malformed normalized input is rejected before insertion; any SQL error rolls back all changes. This removes a possible database-batch/parameter ambiguity, but the supplied log alone does not establish why the previous GENERAL_LEDGER lookup was missing.

**Baseline test diagnostics:** The atomic bootstrap test checks **all four** expected multiword Atomic-sheet entries (`GENERAL_LEDGER`, `SUBSIDIARY_LEDGER`, `ADVANCE_PAYMENT`, `BALANCE_SHEET`) and verifies that each has `ECOBANK_ATOMIC_XLSX` as its source. A future discrepancy prints the actual set of stored phrase keys instead of only throwing a bare `NoSuchElementException`.

**Artifact test isolation:** The H2 integration test continues to store its H2 database under the temporary test directory, while reports and `manifest.json` are written only beneath a separate `artifacts/` folder. Production `ArtifactManifestAssembler.validatePreManifestFiles` is not modified. It remains fail-closed for untracked generated files.

## Verification and limitations

- Java 21 syntax-compilation of the changed production repository and its glossary dependencies succeeds using Spring/JDBC stubs.
- A static parse of the authoritative XLSX and distribution H2 seed SQL confirms 946 Atomic rows and 804 unique entries, including all four multiword terms.
- H2 2.3.232 and full Maven dependencies are not accessible in the authoring sandbox. These tests must be executed against the installed dependencies on the Windows host; their success is not claimed.

## Windows validation gate

First back up any existing `data/schemaforge-glossary.mv.db`. Do not delete it; this patch changes Java import logic and test fixtures, not the H2 schema.

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=GlossaryAtomicBootstrapTest,GlossaryH2AndReportTest,GlossaryOfflineH2ScriptTest,GlossaryRestControllerTest,GlossaryScannerTest,GlossaryStartupWiringTest ^
  test
```

If the targeted gate succeeds, continue with:

```cmd
mvnw.cmd clean test
```

Run the H2 bootstrap script only when the actual project database still needs to be created; JUnit tests use their own temporary H2 files and never rewrite the project's persisted glossary database.
