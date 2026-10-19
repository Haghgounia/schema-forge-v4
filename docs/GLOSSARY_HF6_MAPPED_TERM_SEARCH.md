# SchemaForge V4.14.2-HF6 — Mapped-term search parity

## Evidence from the HF5 Windows Maven log

- Production code compiles (343 files) and test code compiles (340 files).
- 15 glossary tests were executed: 14 successful, 1 failure, 0 errors.
- The remaining test is `GlossaryAtomicBootstrapTest` at line 62. The four expected Atomic-worksheet PHRASE keys and both pages were previously checked successfully, yet `search("LEDGER", PHRASE)` returned 0 matches instead of 2.
- The small manual-entry fixture in `GlossarySearchConsistencyTest` passed. This does not establish the root cause for seeded Excel data; no underlying H2/JDBC anomaly has yet been independently reproduced.

## Scope of HF6

The `search` method first loads the visible terms with the canonical `GlossaryTerm` row mapping, and then applies kind/status and case-insensitive literal substring matching to those *mapped fields*. Previously query predicates read JDBC columns independently before mapping. This makes the exact public domain values used by the unfiltered endpoint the only source for text matching. Exclude soft-deleted entries and historical non-Atomic PHRASE records, maintain stable ordering, preserve pagination input bounds, and derive list/count from one set. This strategy is appropriate for the small active glossary baseline (804 terms).

## Diagnostics

The Atomic bootstrap test now checks that the unfiltered PHRASE keys include the two LEDGER entries, then compares the set of those keys with filtered search results. On a mismatch, the error includes observed unfiltered and filtered records to distinguish bad import data from an inconsistent query path. The consistency test now checks both uppercase and lowercase `LEDGER`, not just lowercase.

## Validation and acceptance

The producing environment has Java 21 but lacks an available Maven distribution and cached H2 runtime JAR. This patch is source-level, not a claim of a successful runtime integration. On Windows:

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=GlossaryAtomicBootstrapTest,GlossarySearchConsistencyTest,GlossaryH2AndReportTest,GlossaryOfflineH2ScriptTest,GlossaryRestControllerTest,GlossaryScannerTest,GlossaryStartupWiringTest ^
  test
```

Expect `Tests run: 15, Failures: 0, Errors: 0`, `BUILD SUCCESS`. Then run `mvnw.cmd clean test`. Preserve your existing `data/schemaforge-glossary.mv.db` and do not reimport Excel. If the seeded search still fails, return the detailed assertion output and an archive of the tested source tree; further speculative changes to production would be unjustified.
