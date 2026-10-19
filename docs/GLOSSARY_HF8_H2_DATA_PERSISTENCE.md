# SchemaForge V4.14.2-HF8 — H2 file persistence correction

## Root-cause evidence

HF7 on Windows (2026-10-19): `GLOSSARY_DIAG` shows the packaged Atomic-only Excel imports 946 source rows and 804 unique terms, with the first SQL snapshot reading 804 records and four phrases. Subsequent fresh JDBC connections report **zero rows even for a direct SQL COUNT**; the table still exists. Thus search filtering and pagination are not responsible for this failure.

The resolved H2 engine was **2.3.232**, inherited from Spring Boot 3.5.0. H2 upstream issue [#4247](https://github.com/h2database/h2database/issues/4247) reports a near-identical failure involving database file compaction on close: recently committed rows disappear on reopen while table structures survive. The fix is included in [H2 2.4.240 release notes](https://h2database.github.io/html/changelog.html). These sources establish a *strong matching hypothesis*, but the exact causality of the Windows run needs HF8 integration confirmation.

## HF8 change scope

* `pom.xml`: explicitly pin `com.h2database:h2` to **2.4.240** instead of inheriting 2.3.232, including test/runtime dependency resolution.
* `distribution/bin/bootstrap-glossary-h2.cmd`: default JAR is the Maven-resolved `2.4.240` file, block a known-vulnerable `h2-2.3.232.jar` override; retain CLI-compatible RunScript invocation.
* `GlossaryH2VersionContractTest`: assert H2 runtime version and persistent reopen of a committed row.
* `GlossaryAtomicSnapshotDiagnosticTest`: assert active runtime version and rerun full Atomic 804-row close/reopen snapshot sequence.
* No changes to `GlossaryRepository.search`, `importNew`, schema, existing H2 files, REST, artifact reports or manifest integrity checks.

## Windows acceptance commands

1. Stop SchemaForge and any process using the Glossary H2 file. Back up `data\schemaforge-glossary.mv.db` (and relevant sidecars) **before first opening with the new engine**. Do not delete or reset any curated data. A corrupted existing DB might require restore from a known-good backup.
2. Apply the HF8 Delta on top of HF7. Clear any custom `H2_JAR` overriding the Maven version.
3. Run:

```cmd
cd /d D:\Projects\schema-forge-v4
set "H2_JAR="
mvnw.cmd dependency:resolve
mvnw.cmd -Dtest=GlossaryH2VersionContractTest,GlossaryAtomicSnapshotDiagnosticTest test
```

Expected: `GLOSSARY_H2_VERSION=2.4.240` and `GLOSSARY_DIAG engineVersion=2.4.240`; every snapshot should show `rawTotal=804` / `rawPhrases=4` / `ledgerTotal=2`. If successful, run the full Glossary suite and `mvnw.cmd clean test`.

```cmd
mvnw.cmd clean -Dtest=GlossaryH2VersionContractTest,GlossaryAtomicSnapshotDiagnosticTest,GlossaryAtomicBootstrapTest,GlossarySearchConsistencyTest,GlossaryH2AndReportTest,GlossaryOfflineH2ScriptTest,GlossaryRestControllerTest,GlossaryScannerTest,GlossaryStartupWiringTest test
mvnw.cmd clean test
```

Only after acceptance and database backup should the offline Bootstrap run, if still needed:

```cmd
call distribution\bin\bootstrap-glossary-h2.cmd
```

The offline seed remains idempotent and never intentionally overwrites curated manual approvals. An H2 binary may require a one-way upgrade to the new engine; **never open an upgraded database with the old 2.3.232 JAR**.

## Limitations

This artifact-producing environment cannot resolve/run the Java H2 runtime. SHA/archive/static syntax verification performed here is not a substitute for Windows Maven/H2 tests. Explicit upstream fix strongly supports version correction, but only the Windows integration run can confirm the full HF7 symptom is resolved.
