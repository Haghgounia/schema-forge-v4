# SchemaForge V4.14.2 HF2 — Glossary source-closure repair

**Base:** User-supplied `schema-forge-v4-2026-10-19-1146.zip` (SHA-256: `e95b5d9b09d1d5b254d66e7cb25f57f7f9dd71e550eca4c8c3844412cd8aaa20`).

## Root cause

The base archive had only five glossary production Java files. Thirteen referenced glossary types
(e.g., `GlossaryTerm`, `GlossaryScanner`, `GlossaryWriteGuard`) were absent, producing 46
`cannot find symbol` errors in the Java compiler. The base also missed H2's Maven runtime dependency,
Glossary-to-Artifact integration, REST exception mapping, artifact enum updates, and two tests.

## Repair scope

- Restored missing glossary sources and test classes from the matching V4.14.2-HF1 full archive.
- Restored the H2 dependency and the optional glossary report integration for standard, batch and EA output.
- Restored Glossary-specific REST error codes and the `GLOSSARY_REPORT` artifact type.
- Preserved the already-correct H2 RunScript `-charset` fix and the Atomic-only Excel/SQL seed.
- Restored the correct zero-width-joiner filename in the Legacy Word regression fixture, whose bytes
  match the original fixture, to prevent a follow-on test resource lookup failure.

No other original source files were altered. The 01_Atomic_Terms reference is the only active Excel source;
there is no import from 02_Canonical_Phrases.

## Windows installation

1. Back up the existing project folder, especially `data/schemaforge-glossary.mv.db` if it exists.
2. Extract the **full** HF2 archive into `D:\Projects\schema-forge-v4` or apply the HF2 delta at
   the project root, with overwrite enabled. Do not replace the data folder with an older copy.
3. Run the following two gates:

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean -Dtest=GlossaryScannerTest,GlossaryAtomicBootstrapTest,GlossaryOfflineH2ScriptTest,GlossaryH2AndReportTest,GlossaryRestControllerTest,GlossaryStartupWiringTest test
mvnw.cmd clean test
```

4. Once compile and tests pass, if H2 was not yet created:

```cmd
call distribution\bin\bootstrap-glossary-h2.cmd
```

The expected initial glossary (with an empty database) is 800 ATOMIC + 4 PHRASE = 804 CANDIDATE rows,
all sourced exclusively from `01_Atomic_Terms`.

## Validation caveat

The repair was reconstructed and statically verified in the assistant sandbox; a full Maven/Spring/H2
run was unavailable there because Maven dependencies could not be downloaded. The user-side Maven
log remains the final confirmation of full Java compilation and H2 integration.
