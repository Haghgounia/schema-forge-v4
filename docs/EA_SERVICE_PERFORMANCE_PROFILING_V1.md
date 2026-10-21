# SchemaForge V4 – EA/XML Service Profiling V1 (diagnostic only)

## Scope and baseline

Base: Glossary V4.14.4-HF1 source. This patch changes **only timing diagnostics** for EA import and generation. It does not replace the XML parser, does not change the generated canonical JSON/SQL/manifest, and does not change H2 data.

Sample source: `Deposit_Account_Operations_6_RC_2026-09-22(4).xml` (6,370,350 bytes, SHA-256 `61d42f5a7dfe57a5e48d2849afe1310450476442cb5d53b7ba800e58dfc296ce`). Measured on Linux, Java 21, ten warmups and thirty measured parses:

- Tables: **114**, columns: **1,767**, foreign keys: **188**, indexes: **206**, unique keys: **90**.
- First cold parse: **1,130.492 ms**.
- Warm parse: median **288.601 ms**, mean **306.647 ms**, P95 **457.012 ms**, min **209.000 ms**.
- This is the real Java parser and schema-model build, **not the REST service total**.

The uploaded earlier `(3).xml` is byte-identical to `(4).xml`.

## What the new logs measure

All log entries are single-line `EA_PERF section=...` messages, and are emitted only with `SCHEMAFORGE_EA_PERF_ENABLED=true` or JVM property `-Dschemaforge.ea.perf.enabled=true`:

- `section=parse`: native XML/DOM, normalization, EA extension index, discovery/decoding tables, associations, model build, canonical normalization.
- `section=prepare`: actual parser execution (`parser_ms`) and schema enrichment + validation (`validation_enrichment_ms`).
- `section=platform` (one for each selected DBMS): metadata validation, preloading live tables, aggregate per-table DDL validation/rendering/file writes, per-table comparison reports, migration, integrated `RUN_ALL` SQL, and DBMS total.
- `section=artifacts`: total selected-platform loop, CRUD, diagrams, canonical JSON, summary, glossary reports, manifest.
- `section=package`: artifact generation vs ZIP compression.
- `section=cleanup`: temporary directory deletion.
- `section=request`: full application service time spent preparing and generating the output; HTTP upload/download transport and network latency are outside this measure.

All measurements use monotonic `System.nanoTime()` for elapsed wall time. DBMS I/O/wait is included if invoked. Timers do not alter artifacts.

## Windows 10 reproduction

1. Apply the **Delta ZIP** over V4.14.4-HF1, retaining existing `data/*.mv.db`; no H2 files are in the patch.
2. Test compile and regression:

   ```cmd
   cd /d D:\Projects\schema-forge-v4
   mvnw.cmd clean -Dtest=EnterpriseArchitectXmlParserTest,EnterpriseArchitectFkAssociationRecoveryTest,EaGenerationOrchestratorTest,SchemaForgeEaPerTableOutputTest,EaPlatformSelectionAcceptanceTest test
   ```

3. Stop existing Spring Boot (port 9090). In its CMD window enable profiling *before* restart:

   ```cmd
   cd /d D:\Projects\schema-forge-v4
   set "SCHEMAFORGE_EA_PERF_ENABLED=true"
   if not exist logs\ea-performance mkdir logs\ea-performance
   mvnw.cmd spring-boot:run > logs\ea-performance\ea-performance.log 2>&1
   ```

   Preserve the same `SCHEMAFORGE_GLOSSARY_ENABLED`, metadata repository / live-DB settings, and other configuration you normally use: performance depends on them. You may disable Glossary only if you explicitly want to compare runs with and without it.

4. In another CMD window (replace the sample file path with the location of the provided EA XML on your machine):

   ```cmd
   cd /d D:\Projects\schema-forge-v4
   if not exist test-output\ea mkdir test-output\ea
   curl.exe -sS -f -X POST "http://localhost:9090/api/v1/generate/ea-xml?platform=all&auditFields=source&timestampSemantics=source" -F "file=@D:\Sample-Docs\EA\Deposit_Account_Operations_6_RC_2026-09-22.xml;type=application/xml" -o test-output\ea\ea-perf-all.zip -w "HTTP=%{http_code} elapsed=%{time_total}s\n"
   curl.exe -sS -f -X POST "http://localhost:9090/api/v1/generate/ea-xml?platform=oracle&auditFields=source&timestampSemantics=source" -F "file=@D:\Sample-Docs\EA\Deposit_Account_Operations_6_RC_2026-09-22.xml;type=application/xml" -o test-output\ea\ea-perf-oracle.zip -w "HTTP=%{http_code} elapsed=%{time_total}s\n"
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File distribution\bin\summarize-ea-perf.ps1 -LogPath "D:\Projects\schema-forge-v4\logs\ea-performance\ea-performance.log"
   ```

5. Repeat each request at least 3 times **after warmup** with the **same settings and same source**. Submit `logs/ea-performance/ea-performance.log`, `logs/ea-performance/ea-performance-summary.csv`, and the HTTP timings. To benchmark cold latency, restart the service and time the very first request separately.

## Acceptance and diagnostic decision

Do **not** automatically replace DOM with SAX/StAX. Rank `section=platform` times and `section=artifacts` plus `section=package` first. If metadata/preload dominates, investigate database connections and repeated catalog reads. If `ddl_ms` or `run_all_ms` dominates, investigate schema-wide passes within per-table work. If comparison/migration dominates, investigate workbook/report generation and live metadata round trips. If the manifest/ZIP dominates, examine file counts, hashing, and compression. If `validation_enrichment_ms` dominates, look at spell-check and enrichers. Only optimize the XML parser if its share becomes material on production files.

Do not compare parser percentages with the 7-DBMS total before measuring the latter. The aggregated per-platform `ddl_ms` **includes canonical/dialect validation and file writes**; it is not solely the SQL renderer. The `platforms_ms` field includes per-platform bookkeeping in addition to the logged substeps.

### Status

Java 21 parser and timing-helper compile successfully in this environment. Full Maven/Spring Boot service tests **cannot be executed in this sandbox** and must be run on Windows. This instrumentation is therefore **diagnostic patch awaiting Windows compile and live endpoint acceptance**, not a performance improvement claim.
