## 2026-08-23 - C11 final consolidation verification candidate

- Started only after C8.10-R1 passed targeted `43/43` and full `554 / 0 / 0 / 4`, and after source-unchanged C9/C10 completion.
- C11 introduces no source/test/runtime behavior change; exact source remains `276` main / `189` test Java with fingerprint `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`.
- Defined a 95-test targeted consolidation gate spanning C4-C8 contracts, REST, producers/orchestrators and Word/Legacy/ZIP/EA regression paths, followed by exact-source `mvnw.cmd clean test`.
- Live-validation states remain governed by C9; test availability is not reported as a live pass.
- C11 is `VERIFICATION CANDIDATE / PENDING USER REGRESSION`; C8.10 remains the official source baseline until both C11 gates pass.

## 2026-08-23 - C10 Documentation Consolidation complete

- Audited the authoritative current reference/index set after C8 and C9.
- Corrected the reference entry point from stale C8.2 `530` regression evidence to the official C8.10 `554 / 0 / 0 / 4` result.
- Updated Architecture from four to five logical-DDL platforms and added `MySqlDialect`; intentionally four-DBMS physical-contract wording remains unchanged where MySQL physical design is deferred.
- Linked Artifact Contract V1, Naming/Layout, Standard Manifest, REST Contract, C8 decomposition, C9 test matrix, current baseline, roadmap and C10 record from the authoritative reference entry point.
- No `src/main`, `src/test`, POM, SQL or runtime behavior changed; C8.10 source fingerprint remains `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`.
- C11 Final Consolidation Regression / Baseline Freeze is the only remaining consolidation stage.

## 2026-08-23 - C9 Test Matrix / Live-Validation Classification complete

- Added authoritative `docs/testing/TEST-MATRIX-C9.md` plus row-level `TEST-MATRIX-C9.csv` covering all `189` Java files under `src/test/java`.
- Classified `107` standard unit/contract, `38` standard offline-integration, `4` configuration-gated directory execution, `25` opt-in offline `*IT`, `9` live DB `*IT`, and `6` support/helper files.
- Formalized evidence states so `LIVE_TEST_AVAILABLE` never implies `LIVE_TEST_EXECUTED_AND_PASSED`.
- Defined normal-change, DBMS-specific, migration, CRUD, corpus/parser, Db2/zOS and C11 release-freeze gate policy.
- C9 is documentation/test-governance only: no file under `src/main` or `src/test` changed; C8.10 remains the official source baseline with fingerprint `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`.
- C10 Documentation Consolidation is now the next roadmap stage.

## 2026-08-23 - C8.10 official / C8 service decomposition complete

- C8.10-R1 user-side targeted regression passed `43 / 0 / 0 / 0` with `BUILD SUCCESS` at `2026-08-23T06:15:32-07:00`.
- Full clean regression passed `554 / 0 / 0 / 4` with `BUILD SUCCESS` at `2026-08-23T06:22:23-07:00`.
- Exact verified inventory: `276` main Java / `189` test Java.
- C8.10 `ArtifactGenerationService` is DONE / USER-VERIFIED / FROZEN; the initial compile-failed C8.10 candidate and one-import R1 repair remain preserved as history.
- Official baseline: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.10`.
- Frozen source fingerprint: `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`.
- C8 API/Application Service Decomposition is complete. C9 Test Matrix / Live-Validation Classification is the next roadmap stage.

## 2026-08-23 - C8.10-R1 PreparedSchema import compile repair

- First C8.10 targeted attempt stopped during main compilation before Surefire: `cannot find symbol: class PreparedSchema` in `SchemaForgeApiService` line 159; Maven finished `BUILD FAILURE` at `2026-08-23T06:03:25-07:00`.
- Root cause: C8.10 moved Standard/Legacy single-document packaging out of the facade and removed the `PreparedSchema` import even though the EA facade path still declares `PreparedSchema prepared = eaGenerationOrchestrator.prepare(...)`.
- R1 restores only `import com.behsazan.schemaforge.application.PreparedSchema;` in `SchemaForgeApiService`; no method body, test source, parser, DBMS, DDL, manifest, naming, producer, REST, or archive behavior changes.
- Inventory remains `276` main Java / `189` test Java; expected targeted `43`; expected full `554 / 0 / 0 / 4`.
- R1 source fingerprint: `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`.
- C8.10-R1 is `REPAIR CANDIDATE / PENDING USER RE-RUN`; C8.9 remains the official baseline and C9 remains blocked.

## 2026-08-23 - C8.10 ArtifactGenerationService extraction candidate

- Started only after C8.9 passed targeted `42/42` and full `551 / 0 / 0 / 4` and was frozen as the official C8.9 checkpoint.
- Extracted the shared Standard Word/Legacy Word workspace, upload-transfer, manifest, package and cleanup workflow from `SchemaForgeApiService` into `ArtifactGenerationService`.
- Preserved request validation/source normalization/context creation in the facade and preserved the existing `DocumentGenerationOrchestrator`, C5 paths, C6 manifest contract, Ledger semantics and ZIP packaging behavior.
- `SchemaForgeApiService` reduced from `244` to `210` lines; new `ArtifactGenerationService` is `110` lines.
- Added `ArtifactGenerationServiceTest` with 3 focused tests for Standard Word manifest/package cleanup, Legacy schema delegation and cleanup on generation failure.
- Candidate inventory: `276` main Java / `189` test Java; expected targeted `43`; expected full Surefire count `554`; source fingerprint `dfe575066ace7ac8de555e9f1a561c00f1a0b217d54b6c8147680d1aa552db40`.
- The first user-side C8.10 targeted attempt failed during main compilation before tests because the facade still referenced `PreparedSchema` after its import was removed. This candidate is superseded by C8.10-R1; C8.9 remains official.

## 2026-08-23 - C8.9 EaGenerationOrchestrator official verification

- User-verified targeted C8.9 regression: `42/42`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T05:39:34-07:00`.
- User-verified full clean regression: `551 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T05:43:05-07:00`.
- Full build compiled `275` main Java and `188` test Java source files, confirming the intended C8.9 source was exercised.
- Promoted exact candidate source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.9`.
- Official source fingerprint: `9ef7e1315e82b86817864d94431b20ce02a367ca1777c622dd5a5f7102db6898`.
- C8.9 is DONE / USER-VERIFIED. Roadmap inventory leaves the named `ArtifactGenerationService` boundary for C8.10 before C8 can close and C9 can start.

## 2026-08-23 - C8.9 EaGenerationOrchestrator extraction candidate

- Started only after C8.8-R1 passed repaired targeted `39/39` and full `548 / 0 / 0 / 4` and C8.8 was frozen.
- Extracted Enterprise Architect XML/XMI preparation and multi-table artifact orchestration from `SchemaForgeApiService` into `EaGenerationOrchestrator`.
- Preserved EA parser/schema override semantics, preparation, per-table DDL across all five DBMS, PostgreSQL lowercase EA artifact names, metadata comparison, migration, CRUD, diagrams, canonical JSON, dependency ordering/run-all, EA manifest extension and Ledger producer identities.
- `SchemaForgeApiService` reduced from `565` to `244` lines; new `EaGenerationOrchestrator` is `418` lines.
- Added `EaGenerationOrchestratorTest` with 3 focused tests for dependency-order/run-all+manifest, schema override/PostgreSQL naming, and legacy run-script Ledger producer identity across all five DBMS.
- Candidate inventory: `275` main Java / `188` test Java; expected full Surefire count `551`; source fingerprint `9ef7e1315e82b86817864d94431b20ce02a367ca1777c622dd5a5f7102db6898`.
- C8.9 is `PENDING REGRESSION`; C8.8 remains official.

## 2026-08-23 - C8.8 BatchGenerationOrchestrator official verification

- User-verified repaired targeted C8.8-R1 regression: `39/39`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T05:21:18-07:00`.
- User-verified full clean regression: `548 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T05:24:08-07:00`.
- Full build compiled `274` main Java and `187` test Java source files, confirming the intended C8.8-R1 source was exercised.
- Promoted exact R1 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.8`.
- Official source fingerprint: `b8fb0a328ef0ad382e963227a050e14f8f128cadc2a2ff38afd55eb05379b396`.
- C8.8 is DONE / USER-VERIFIED; the next C8 extraction must be selected by source inventory before coding.

## 2026-08-23 - C8.8-R1 BatchGenerationOrchestrator test assertion repair

- First C8.8 targeted run compiled the expected `274` main / `187` test Java sources, then finished `39 / 1 / 0 / 0` with one failure in `BatchGenerationOrchestratorTest.batchGenerationPreservesFaultIsolationDiagnosticsManifestAndLedgerIdentity` at line 74.
- Root cause is test-only: the assertion filtered every `SUMMARY_REPORT`/`ERROR_REPORT` in the merged batch Ledger and incorrectly required producer `SchemaForgeApiService`; valid batch Mermaid/Graphviz and per-document CRUD summary descriptors intentionally use their own producers.
- R1 narrows that assertion to descriptors whose logical name is exactly `batch-generation`, asserts exactly two batch diagnostic descriptors, and preserves the legacy producer assertion for those two records.
- `src/main` is byte-for-byte unchanged from the C8.8 extraction candidate; only `BatchGenerationOrchestratorTest.java` changes under `src`.
- R1 inventory remains `274` main Java / `187` test Java with expected full Surefire count `548`.
- R1 source fingerprint: `b8fb0a328ef0ad382e963227a050e14f8f128cadc2a2ff38afd55eb05379b396`.
- C8.8-R1 is `PENDING USER RE-RUN`; C8.7 remains the official baseline.

## 2026-08-23 - C8.8 BatchGenerationOrchestrator extraction candidate

- Started only after C8.7 passed targeted `35/35` and full `545 / 0 / 0 / 4` and was frozen.
- Moved only ZIP-batch orchestration from `SchemaForgeApiService` to `BatchGenerationOrchestrator`; request validation remains in the facade.
- Preserved `BatchArchiveSupport`, `ArtifactPackageBuilder`, `DocumentGenerationOrchestrator`, collision remap, diagnostics, aggregate diagrams, Standard Manifest V1, archive paths, generationId/timestamp and Ledger producer semantics.
- Added `BatchGenerationOrchestratorTest` with 3 focused tests.
- `SchemaForgeApiService` reduced from 648 to 565 lines.
- Candidate inventory: `274` main Java / `187` test Java; source fingerprint `779e3751ccb29e90885027b3dce3b94e62a422e7c67003d25a38bcda63a6dc31`; expected full Surefire count `548`.
- C8.8 is PENDING REGRESSION; C8.7 remains official.

## 2026-08-23 - C8.7 DocumentGenerationOrchestrator official verification

- User-verified targeted C8.7 regression: `35/35`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T04:52:16-07:00`.
- User-verified full clean regression: `545 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T04:54:29-07:00`.
- Full build compiled `273` main Java and `186` test Java source files, confirming the intended C8.7 candidate was exercised.
- Promoted exact C8.7 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.7`.
- Source fingerprint: `a8e0f2d94895ab2142ac8bc88aefa7f5b8d4029df2bf3ea297b883f076add57d`.
- C8.7 is DONE / USER-VERIFIED; C8.8 `BatchGenerationOrchestrator` is NEXT.

## 2026-08-23 - C8.7 DocumentGenerationOrchestrator extraction candidate

- Started only after C8.6 passed targeted `34/34` and full `542 / 0 / 0 / 4` and was frozen.
- Extracted shared Standard Word/Legacy Word orchestration into `DocumentGenerationOrchestrator`; parser/recovery implementations remain unchanged.
- Preserved the existing canonical preparation pipeline, five-DBMS DDL/metadata loop, Migration/Comparison/CRUD/Diagram producer dispatch, canonical JSON aggregation, C5 paths, Ledger semantics, C6 manifest and C7 REST behavior.
- `SchemaForgeApiService` reduced from `733` to `648` lines; `DocumentGenerationOrchestrator` is `198` lines.
- Added `DocumentGenerationOrchestratorTest` with 3 focused tests for five-DBMS CREATE+JSON output, metadata-unavailable producer skips, and Legacy schema preservation.
- Candidate inventory: `273` main Java / `186` test Java; source fingerprint `a8e0f2d94895ab2142ac8bc88aefa7f5b8d4029df2bf3ea297b883f076add57d`; expected full Surefire count `545`.
- C8.7 is PENDING REGRESSION; C8.6 remains official.

## 2026-08-23 - C8.6 ArtifactPackageBuilder official verification

- User-verified targeted C8.6 regression: `34/34`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T04:22:40-07:00`.
- User-verified full clean regression: `542 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T04:24:52-07:00`.
- Full build compiled `272` main Java and `185` test Java source files, confirming the intended C8.6 candidate was exercised.
- Promoted exact C8.6 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.6`.
- Source fingerprint: `a5f01c4c0fa180632743ae6be7a4d7cd0b49f618c1eb087a09bd0375658e8afb`.
- C8.6 is DONE / USER-VERIFIED; C8.7 `DocumentGenerationOrchestrator` is NEXT.

## 2026-08-23 - C8.6 ArtifactPackageBuilder extraction candidate

- Started only after C8.5 passed targeted `39/39` and full `539 / 0 / 0 / 4` and was frozen.
- Extracted only common directory ZIP packaging, path normalization, and best-effort recursive cleanup from `SchemaForgeApiService` into `ArtifactPackageBuilder`.
- Preserved ZIP entry relative paths/content, Word/Legacy/ZIP/EA orchestration, C5 naming, C6 manifest behavior, and all producer semantics.
- `SchemaForgeApiService` reduced from `756` to `733` lines; `ArtifactPackageBuilder` is `49` lines.
- Added `ArtifactPackageBuilderTest` with 3 focused tests for ZIP path/content, forward-slash normalization, and idempotent recursive cleanup.
- Candidate inventory: `272` main Java / `185` test Java; source fingerprint `a5f01c4c0fa180632743ae6be7a4d7cd0b49f618c1eb087a09bd0375658e8afb`; expected full Surefire count `542`.
- C8.6 is PENDING REGRESSION; C8.5 remains official.

## 2026-08-23 - C8.5 BatchArchiveSupport official verification

- User-verified targeted C8.5 regression: `39/39`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T04:05:20-07:00`.
- User-verified full clean regression: `539 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T04:08:54-07:00`.
- Full build compiled `271` main Java and `184` test Java source files, confirming the intended C8.5 candidate was exercised.
- Promoted exact C8.5 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.5`.
- Source fingerprint: `49eaad1760cd693a32fb0f9571b404cd395d679a1b45d084cc30ee33eb80ae0f`.
- C8.5 is DONE / USER-VERIFIED; C8.6 `ArtifactPackageBuilder` is NEXT.

## 2026-08-23 - C8.5 BatchArchiveSupport extraction candidate

- Started only after C8.4 passed targeted `44/44` and full `536 / 0 / 0 / 4` and was frozen.
- Extracted only ZIP-batch filesystem/ledger helper mechanics into `BatchArchiveSupport`; `generateFromZipTracked(...)` remains in `SchemaForgeApiService`.
- Preserved zip-slip rejection, DOCX filtering/order, collision-safe C5 path remapping, generationId/provenance, batch CSV/error content, manifest behavior, and archive semantics.
- `SchemaForgeApiService` reduced from `872` to `756` lines.
- Added `BatchArchiveSupportTest` with 3 focused tests.
- Candidate inventory: `271` main Java / `184` test Java; source fingerprint `49eaad1760cd693a32fb0f9571b404cd395d679a1b45d084cc30ee33eb80ae0f`; expected full Surefire count `539`.
- C8.5 is PENDING REGRESSION; C8.4 remains official.

## 2026-08-23 - C8.4 CrudArtifactProducer extraction official

- Started only after C8.3 passed targeted `52/52` and full `533 / 0 / 0 / 4` regression and was frozen.
- Extracted metadata-based Oracle/SQL Server CRUD artifact orchestration from `SchemaForgeApiService` into `CrudArtifactProducer`.
- Preserved Oracle/SQL Server CRUD generators, grant-derived options, metadata lookup/fallback, summary CSV values, C5 paths, media types, and Artifact Ledger producer identity.
- `SchemaForgeApiService` reduced from `1024` to `872` lines.
- Added `CrudArtifactProducerTest` with three focused cases: no-PK skip/summary, successful Oracle+SQL Server generation including configured grants, and generator-failure capture without aborting the summary.
- Candidate inventory: `270` main Java / `183` test Java; source fingerprint `8f134a74c2967a3c005b0250280d09cb75854d0c185f9485de947a749b2e8c57`.
- User verification passed: targeted `44 / 0 / 0 / 0`; full `536 / 0 / 0 / 4`, BUILD SUCCESS. C8.4 is frozen and C8.5 is next.

## 2026-08-23 - C8.3 ComparisonArtifactProducer official verification

- User-verified targeted C8.3 regression: `52/52`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T03:33:02-07:00`.
- User-verified full clean regression: `533 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T03:35:39-07:00`.
- Full build compiled `269` main Java and `182` test Java source files, confirming the intended C8.3 candidate was exercised.
- Promoted exact C8.3 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.3`.
- Source fingerprint: `90b8fcb7c8a2998b0aa878e01b59bb1d77f6916560514ce4e65dc2afdc927cab`.
- C8.3 is DONE / USER-VERIFIED; C8.4 CRUD artifact extraction is NEXT.

## 2026-08-23 - C8.3 ComparisonArtifactProducer extraction candidate

- Started only after C8.2-R1 passed targeted `45/45` and full `530 / 0 / 0 / 4` regression and was frozen.
- Moved `writeComparisonWorkbooks(...)` and `writeEaComparisonWorkbook(...)` out of `SchemaForgeApiService` into `ComparisonArtifactProducer`.
- Preserved `SchemaCompareExcelWriter`, metadata table resolution/fallback, usage-frequency mapping, logical/physical workbook comparison semantics, C5 comparison paths, media type, and ledger producer string.
- Preserved EA PostgreSQL comparison filenames as lowercase, matching the pre-extraction `eaArtifactBaseName(...)` behavior.
- Reduced `SchemaForgeApiService` from `1149` to `1024` lines.
- Added `ComparisonArtifactProducerTest` with 3 focused tests.
- Candidate inventory: `269` main Java / `182` test Java; expected full Surefire count `533`; fingerprint `90b8fcb7c8a2998b0aa878e01b59bb1d77f6916560514ce4e65dc2afdc927cab`.
- C8.3 is DONE / USER-VERIFIED and is the official baseline; C8.4 CRUD extraction is NEXT.

## 2026-08-23 - C8.2 MigrationArtifactProducer official verification

- User-verified targeted C8.2-R1 regression: `45/45`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T03:12:56-07:00`.
- User-verified full clean regression: `530 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T03:16:46-07:00`.
- Promoted exact C8.2-R1 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.2`.
- Frozen source inventory: `268` main Java / `181` test Java.
- Frozen source fingerprint: `aa77b6bfe9248ebe7b061b2cd39a75ece5e34e765fd121a0ea62d1701a6f1e14`.
- C8.2 is DONE / USER-VERIFIED; C8.3 `ComparisonArtifactProducer` is NEXT.
- Freeze changes are documentation-only; no Java source/test changed after the verified run.

## 2026-08-23 - C8.2-R1 MigrationArtifactProducer test-fixture repair

- User targeted C8.2 regression compiled `268` main Java and `181` test Java sources, then ran `45` tests with `0` failures and `3` errors.
- All three errors were confined to `MigrationArtifactProducerTest`; production migration generation and EA integration tests in the same run passed.
- Root cause: the test helper supplied `ArtifactGenerationContext` timestamp `20260823020000000`, which violates the frozen C5 timestamp contract `yyyyMMdd_HHmmss_SSS`.
- Repair is test-only: changed that fixture timestamp to `20260823_020000_000`. No production Java source changed.
- Expected targeted rerun remains `45 / 0 / 0 / 0`; expected full regression remains `530 / 0 / 0 / 4`.
- C8.1 remains the official baseline; C8.2-R1 is PENDING USER RE-RUN.

## 2026-08-23 - C8.2 MigrationArtifactProducer extraction candidate

- Started C8.2 only after C8.1 was user-verified and frozen.
- Extracted `writeMigrationArtifacts(...)` from `SchemaForgeApiService` into `MigrationArtifactProducer`.
- Preserved `MigrationGenerationService`, `MigrationSqlRenderer`, `SchemaDiffEngine`, `FlywayMigrationNamer`, `MigrationRenderOptions.safeDefaults()`, metadata lookup semantics, and `MigrationGenerationService` ledger producer strings.
- Reduced `SchemaForgeApiService` from `1214` to `1149` lines.
- Added `MigrationArtifactProducerTest` with 3 focused tests: repository unavailable, live table already matching, and generated Flyway artifact with canonical path/ledger entry.
- Source diff vs C8.1 is limited to `SchemaForgeApiService.java`, new `MigrationArtifactProducer.java`, and new `MigrationArtifactProducerTest.java`.
- Candidate inventory: `268` main Java / `181` test Java; expected full Surefire count `530`; fingerprint `7b9b012c74d53524acbb83cb09d1304f4a4bb19d4fbf742381a85fbafeb31f79`.
- Maven wrapper remains unavailable in the preparation environment; C8.2 is PENDING REGRESSION.

## 2026-08-23 - C8.1 DiagramArtifactProducer official verification

- User-verified targeted C8.1 regression: `55/55`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T01:56:10-07:00`.
- User-verified full clean regression: `527 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T01:58:11-07:00`.
- Promoted exact C8.1 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.1`.
- Frozen source inventory: `267` main Java files / `180` test Java files.
- Frozen source fingerprint: `128900965948b2686b4d1fa7d5b8b78278756b3be8e4926d48db320f271cba8e`.
- Marked C8.1 DONE / USER-VERIFIED; C8.2 `MigrationArtifactProducer` is NEXT.
- Freeze changes after the verified run are documentation-only; no Java source/test was changed.

## 2026-08-23 - C8.1 DiagramArtifactProducer extraction candidate

- Started C8 only after C7.2 was user-verified and frozen.
- Extracted per-document Mermaid/Graphviz, conceptual ERD, batch Mermaid, and batch Graphviz production from `SchemaForgeApiService` into `DiagramArtifactProducer`.
- Reduced `SchemaForgeApiService` from 1424 to 1214 lines without changing call order.
- Preserved `ArtifactNamingPolicy` paths, ledger artifact types/logical names/producer strings, summary/issue content, ZIP entries, C6 manifest behavior, and C7 REST behavior.
- Added `DiagramArtifactProducerTest` with 2 focused tests for canonical paths/ledger entries and batch artifact contract.
- New producer core compiles independently with Java 21; Maven wrapper download is unavailable in the preparation environment.
- Candidate inventory: `267` main Java / `180` test Java; expected full Surefire count `527`; fingerprint `128900965948b2686b4d1fa7d5b8b78278756b3be8e4926d48db320f271cba8e`.
- C8.1 is PENDING REGRESSION; no C8.2 extraction starts before targeted and full Maven gates are green.

## 2026-08-23 - C7.2 REST Response/Error Contract official verification

- User-verified targeted C7.2 regression: `31/31`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T01:34:19-07:00`.
- User-verified full clean regression: `525 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T01:37:07-07:00`.
- Promoted exact C7.2 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C7.2`.
- Frozen source inventory: `266` main Java files / `179` test Java files.
- Frozen source fingerprint: `763dcea0451ee0420c1886a11858452288c34e02a721a1aab166de673daa0a26`.
- Marked C7 DONE / USER-VERIFIED and C8 API/Application Service Decomposition NEXT / READY TO START.
- Freeze changes after the verified run are documentation-only; no Java source/test was changed.

## 2026-08-23 - C7.1/C7.2 REST Response and Error Contract candidate

- Completed C7.1 design before source changes; contract documented in `docs/architecture/REST-CONTRACT-C7.1.md`.
- Added `schemaforge-rest-error/v1` with stable code/status/message/path/requestId/timestamp/details fields.
- Added server-generated `X-SchemaForge-Request-Id` for `/api/**` success and error responses.
- Replaced duplicated controller-local `@ExceptionHandler` methods with one `SchemaForgeRestExceptionHandler`.
- Added `ServiceUnavailableException` so only explicitly unavailable required services map to HTTP 503; unrelated internal `IllegalStateException` failures remain 500.
- Preserved successful endpoint URLs, payload types, media types, Content-Disposition filenames, and SQL/ZIP/Mermaid bodies.
- Added 21 C7 tests across payload validation, correlation filtering, central exception mapping, and MockMvc integration.
- Candidate inventory: `266` main Java / `179` test Java; expected full Surefire count `525`; fingerprint `763dcea0451ee0420c1886a11858452288c34e02a721a1aab166de673daa0a26`.
- Maven execution is pending user regression; C8 remains blocked until C7 is verified and frozen.

## 2026-08-23 - C6.2 Standard Artifact Manifest official verification

- User-verified C6.2 targeted regression: `46/46`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T00:53:33-07:00`.
- User-verified full clean regression: `504 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T00:58:39-07:00`.
- Promoted exact C6.2 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C6.2`.
- Frozen source inventory: `261` main Java files / `175` test Java files.
- Frozen source fingerprint: `b9fa369b5e9b079279fb577d40c77e1b14c8193fedea2f85ab0e6edadaf8969f`.
- Marked C6 DONE / USER-VERIFIED and C7 REST Response and Error Contract NEXT / READY TO START.

## 2026-08-23 - C6.2 Standard Artifact Manifest implementation candidate

- C5.3-R2 regression gate is green and the repair is promoted as the official corrective checkpoint: targeted `38/38`; full `496 / 0 / 0 / 4`; `BUILD SUCCESS` at `2026-08-23T00:18:45-07:00`.
- Added `com.behsazan.schemaforge.artifact.manifest` with Standard Manifest V1 DTOs, assembler, writer, SHA-256 integrity, size metadata, deterministic ordering, and package/ledger invariants.
- Extended request generation context with one captured offset `generatedAt`; child contexts inherit ID, timestamp token, and generated time.
- Word, Legacy Word, ZIP Batch, and EA now finalize one root `manifest.json` using `schemaforge-manifest/v1`.
- Word and Legacy gain exactly one package file; ZIP Batch gains one root manifest and never nests child manifests; EA replaces its legacy manifest in place.
- Replaced the former EA-only map-shaped manifest with the common contract and retained dependency/cycle data under `extensions.enterpriseArchitect`.
- Every non-manifest generated artifact receives SHA-256 and exact `sizeBytes`; skipped/failed outcomes and the manifest self-entry intentionally have null integrity.
- Added `ArtifactManifestWriterTest` and `SchemaForgeManifestContractTest`; updated existing exact archive-count and EA-manifest assertions.
- No C5 naming/layout, parser behavior, canonical semantics, DDL/CRUD/Migration SQL, REST endpoint URL, or standalone CRUD/Mermaid payload contract changed.
- Local preparation checks passed; Maven wrapper remains unavailable in the preparation environment because Maven Central cannot be reached.
- Candidate inventory: `261` main Java files / `175` test Java files; expected full Surefire count `504`.
- Candidate source fingerprint: `b9fa369b5e9b079279fb577d40c77e1b14c8193fedea2f85ab0e6edadaf8969f`.
- C6.2 remains `PENDING REGRESSION`; C7 must not start until targeted and full Maven regression are green.

## 2026-08-23 - C5.3-R2 MySQL repair official verification

- User-verified targeted R2 regression: `38` tests, `0` failures, `0` errors, `0` skips; `BUILD SUCCESS` at `2026-08-23T00:16:16-07:00`.
- User-verified full clean regression: `496` tests, `0` failures, `0` errors, `4` environment-gated skips; `BUILD SUCCESS` at `2026-08-23T00:18:45-07:00`.
- Promoted the exact R2 source to official corrective checkpoint `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C5.3-R2`.
- Official R2 inventory: `253` main Java files / `173` test Java files.
- Official R2 source fingerprint: `de0eaac67c9488f71d8a57fe36a55459b6b558dcc61161976def3b25aa29a42c`.
- C6.2 production implementation is now permitted on this verified source.

## 2026-08-23 - C6.1 Standard Artifact Manifest V1 design

- Completed the documentation-only C6.1 manifest schema/design before any C6 production implementation.
- Fixed contract identifier `schemaforge-manifest/v1` while retaining Artifact Contract V1 as an independent version.
- Fixed top-level generation/source/model/validation/outcome sections and a stable artifact-entry shape with nullable platform/path/media/integrity fields where contractually appropriate.
- Fixed SHA-256 + byte-size integrity for every non-manifest GENERATED artifact using exact packaged bytes after final C5 path/collision allocation.
- Defined a MANIFEST self-entry at root `manifest.json` with intentionally null self-integrity to avoid recursive checksum semantics.
- Defined deterministic sorting and archive/ledger equality invariants.
- Defined migration of the current EA-only manifest into the common contract; EA dependency/cycle metadata moves under `extensions.enterpriseArchitect` rather than preserving a second legacy manifest.
- Confirmed Manifest V1 is required for Word, Legacy Word, ZIP Batch, and EA ZIP-producing paths; standalone CRUD/Mermaid endpoints keep their existing HTTP payloads in C6.
- C6.1 is documentation-only. C6.2 production implementation is blocked until the pending C5.3-R2 MySQL repair passes Maven regression.

## 2026-08-23 - C5.3-R2 MySQL NUMBER(19) AUTO_INCREMENT compatibility repair candidate

- Real EA input exposed the pre-existing MySQL blocker `NUMBER(19,0) + AutoNum` -> `AUTO_INCREMENT` with no signed lossless integer mapping.
- Added evidence-bounded MySQL adaptation: `NUMBER(19,0)` identity -> `BIGINT UNSIGNED AUTO_INCREMENT`.
- Added canonical-FK-driven propagation so internal `NUMBER(19,0)` FK columns that reference such identities also render `BIGINT UNSIGNED`; unrelated `NUMBER(19,0)` remains `DECIMAL(19)` and external/unknown parents are not guessed.
- Added inline DBA-visible SQL comments describing the identity/FK portability adaptation and the nonnegative AUTO_INCREMENT assumption.
- Added an optional full-schema type-mapping context to `DdlGenerator`; EA per-table output uses it without changing the set of tables written to each artifact.
- Metadata comparison now asks the dialect for the schema-aware desired type.
- Added regression coverage using the existing real `Party_14050514.xml` fixture.
- Java 21 core compilation and a direct real-EA compatibility probe passed in the build environment; Maven wrapper execution remains unavailable there because Maven Central cannot be reached.
- A separate existing MySQL limitation (`TIMESTAMP WITH TIME ZONE` has no lossless mapping) was discovered after the NUMBER(19) blocker and recorded as known limitation; it is not part of R2.
- R2 is `REPAIR CANDIDATE / PENDING MAVEN REGRESSION`; the official baseline remains C5.3 until user verification.

## 2026-08-23 - Consolidation corrective-version documentation normalization

- Added `docs/roadmap/CONSOLIDATION-VERSION-HISTORY.md` as the authoritative candidate/repair/freeze traceability register.
- Backfilled consolidation checkpoints C1, C4.2, C4.3, C5.1, C5.2, C5.3, C5.3-R1, and the C5.3 official freeze with scope, verification, fingerprint, and promotion state.
- Updated current documentation entry points and regression references from stale C1/C4.3 values to the official C5.3 baseline and `492/0/0/4` regression result.
- Updated current README/API/input-output examples to the frozen C5 artifact-first naming/layout contract.
- Preserved historical stage-input statements where they are intentionally part of the record (for example C5.1/C5.2 correctly naming C4.3 as their input baseline).
- Documentation-only maintenance after the user-verified C5.3-R1 full regression; no Java source, tests, SQL semantics, REST endpoints, or artifact paths changed.

## 2026-08-23 - C5 Artifact Naming/Layout official freeze

- User-verified targeted C5.3 regression passed: `50` tests, `0` failures, `0` errors, `0` skips; `BUILD SUCCESS` at `2026-08-22T23:19:22-07:00`.
- User-verified C5.3-R1 repair test passed: `1` test, `0` failures, `0` errors, `0` skips; `BUILD SUCCESS` at `2026-08-22T23:31:05-07:00`.
- User-verified full `mvnw.cmd clean test` on the exact repaired candidate passed: `492` tests, `0` failures, `0` errors, `4` environment-gated skips; `BUILD SUCCESS` at `2026-08-22T23:33:56-07:00`.
- Promoted C5 Artifact Naming and Layout Consolidation from candidate to official current baseline.
- Current source inventory: `253` main Java files and `172` test Java files.
- Current source fingerprint: `8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37`.
- Marked C5 DONE / USER-VERIFIED and `C6 - Standard Artifact Manifest` NEXT / READY TO START.
- Documentation-only freeze update; no Java source, tests, SQL semantics, REST endpoints, or artifact paths changed after the verified C5.3-R1 test run.

## 2026-08-23 - C5.3 regression repair R1

- User-verified targeted C5.3 regression passed: `50` tests, `0` failures, `0` errors, `0` skips; `BUILD SUCCESS` at `2026-08-22T23:19:22-07:00`.
- User-verified full `mvnw.cmd clean test` executed `492` tests and found exactly one failure in `DirectoryDualDatabaseGenerationRunnerTest`; all other tests passed and the four environment-gated database execution tests remained skipped.
- Root cause: the regression test still enumerated only the output-directory root with `Files.list(output)`, while C5 intentionally moves generated DDL under `ddl/oracle/` and `ddl/postgresql/`; generated scripts were present, but the stale assertion counted zero.
- Repair R1 changes only `DirectoryDualDatabaseGenerationRunnerTest`: use recursive regular-file discovery and explicitly assert the canonical C5 DDL roots for Oracle and PostgreSQL.
- No production Java source, parser, DDL generator, migration logic, metadata behavior, REST contract, or artifact content changed in this repair.
- R1 source inventory remains `253` main Java files / `172` test Java files.
- R1 source fingerprint: `8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37`.
- C5.3 remains pending user re-run; C4.3 remains the official baseline until the repaired candidate passes targeted and full regression.

## 2026-08-23 - C5.3 Artifact Naming/Layout implementation candidate

- Added `ArtifactNamingPolicy` as the production naming/layout authority for non-Flyway artifacts.
- Added one request-level `generationTimestamp` to `ArtifactGenerationContext`; child contexts inherit generation ID and timestamp.
- Standardized Word, Legacy Word, ZIP Batch, EA, and offline generation on artifact-first paths: `ddl/`, `migration/`, `crud/`, `model/`, `comparison/`, `diagram/`, `scripts/`, and `reports/`.
- Preserved Flyway filename grammar, CRUD semantic suffixes, standalone Mermaid selector filenames, REST endpoint URLs, and HTTP response bodies.
- Added deterministic final-path collision allocation using `__sf_<10-hex-hash>`; Flyway collisions fail instead of being renamed.
- ZIP Batch now preserves canonical child artifact paths and only remaps a final path when a true package collision occurs.
- Centralized fixed batch diagram/report names under `ArtifactNamingPolicy`; migrated the legacy DDL collision allocator to the same central policy.
- Added/updated contract tests for naming, collision behavior, Word/Legacy/ZIP/EA layouts, artifact tracking, comparison/CRUD paths, and offline generation.
- Confirmed by source-tree diff that `generation`, `dialect`, `specification`, `metadata`, `migration`, and `domain` packages are unchanged from C4.3.
- Candidate source inventory: `253` main Java files / `172` test Java files.
- Candidate source fingerprint: `5b600c90b3d42ea0fdbf18ef48d8832f8336d22fa2c39406f01ac82a5821c1a6`.
- C5.3 remains PENDING REGRESSION; C4.3 remains the official baseline until targeted and full Maven tests are user-verified.

## 2026-08-23 - C5.2 artifact naming/layout design decisions

- Fixed artifact-first directory roots for DDL, migration, CRUD, model, comparison, diagrams, scripts, and reports.
- Standardized canonical JSON on the existing `*.schema.json` canonical snapshot suffix; rejected a competing `*.canonical.json` convention.
- Fixed comparison and diagram naming grammars and preserved standalone Mermaid deterministic selector semantics.
- Decided that one top-level request owns one shared generation timestamp in addition to the C4 generation ID; Flyway migration versions remain independent.
- Approved deterministic stable-hash collision handling against final canonical paths.
- Approved a single C5 contract switch with no duplicate legacy-layout mode.
- Defined the target central `ArtifactNamingPolicy` responsibility while preserving `FlywayMigrationNamer` as migration-filename authority.
- Documentation/design only: no Java source, tests, runtime behavior, filename, archive path, REST response, or SQL content changed.

## 2026-08-23 - C5.1 artifact naming/layout analysis

- Completed source-derived inventory of current Word/Legacy, ZIP Batch, EA, standalone CRUD, and standalone Mermaid naming/path contracts.
- Added `docs/architecture/ARTIFACT-NAMING-LAYOUT-C5.1.md` with the Current -> Proposed mapping and compatibility-risk matrix.
- Proposed artifact-first canonical roots (`ddl`, `migration`, `crud`, `model`, `comparison`, `diagram`, `scripts`, `reports`) for C5.2 design review.
- Preserved Flyway filename grammar, platform tokens, CRUD semantic suffixes, and standalone Mermaid selector semantics as explicit compatibility constraints.
- Marked C5 IN PROGRESS with C5.1 DONE / C5.2 NEXT.
- Documentation-only stage: no Java source, tests, runtime behavior, filename, archive path, REST response, or SQL content changed.

# SchemaForge V4 Changelog

## 2026-08-22 - C4.3 Artifact Contract V1 production-path mapping freeze

- User-verified targeted C4.3 regression: `23` tests, `0` failures, `0` errors, `0` skips; `BUILD SUCCESS` at `2026-08-22T22:50:14-07:00`.
- User-verified full `mvnw.cmd clean test`: `482` tests, `0` failures, `0` errors, `4` environment-gated skips; `BUILD SUCCESS` at `2026-08-22T22:53:13-07:00`.
- Promoted C4.3 production-path artifact tracking from candidate to official current baseline.
- C4 Artifact Contract V1 is complete: C4.1 inventory, C4.2 core contract, and C4.3 pipeline tracking are all DONE / USER-VERIFIED.
- Current source inventory: `251` main Java files and `170` test Java files.
- Current source fingerprint: `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`.
- Marked `C5 - Artifact Naming and Layout Consolidation` NEXT / READY TO START.
- Documentation-only freeze update; no Java source or runtime behavior changed after the verified C4.3 test run.

## 2026-08-23 - C4.3 Artifact Contract production-path mapping candidate

- Added request-local `ArtifactGenerationContext`, `ArtifactLedger`, and portable `ArtifactPaths`.
- Wired Word, Legacy Word, ZIP Batch, and EA generated files to `ArtifactDescriptor` metadata without changing filenames or archive layout.
- Added generated/skipped/failed tracking for metadata-dependent migration, comparison, and CRUD outcomes.
- Added final ZIP-layout remapping for per-document batch descriptors so staging paths never leak into the contract.
- Added Artifact Contract descriptors to standalone Oracle CRUD, SQL Server CRUD, and canonical-JSON Mermaid generation results while preserving controller response bodies and attachment names.
- Added `ArtifactLedgerTest` and `SchemaForgeArtifactTrackingTest`; enhanced existing CRUD/Mermaid tests with descriptor assertions.
- Java syntax audit reports no syntax errors; Artifact Contract core compiles in isolation with Java 21.
- Maven regression remains pending user execution because Maven Central is unavailable in the preparation environment.
- C4.3 candidate source inventory: `251` main Java files / `170` test Java files; source fingerprint `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`.

## 2026-08-22 - C4.2 Artifact Contract V1 regression freeze

- User-verified targeted `ArtifactContractTest`: `8` tests, `0` failures, `0` errors, `0` skips; `BUILD SUCCESS` at `2026-08-22T08:42:15-07:00`.
- User-verified full `mvnw.cmd clean test`: `475` tests, `0` failures, `0` errors, `4` environment-gated skips; `BUILD SUCCESS` at `2026-08-22T21:39:20-07:00`.
- Promoted C4.2 from candidate to official current source baseline.
- Current source inventory: `248` main Java files and `168` test Java files.
- Current source fingerprint: `8b76049ff698850bfd79cd497c309ea61bda607db719d33bb93b9c3f6721ad75`.
- Marked `C4.2` DONE and `C4.3 - Pipeline mapping to Artifact Contract` NEXT / READY TO START.
- Documentation-only freeze update; no Java source or runtime behavior changed.

## 2026-08-22 - C4.2 Artifact Contract V1 core model

- Added the database-neutral `com.behsazan.schemaforge.artifact` metadata contract.
- Added `ArtifactDescriptor`, `ArtifactType`, `ArtifactStatus`, `ArtifactOrigin`, `ArtifactProvenance`, and contract version `1`.
- Normalized all source-derived C4.1 artifact families without introducing a ZIP transport artifact type.
- Kept `DatabasePlatform` optional for platform-neutral artifacts instead of adding artificial NONE/MULTI DBMS values.
- Added portable package-relative path invariants and explicit GENERATED/SKIPPED/FAILED outcome semantics.
- Added `ArtifactContractTest` and `docs/architecture/ARTIFACT-CONTRACT.md`.
- No existing generation pipeline, filename, ZIP layout, SQL semantic, parser, REST endpoint, or HTTP response was changed.
- New main-source classes compile with Java 21 in isolated `javac`; Maven regression is pending user execution because Maven Central is unavailable in the preparation environment.

## 2026-08-22 - C1 regression re-verification

- Recorded the final user-provided `mvnw.cmd clean test` re-verification after workspace stale-file cleanup.
- Result: `467` tests, `0` failures, `0` errors, `4` skips, `BUILD SUCCESS`; finished `2026-08-22T07:28:27-07:00`.
- Corrected the official main-source inventory to 242 files; the earlier 243 count was caused by stale `DatabaseCapability.java` in an intermediate workspace.
- Marked C4 Artifact Contract V1 as `NEXT / READY TO START`.
- No runtime or source-code change was made.

## 2026-08-22 - Consolidation execution control plan

- Added `SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md` as the authoritative stage-control roadmap after the C1 official freeze.
- Recorded completed C0-C3/C1 consolidation work and remaining C4-C11 stages.
- Added the mandatory rule that every stage must be explained with its exact work/change/test/risk/exit list before implementation begins.
- Linked the execution plan from the current documentation and baseline entry points.

## 2026-08-03 - EA table Persian name separation

- Preserved EA table `alias` as `Table.persianName`, independently from table description.
- Added the Persian name to JSON, SQL headers, and comparison workbook table metadata.
- Preserved the value through schema enrichment.

## 2026-07-25 - Consolidated JSON validation findings

- Added the validation warning abbreviation reference.
- Added recovery warnings and duplicate-column findings to JSON `validation.issues`.
- Added metadata datatype mismatch findings to JSON output generated by the REST pipeline.
- Reused each database metadata comparison result for both SQL and JSON generation, avoiding a second metadata query.

## 2026-07-25

- Added Oracle `CHAR` and `BYTE` length semantics to the canonical `DataType` model.
- Preserved the previous four-argument `DataType` constructor for source compatibility.
- Updated Word parsing and Oracle SQL rendering for `VARCHAR2(n CHAR)`, `VARCHAR2(n BYTE)`, and default length semantics.
- Added regression tests for length semantics.
- Added the project `doc` directory and copied the current project documentation into it.
- Retained offline Oracle DDL generation as a separate stage from online Oracle metadata validation.
