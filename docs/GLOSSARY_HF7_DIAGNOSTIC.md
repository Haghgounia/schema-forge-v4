# Glossary V4.14.2-HF7 — source parity and H2 repeated-search diagnostics

## Observed evidence

The Windows HF6 run compiled 343 production and 340 test Java files successfully and passed 14/15 glossary tests. `GlossaryAtomicBootstrapTest:52` fails on `assertEquals(4, firstPage.total())`, returning `0` for `search(null, PHRASE, null, 0, 2)`. The same test already verified `search(null, PHRASE, null, 0, 1000).total() == 4` and matched all four Atomic-worksheet phrase keys.

In the supplied HF6 `GlossaryRepository.search`, **`total` is assigned to `matches.size()` before page slicing** and page size is only used to reject invalid sizes and choose a sublist. A change of size from 1000 to 2 cannot change `total` given the same visible database rows and same binary. Therefore the log alone does not justify another production search rewrite. Differences in connected database state, class/source versions, or connection lifetime need direct measurement.

## Scope

This is intentionally an *investigation-only* patch. It adds:

- One isolated JUnit integration diagnostic `GlossaryAtomicSnapshotDiagnosticTest`: fresh `@TempDir` H2 file, packaged `01_Atomic_Terms.xlsx`, 946/804 import checks, direct SQL counts and repeated `GlossaryRepository.search` calls using page sizes 1000/2/10, first and second page checks, LEDGER lookup; compares behavior with/without an extra open connection.
- `distribution/bin/diagnose-glossary-h2.cmd`: prints SHA-256 of installed Repository source, then runs diagnostic test.

It does NOT modify production Java, baseline spreadsheet, H2 schema, REST API, batch generation, existing tests, or user's persistent `data/schemaforge-glossary.mv.db`.

## Expected source hashes for HF6

```
GlossaryRepository.java           3f7caaea204ed475616d4a1162cd7f170a92f98250c6b063d7a07522710e600c
GlossaryAtomicBootstrapTest.java  28c4746373e595e179b160ef21ef7e8027ddf43fe467e0560dcdc787968f3ef9
GlossarySearchConsistencyTest.java 6cfc09f1f2bc69212f8e0a8174ea30e606a7e01206d99af0121d5db7a4edfb46
```

A mismatched source hash indicates the executed project tree may not match the packaged HF6 source. The class-byte SHA emitted by the diagnostic is supplemental and is not the same hash as source text.

## Execute on Windows

From project root (after applying HF7 delta):

```cmd
cd /d D:\Projects\schema-forge-v4
call distribution\bin\diagnose-glossary-h2.cmd
```

Or directly:

```cmd
mvnw.cmd clean -Dtest=GlossaryAtomicSnapshotDiagnosticTest test
```

Review all lines beginning `GLOSSARY_DIAG`, particularly `SNAPSHOT`. They report `rawTotal`, `rawPhrases`, `allTotal`, `allKeys`, `firstTotal`, `firstItems`, `secondTotal`, `secondItems`, and `ledgerTotal`. Their expected values are `804, 4, 4, {four exact keys}, 4, 2, 4, 2, 2` for all five snapshots.

If raw SQL changes between snapshots, investigate connection/storage lifetime or test environment. If raw SQL stays 804/4 but API results change, inspect compiled class identity and JDBC mapping using reported SHA and paths. If all five snapshots pass while `GlossaryAtomicBootstrapTest` continues to fail, inspect test ordering and the deployed source tree; do not silently relax the failing assertion.

No Java/H2 integration tests ran on the artifact-producing environment; only syntax compilation against project-shaped stubs and archive checks were performed.
