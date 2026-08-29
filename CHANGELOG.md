## 2026-08-29 - EA/REST metadata performance R5.1 regression fix

- Fixed request-cache negative table lookups so a miss under one schema spelling (for example `APP`) does not suppress a valid retry using the catalog-returned spelling (for example `app`).
- Positive table hits remain case-insensitive and replace earlier case-variant misses.
- Preserved R5 bulk metadata loading and request-level Comparison/Migration/CRUD cache reuse.
- Updated the stale EA run-script regression expectation from five to all six supported DBMS platforms.
- Added focused regression coverage for case-variant negative-cache recovery.

## 2026-08-29 - EA/REST metadata performance hardening

- Added request-local metadata caching to the failure-isolating repository wrapper.
- A schema proven missing now short-circuits repeated live-table probes during later comparison/migration phases.
- Metadata validation skips global column-profile scans and per-table/FK location lookups when the target schema is known missing.
- EA comparison skips live-table resolution immediately when schema existence metadata already says the schema is absent.
- Added regression coverage for schema-missing fast paths and request-cache reuse.

## 2026-08-29 - Oracle EA migration convergence / rename sequencing

- Treats an explicit live `DEFAULT NULL` as equivalent to no logical default.
- Emits Oracle name-only constraint/index drift as metadata `RENAME` instead of destructive DROP+CREATE.
- Prioritizes rename-bearing Flyway migrations before other table migrations so legacy collapsed names are released before later ADD operations.
- Aligns Schema Compare (logical and physical index sheets) with Migration by suppressing normal indexes already covered by PK/UK keys.
- Adds focused regression coverage for NULL-default convergence, Oracle object rename rendering, cross-table Flyway rename ordering, and redundant-index comparison.

## 2026-08-29 - DB2 LUW FK P7.1 / non-mutating Legacy Unique Key probe

- Added `LegacyUniqueKeyRecoveryProbeIT`, a read-only Word-corpus probe for the P7 `UK/UQ` routing fix.
- The probe reparses source Word documents directly, reports recovered canonical `UniqueKey` objects and their member columns, and never writes to the recovered canonical snapshot corpus.
- Added separate summary, recovered-key CSV, and parse-error CSV reports under `target/legacy-unique-key-probe`.
- Cleaned the project root: historical `*NOTES*` files are retained under `docs/dev-notes/` instead of the repository root.


## 2026-08-29 - DB2 LUW FK P7 / Legacy constraint-key routing

- Fixed legacy Word `ColumnLayoutResolver` routing so `UK`, `UQ`, and `PFK` tokens remain in the constraint-key channel instead of being diverted to the index channel.
- Preserved `IX`/`UIX` as index tokens; no unique constraint is inferred from FK metadata.
- Bumped `LegacyWordSpecificationParser.PARSER_VERSION` to `0.7.2`.
- Bumped canonical Word pipeline parser version so cached Word-derived snapshots are rebuilt under the corrected semantics.
- Added regression coverage for `UK/UQ/PFK` versus `IX/UIX` routing.
## R7.10 DB2 LUW P5 — Final-state FK live validation (2026-08-27)

## R7.10 - Db2 LUW P6.1 FK structural-audit parser fix (2026-08-29)

- Fixed `Db2LuwForeignKeyStructuralAuditTest` CREATE TABLE discovery when generated inline issue comments occur between the table name and body.
- Aligned the P6 CREATE TABLE prefix contract with the already-proven P5 live FK validator.
- Added comment-aware table-body parenthesis discovery and a focused regression test.
- This is audit-only; production DDL, canonical models, and database execution are unchanged.


- Added `Db2LuwForeignKeyDirectoryExecutionIT` as a separate live gate for foreign keys intentionally skipped by DB2 LUW HISTORICAL replay.
- Mirrors the deterministic historical final-state rule: only the final encountered definition of each qualified table contributes FK candidates.
- Adds SYSCAT preflight classification for missing source/parent tables, missing source/parent columns, and missing referenced PK/UNIQUE evidence.
- Executes only preflight-valid FKs against live Db2 LUW and drops every successfully created FK immediately after validation.
- Produces separate summary, live-error, structural-blocker, dependency-skip, and cleanup-error reports under `target/db2luw-fk-validation-report`.
- Test/live-validation tooling only; production DDL rendering and the already-verified 4,693-file HISTORICAL baseline are unchanged.

## R8.1 — Pre-Freeze Preparation (2026-08-26)

- R8.1 is VERIFIED / CLOSED after user-side full clean regression: `587 / 0 / 0 / 4`, `BUILD SUCCESS`.
- Kept all production Java under `src/main` unchanged.
- Fixed the Windows regression helper so the active log is not locked under `target` during `mvn clean`.
- Aligned seven test-side files with the already-frozen R7.3 fail-closed datatype contract and current service overloads; focused gate passed `26 / 0 / 0 / 0`.
- The local nine-document Word regression now records the known `BIM.PROVINCES.POPULATION` unresolved datatype as `Blocked=1`, with `Failed=0`, rather than generating guessed DDL.
- Recorded R7.1 legacy recovery, R7.2 recovered-corpus, R7.3 SAFE/OPTIMIZED strict acceptance, and R7.4 live PASS evidence for SQL Server, MySQL, PostgreSQL, and Oracle.
- Db2 z/OS remains `PENDING_ENVIRONMENT`; R7.4 stays open and R8.2 remains blocked by that external environment only.

## R7.3.3 — New Word strict regression gate (2026-08-26)

- Added exact corpus/accounting/failure-taxonomy assertions to `WordDirectoryMultiDatabaseGenerationIT` behind `schemaforge.word.failOnRegression=true`.
- User-side SAFE Strict and OPTIMIZED Strict both passed the frozen 660-document standard-parser baseline with the same exact 31 classified target failures and no generic `GENERATION_FAILED`.
- R7.3 is CLOSED / PASS. No parser or DDL semantics changed in R7.3.3.

## R7.3.2 — Word corpus failure-code reporting synchronization (2026-08-26)

- Repackaged the R7.3.1 functional baseline with the structured failure classifier present in `WordDirectoryMultiDatabaseGenerationIT`.
- No DDL semantics, parser behavior, or dialect mapping changed.
- Failure reporting now distinguishes unresolved canonical datatypes, MySQL multiple AUTO_INCREMENT, sequence NEXTVAL portability, identity integer representability, and per-dialect decimal precision limits instead of collapsing them to `GENERATION_FAILED`.
- Intended as the reporting prerequisite for the R7.3 SAFE strict acceptance freeze.

## R7.3.1 — New Word corpus unresolved-datatype fail-closed gate (2026-08-25)

- R7.3 SAFE discovery parsed all 660 standard DOCX files with zero parse failures and generated PostgreSQL for all 660; 19 dialect generation failures were isolated to 16 documents.
- Classified those failures into five evidence-backed groups: one `NUMBER(510)` portability blocker, nine MySQL multiple-identity/AUTO_INCREMENT blockers, three source rows with missing datatype, two MySQL sequence `NEXTVAL` blockers, and one MySQL `NUMBER(20)` identity range blocker.
- Fixed a cross-dialect safety gap where the canonical sentinel `MISSING_DATA_TYPE` could be rendered as executable type text by Oracle, PostgreSQL, Db2 z/OS and SQL Server.
- `SpecificationValidator` now emits `ERROR/COLUMN_DATATYPE_UNRESOLVED` for that sentinel.
- `DdlGenerator` now fails closed before dialect rendering when any executable column still has `MISSING_DATA_TYPE`; no target DBMS may publish guessed/invalid DDL for an unresolved source type.
- Added focused five-dialect regression coverage in `UnresolvedCanonicalDatatypeGenerationTest` and updated the existing phase-1 parser regression to expect the unresolved-type model to be invalid while still exportable as diagnostic JSON.
- Hardened `WordDirectoryMultiDatabaseGenerationIT` with `schemaforge.word.expectedMinDocuments` and structured generation-failure codes for R7.3 acceptance reporting.
- No guess-based repair was added for multiple identities, sequence defaults, oversized exact numerics, or the `NUMBER(20)` MySQL identity case.

## R7.2 — Cross-dialect recovered canonical corpus preparation (2026-08-25)

- Classified the first SAFE four-DBMS corpus gate: PostgreSQL generated all 5,321 snapshots; Oracle blocked 25; Db2/zOS blocked 1,508; SQL Server blocked 1,498; canonical read/validation failures remained zero.
- Confirmed the dominant Db2/zOS and SQL Server failures are the same canonical exact-numeric precision gap already handled by evidence-backed recovery, not independent dialect defects.
- Extended `MySqlFinalRecoveryGenerationIT` with optional `schemaforge.mysql.final.recoveredSnapshotDir` output so the exact cumulative DB2/historical/confirmed evidence overlay can be materialized as a separate database-neutral derived canonical corpus.
- The source canonical JSON corpus remains immutable; derived snapshots preserve original snapshot/model/parser provenance and source identity.
- Added complete accounting and zero-write-failure assertions for derived recovered snapshots.
- Added `docs/R7.2-FIVE-DBMS-CORPUS-ACCEPTANCE.md` with the recovery-materialization and observational SAFE commands.

## R7.1 — MySQL final recovery closure gate (2026-08-25)

- Hardened `MySqlFinalRecoveryGenerationIT` so canonical snapshot read failures are no longer silently skipped.
- Added discovered/loaded/read-failed corpus accounting and a dedicated `mysql-final-snapshot-read-failures_*.csv` report.
- Added `schemaforge.mysql.final.expectedMinSnapshots` (default `5321`) to prevent a partial legacy corpus from producing a false green closure result.
- Added `schemaforge.mysql.final.expectedMinGenerated` (default `4702`) to make the cumulative P2 recovery coverage floor explicit and configurable.
- Added `schemaforge.mysql.final.failOnSnapshotReadErrors` (default `true`); R7.1 closure also requires `failOnGenerationErrors=true`.
- Preserved the evidence-only recovery policy: canonical JSON is never mutated and residual unsupported/conflicting cases remain classified hard blockers rather than guessed.
- Added `docs/R7.1-MYSQL-FINAL-RECOVERY-CLOSURE.md` with the full Windows closure command and retained-evidence contract.
- Test-only recovery/acceptance hardening; no production parser, canonical model, datatype mapper, DDL renderer, REST, metadata repository, migration renderer, or live DB semantics changed.

## R6.8 — Legacy canonical corpus five-DBMS acceptance gate (2026-08-25)

- Promoted existing `CanonicalJsonDirectoryToDdlIT` instead of creating a duplicate corpus runner.
- Default corpus generation now covers all five registered DBMS, including MySQL.
- Freezes one numeric mapping strategy per run and reports `SAFE`/`OPTIMIZED` in the corpus summary.
- Added `schemaforge.snapshot.ddl.expectedMinSnapshots` to prevent an accidentally partial corpus directory from producing a false green acceptance result.
- Added source-canonical aggregate statistics for tables, columns, PK, FK, UK, indexes, checks, sequences, identity columns and defaulted columns.
- Canonical validation, datatype-mapping findings and offline SQL validation now retain explicit severity in the issue CSV.
- `failOnErrors=true` fails only on canonical/mapping/static/generation errors; accepted warnings remain reportable and can be promoted to gate failures with `schemaforge.snapshot.ddl.failOnWarnings=true`.
- Added lightweight MySQL corpus sanity checks for empty scripts, missing `CREATE TABLE`, and generated `[ERROR]` markers.
- Updated bulk-corpus documentation to require two five-DBMS passes over the same legacy JSON corpus: `SAFE` and `OPTIMIZED`.
- Test-only corpus tooling/documentation change; no production parser, canonical model, REST, DDL renderer, metadata repository, migration renderer or live database behavior changed.

## R6.7 — Configurable exact-numeric mapping policy (2026-08-25)

- Kept `SAFE` as the default exact-numeric policy.
- Wired `schemaforge.numeric-mapping.strategy` through Spring configuration for REST generation.
- Added explicit `DialectFactory.create(platform, strategy)` to avoid global mutable configuration.
- Applied the selected strategy consistently to Oracle, PostgreSQL, Db2 z/OS, SQL Server and MySQL dialect instances. Oracle keeps Oracle-native `NUMBER` rendering while reporting the active policy.
- Added MySQL lossless `OPTIMIZED` narrowing for scale-zero exact numerics: precision 1–4 -> `SMALLINT`, 5–9 -> `INT`, 10–18 -> `BIGINT`, larger/fractional values remain `DECIMAL`.
- Aligned MySQL metadata equivalence and migration diff/rendering with the selected strategy so `OPTIMIZED` output does not create false datatype mismatches or contradictory ALTER plans.
- Recorded `extensions.generationOptions.numericMapping.strategy` in Word, Legacy Word, Batch and EA manifests.

## 2026-08-25 - R6.6 ZIP Batch input accounting and duplicate suppression

- Batch summaries now account for every regular input file, not only processable DOCX files. Unsupported extensions and temporary/hidden files are recorded as `SKIPPED` with an explicit reason.
- Added `DUPLICATE_SOURCE_CONTENT` detection using SHA-256 so byte-identical documents are not emitted twice into an executable package.
- Added `DUPLICATE_LOGICAL_TABLE` detection after canonical preparation so different source bytes resolving to an already accepted `schema.table` are also excluded from executable artifacts.
- The first accepted source remains authoritative for the batch; duplicate sources produce no DDL/model/diagram artifacts and are reported in `batch-generation-summary.csv`.
- Standard Manifest V1 now records `extensions.batchInput` counts for regular files, processable documents, successes, failures and skips.
- Existing fault isolation remains unchanged: genuinely failing processable documents are still `FAILED` and captured in `batch-generation-errors.log`.
- R6.5 EA TIMESTAMP precision, R6.4 opt-in grants, and R6.3 MySQL cleanup behavior are unchanged.

## 2026-08-25 - R6.5 EA TIMESTAMP precision preservation

- Fixed Enterprise Architect XMI import dropping tagged temporal precision after reading it from the source column.
- `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE`, and `TIMESTAMP WITH LOCAL TIME ZONE` now retain positive EA `precision` values in the canonical `DataType`.
- The FEE acceptance source now parses all `99` source TIMESTAMP columns as canonical `TIMESTAMP(6)` instead of `TIMESTAMP` with null precision.
- Existing dialect policies then render source `TIMESTAMP(6)` as Oracle `TIMESTAMP(6)`, PostgreSQL `TIMESTAMP(6)`, Db2 z/OS `TIMESTAMP(6)`, SQL Server `DATETIME2(6)`, and MySQL `DATETIME(6)`.
- Added focused EA parser regression coverage for both regular and timezone-aware TIMESTAMP precision.
- No GRANT policy, audit enrichment, FK recovery, LOB mapping, artifact contract, or live-test cleanup behavior changed.

## 2026-08-25 - R6.4.1 MySQL native LOB portability

- Fixed REST `INVALID_REQUEST` on canonical/live `LONGTEXT` caused by a MySQL foundation mapper round-trip gap.
- MySQL now accepts native `TINYTEXT`, `MEDIUMTEXT`, `LONGTEXT`, `TINYBLOB`, `MEDIUMBLOB`, and `LONGBLOB` without guessing.
- Added cross-dialect portability for MySQL native LOB aliases: Oracle -> CLOB/BLOB, PostgreSQL -> TEXT/BYTEA, Db2 z/OS -> CLOB/BLOB, SQL Server -> VARCHAR(MAX)/VARBINARY(MAX).
- Existing generic `TEXT`/`CLOB` and `BLOB` mapping policies remain unchanged.
- Added regression assertions across all five dialects.

## 2026-08-25 - R6.4 Grant Policy Hardening

- Changed configured database grants to opt-in: `GrantProperties.defaults()` now contains no invented principals.
- Removed hard-coded `U_DEVELOPER` / `U_DESIGNER` from `application.yml`; default is `schemaforge.standards.grants: []`.
- Explicit table-level `GRANTS` metadata is still preserved because it originates from the input model.
- Configured grants and CRUD execute grants are generated only for principals explicitly configured by the deployment environment.
- SchemaForge does not create users/roles to satisfy a grant; principal provisioning remains a DBA/security responsibility.
- Added regression coverage proving default MySQL DDL contains no invented grant principals.

## 2026-08-25 - R6.3 MySQL live-test FK-safe cleanup

- User-side FULL `MySqlDirectoryExecutionTest` rerun on the 47-table FEE corpus reported `31` cleanup failures plus `63` SQL failures, totaling the same `94` actionable failures.
- The prior GRANT prerequisite issue was no longer the blocker; the rerun exposed test-harness cleanup attempting to drop parent tables while child foreign keys from the previous run still existed.
- Updated only `MySqlDirectoryExecutionTest` destructive cleanup to disable session `FOREIGN_KEY_CHECKS` around each `DROP TABLE IF EXISTS` and restore it immediately afterward.
- Production DDL, MySQL dialect behavior, FK generation, REST behavior, parser, canonical model and artifact contracts are unchanged.
- R6.3 is a test-harness repair candidate pending user rerun of the same 47-file FULL live gate.

## 2026-08-25 - R6.2 infrastructure SQL Server assertion repair

- User-side R6.1 targeted gate compiled `279` main and `193` test Java sources and ran 20 tests; 19 passed and only `InfrastructureProvisioningTemplateTest` failed at SQL Server assertion line 43.
- Production SQL Server identifier rendering intentionally leaves safe ordinary identifiers unquoted, so schema bootstrap renders `CREATE SCHEMA FEE AUTHORIZATION [dbo]` rather than `CREATE SCHEMA [FEE]`.
- Updated only the test expectation to the actual SQL Server contract; no production DDL, REST, audit policy, parser, manifest, Db2/zOS mapper, or infrastructure template behavior changed.
- R6.2 is a test-contract repair candidate pending user rerun of the same targeted gate.

## 2026-08-25 - R6.1 infrastructure test fixture repair

- User-side R6 targeted gate compiled `279` main and `193` test Java sources and ran 20 tests; 19 passed and only `InfrastructureProvisioningTemplateTest` errored before assertions.
- Root cause was test-only fixture `DataType.simple("NUMBER")`, which is intentionally rejected by Db2 z/OS because lossless NUMBER mapping requires explicit precision.
- Replaced only that fixture with `DataType.numeric("NUMBER", 19, 0)`; no production mapper, DDL, REST, audit policy, infrastructure template, parser, or manifest behavior changed.
- R6.1 is a test-contract repair candidate pending user rerun of the R6 targeted gate.

## 2026-08-24 - C11 final consolidation baseline official

- User-verified targeted consolidation gate passed `95 / 0 / 0 / 0`, `BUILD SUCCESS`, finished `2026-08-24T10:54:16+03:30`.
- User-verified full clean regression passed `554 / 0 / 0 / 4`, `BUILD SUCCESS`, finished `2026-08-24T10:16:01+03:30`.
- Standard Word regression remained `9/9` documents, `9` tables, `117` columns.
- No `src` change occurred in C9, C10 or C11; frozen inventory remains `276` main / `189` test Java and fingerprint `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`.
- Promoted final consolidation baseline to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C11`.
- Final distributable ZIP must be created from committed Git `HEAD` with `git archive`; retain the generated ZIP SHA-256 sidecar evidence.
- C4-C11 consolidation is complete. Deferred features remain inactive until explicitly promoted into a new stage.

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
- Official inventory: `270` main Java / `183` test Java; source fingerprint `8f134a74c2967a3c005b0250280d09cb75854d0c185f9485de947a749b2e8c57`; targeted `44/44`; full `536 / 0 / 0 / 4`.
- User verification passed: targeted `44 / 0 / 0 / 0`; full `536 / 0 / 0 / 4`, BUILD SUCCESS. C8.4 is frozen and C8.5 is next.

## 2026-08-23 - C8.3 ComparisonArtifactProducer official verification

- User-verified targeted C8.3 regression: `52/52`, no failures/errors/skips, `BUILD SUCCESS` at `2026-08-23T03:33:02-07:00`.
- User-verified full clean regression: `533 / 0 / 0 / 4`, `BUILD SUCCESS` at `2026-08-23T03:35:39-07:00`.
- Full build compiled `269` main Java and `182` test Java source files, confirming the intended C8.3 candidate was exercised.
- Promoted exact C8.3 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.3`.
- Source fingerprint: `90b8fcb7c8a2998b0aa878e01b59bb1d77f6916560514ce4e65dc2afdc927cab`.
- C8.3 is DONE / USER-VERIFIED; C8.4 CRUD artifact extraction is NEXT.

## 2026-08-23 - C8.3 ComparisonArtifactProducer extraction candidate

- Started C8.3 only after C8.2-R1 was user-verified and frozen as the official C8.2 baseline.
- Extracted Word/Legacy and EA comparison-workbook artifact orchestration from `SchemaForgeApiService` into `ComparisonArtifactProducer`.
- Preserved metadata table lookup/fallback semantics, `SchemaCompareExcelWriter`, logical/physical comparison behavior, workbook media type, C5 comparison paths, ledger producer identity, and PostgreSQL EA lowercase naming.
- Reduced `SchemaForgeApiService` from 1149 to 1024 lines.
- Added 3 focused tests: unavailable repository skip, canonical document-flow workbook/ledger, and EA PostgreSQL lowercase path with case-insensitive schema fallback.
- Candidate source inventory: 269 main Java / 182 test Java; fingerprint `90b8fcb7c8a2998b0aa878e01b59bb1d77f6916560514ce4e65dc2afdc927cab`.
- Expected full regression: 533 tests (C8.2 official 530 + 3 new tests).
- C8.3 passed targeted and full regression and is now official; C8.4 CRUD extraction is NEXT.

## 2026-08-23 - C8.2 MigrationArtifactProducer official verification

- User-verified C8.2-R1 targeted regression: 45 tests, 0 failures, 0 errors, 0 skips; `BUILD SUCCESS` at `2026-08-23T03:12:56-07:00`.
- User-verified full clean regression: 530 tests, 0 failures, 0 errors, 4 configuration-gated skips; `BUILD SUCCESS` at `2026-08-23T03:16:46-07:00`.
- Promoted the exact R1 source to `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.3`.
- Frozen source inventory: 268 main Java / 181 test Java; fingerprint `aa77b6bfe9248ebe7b061b2cd39a75ece5e34e765fd121a0ea62d1701a6f1e14`.
- C8.2 is DONE / USER-VERIFIED; C8.3 `ComparisonArtifactProducer` is NEXT.
- Freeze changes after the verified run are documentation-only; no Java source/test changed.

## 2026-08-23 - C8.2-R1 test-only regression repair

- Corrected the fixed `ArtifactGenerationContext` timestamp in `MigrationArtifactProducerTest` from `20260823020000000` to the C5 contract form `20260823_020000_000`.
- The user C8.2 targeted run reached `45` tests with `3` errors, all in this new test fixture; production source is unchanged.
- C8.2-R1 remains pending targeted and full user rerun.

## 2026-08-23 - C8.2 Migration artifact producer extraction candidate

- Moved migration artifact orchestration out of `SchemaForgeApiService` into `MigrationArtifactProducer`.
- Preserved migration diff/rendering, SAFE options, Flyway naming, metadata lookup behavior, artifact paths, and ledger producer identity.
- Reduced `SchemaForgeApiService` from 1214 to 1149 lines.
- Added 3 focused producer tests covering unavailable metadata, no-diff skip, and generated Flyway artifact/ledger path.
- Candidate source inventory: 268 main Java / 181 test Java; expected full regression 530 tests.
- Candidate source fingerprint: `7b9b012c74d53524acbb83cb09d1304f4a4bb19d4fbf742381a85fbafeb31f79`.

## 2026-08-23 - C8.1 Diagram producer extraction official freeze

- User-verified targeted regression: 55 tests, 0 failures, 0 errors, 0 skips; `BUILD SUCCESS` at `2026-08-23T01:56:10-07:00`.
- User-verified full clean regression: 527 tests, 0 failures, 0 errors, 4 configuration-gated skips; `BUILD SUCCESS` at `2026-08-23T01:58:11-07:00`.
- Frozen baseline: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.1`.
- Frozen source fingerprint: `128900965948b2686b4d1fa7d5b8b78278756b3be8e4926d48db320f271cba8e`; inventory 267 main Java / 180 test Java.
- C8.1 is DONE / USER-VERIFIED; C8.2 `MigrationArtifactProducer` is next.

## 2026-08-23 - C8.1 Diagram producer extraction candidate

- Moved diagram artifact production out of `SchemaForgeApiService` into `DiagramArtifactProducer`.
- Preserved naming/layout, ledger descriptors, diagram content, manifests, REST behavior, and SQL semantics.
- Added 2 focused producer tests; candidate full regression target is 527 tests.
- Candidate source fingerprint: `128900965948b2686b4d1fa7d5b8b78278756b3be8e4926d48db320f271cba8e`.

## 2026-08-23 - C7.2 REST Response/Error Contract official freeze

- User-verified targeted regression: 31 tests, 0 failures, 0 errors, 0 skips; `BUILD SUCCESS`.
- User-verified full clean regression: 525 tests, 0 failures, 0 errors, 4 configuration-gated skips; `BUILD SUCCESS`.
- Frozen baseline: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C7.2`.
- Frozen source fingerprint: `763dcea0451ee0420c1886a11858452288c34e02a721a1aab166de673daa0a26`; inventory 266 main Java / 179 test Java.
- C7 standardizes REST errors/correlation while preserving successful endpoint payloads; C8 service decomposition is next.

## 2026-08-23 - C5.3 Artifact Naming/Layout implementation candidate

- Centralized non-Flyway artifact names and package-relative paths in `ArtifactNamingPolicy`.
- Standardized Word, Legacy Word, ZIP Batch, EA, and offline generation on the artifact-first layout (`ddl`, `migration`, `crud`, `model`, `comparison`, `diagram`, `scripts`, `reports`).
- Added one shared generation timestamp per top-level request and deterministic final-path collision handling.
- Preserved SQL generation semantics, Flyway filename grammar, CRUD semantic suffixes, REST endpoints, and standalone Mermaid selector filenames.
- Added naming/layout/collision regression coverage; full Maven regression remains pending user verification before C5 promotion.

## 2026-08-22 - Artifact Contract V1 C4.3 official freeze

- Completed C4.3 production-path mapping to Artifact Contract V1 for Word, Legacy Word, ZIP Batch, EA, Oracle CRUD, SQL Server CRUD, and canonical-JSON Mermaid paths.
- User-verified targeted regression: 23 tests, 0 failures, 0 errors, 0 skips; `BUILD SUCCESS`.
- User-verified full clean regression: 482 tests, 0 failures, 0 errors, 4 environment-gated skips; `BUILD SUCCESS`, finished 2026-08-22T22:53:13-07:00.
- Frozen source fingerprint: `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`; source inventory 251 main Java / 170 test Java.
- Artifact Contract V1 is complete; C5 Artifact Naming and Layout Consolidation is the next controlled stage.
- Freeze update after the verified run is documentation-only.

## 2026-08-22 - V4 consolidation C1 official baseline freeze

- Promoted `SCHEMAFORGE-V4-CONSOLIDATION-CANDIDATE-20260822-C1` to official frozen baseline `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C1`.
- User-verified targeted consolidation regression: 28 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`, finished 2026-08-22T06:36:43-07:00.
- User-verified clean full regression: 467 tests, 0 failures, 0 errors, 4 configuration-gated skips, `BUILD SUCCESS`, finished 2026-08-22T07:01:53-07:00.
- The four normal-suite skips are MySQL, Oracle, PostgreSQL, and SQL Server directory-execution tests that require explicit SQL-root/JDBC configuration; opt-in live `*IT` pilots remain separate evidence.
- Standard Word regression inside the clean build: 9 documents, 9 passed, 0 failed, 9 tables, 117 columns.
- Frozen `src` fingerprint remains `77e038a4acb5631d4a407174d9e075cc3d773d21b96a7e884410d9fbdc00525c`; documentation-only freeze edits do not change the `src` tree.
- Repository source inventory: 242 `.java` files under `src/main/java`, 167 under `src/test/java`; Maven reported 243 main compilation units.

## 2026-08-22 - V4 consolidation C1 baseline cleanup

- Enabled the existing standard table-GRANT pipeline for MySQL by declaring `DialectFeature.GRANT` in `MySqlDialect`; no shared GRANT generation logic or configuration format was changed.
- Added MySQL GRANT regression coverage in the dialect foundation, core DDL, shared grant-enrichment, and central capability tests.
- Removed the unused duplicate `DatabaseCapability` enum so `DialectFeature` remains the single active optional-DDL capability contract.
- Added MySQL to the central dialect capability regression matrix.
- Prevented MySQL comparison workbooks from emitting generic/fallback physical-comparison sheets while the MySQL physical design contract remains intentionally deferred; logical/object comparison remains enabled.
- Added regression coverage proving MySQL workbooks omit `TABLE_PHYSICAL_COMPARE`, `INDEX_PHYSICAL_COMPARE`, and `COLUMN_PHYSICAL_COMPARE` until an explicit MySQL physical contract is modeled.
- Updated REST/OpenAPI documentation to the current seven endpoints and five registered DBMS, including legacy Word and canonical-JSON Mermaid export.
- Reworked the authoritative reference baseline so the 2026-08-17 399-test result is retained as historical evidence rather than being misrepresented as certification of the current 2026-08-22 source candidate.
- Current C1 source fingerprint: `77e038a4acb5631d4a407174d9e075cc3d773d21b96a7e884410d9fbdc00525c`.
- A new clean Maven regression is required before this candidate can be promoted to an official frozen baseline.

## 2026-08-22 - ALTER/Migration M2-R13 Db2 for z/OS live pilot

- Added `Db2ZosMigrationM2LivePilotIT` as the final opt-in M2 live pilot for the five-database migration path.
- The pilot preserves unconditional CREATE generation while exercising live column + PK/FK/UK/CHECK/INDEX migration, explicit destructive confirmation, seed-data preservation, catalog re-read, zero residual drift, and best-effort cleanup.
- Db2 PK/UNIQUE enforcing indexes referenced by `SYSIBM.SYSTABCONST` are now excluded from standalone-index metadata so a constraint-owned index is not modeled twice; physical index properties remain attached to PK/UK metadata.
- The test reuses the existing local-JCC `db2zos-live` Maven profile and the exact `I_UNDERSTAND_DB2_DDL_MAY_COMMIT` acknowledgement; the IBM driver remains unbundled.
- No CREATE-DDL policy change: CREATE remains unconditional and Flyway-compatible ALTER remains an additive artifact.

## 2026-08-22 - ALTER/Migration M2-R11 SQL Server dynamic default-drop fix

- Fixed SQL Server default-constraint removal after live M2 exposed `Incorrect syntax near 'QUOTENAME'`.
- Dynamic constraint DROP now assigns the composed `ALTER TABLE ... DROP CONSTRAINT` command to an `nvarchar(max)` variable before invoking `sys.sp_executesql`; `QUOTENAME(...)` is no longer used directly inside the EXEC string argument.
- The default-constraint discovery and DROP still execute inside one outer `sys.sp_executesql` batch, preserving JDBC/Flyway statement-splitting safety and local-variable scope.
- Added focused renderer regression assertions for the executable dynamic-SQL shape.
- CREATE generation, semantic diff, dependency refresh, and destructive-confirmation policies are unchanged.

## 2026-08-22 - ALTER Migration M2-R9 SQL Server live pilot

- Added `SqlServerMigrationM2LivePilotIT` for opt-in live validation of the existing-table CREATE+ALTER path against Microsoft SQL Server.
- The pilot uses only `SF_M2_PARENT` and `SF_M2_CHILD` inside the explicitly configured non-system schema, verifies unconditional CREATE generation, executes confirmed column + PK/FK/UK/CHECK/INDEX migration SQL, preserves seed data, re-reads `sys.*` metadata, and requires zero residual drift.
- SQL Server PK/UNIQUE backing indexes are now excluded from standalone index metadata so constraint-owned indexes are not modeled twice.
- SQL Server default-constraint removal is emitted through a single `sys.sp_executesql` JDBC/Flyway-safe batch; internal semicolons stay inside the dynamic batch instead of losing local-variable scope when SQL is statement-split for live validation.
- Added focused regression coverage for the standalone-index filter and single-statement SQL Server default-drop batch.

## 2026-08-22 - ALTER Migration M2-R8 PostgreSQL CHECK catalog normalization

- PostgreSQL M2 live pilot reached real migration execution and returned a single residual CHECK drift: live `id > 0 AND parent_id > 0` versus desired `(ID > 0) AND (PARENT_ID > 0)`.
- Added PostgreSQL-only CHECK comparison normalization for ordinary unquoted identifier case and redundant parentheses around atomic boolean predicates emitted/removed by `pg_get_constraintdef(..., true)`.
- Parentheses containing a top-level `AND`/`OR` are deliberately preserved so boolean precedence changes remain visible as real migration drift.
- String literal case/content and quoted-identifier case remain semantic and are not folded away.
- CREATE DDL, ALTER rendering, destructive-confirmation policy, and the other four DBMS dialects are unchanged.

## 2026-08-22 - ALTER Migration M2-R4.1 test compile fix

- Added the missing JUnit `assertFalse` static import used by the MySQL CHECK literal-preservation regression test.
- This is test-source-only; production migration, diff, renderer, CREATE, and ALTER behavior are unchanged.

## 2026-08-21 - ALTER Migration M2 real MySQL live pilot

- Added `MySqlMigrationM2LivePilotIT` for an opt-in destructive pilot against a dedicated `SCHEMAFORGE_*` MySQL database.
- The pilot proves that normal full CREATE SQL is still generated while the live table already exists.
- Exercises live-to-desired column changes plus PK/FK/UK/CHECK/INDEX replacement, writes both safe/commented and explicitly confirmed Flyway-compatible migrations, executes the confirmed migration, and re-diffs live metadata.
- Requires zero residual changes after execution and verifies that surviving seed data is preserved.
- Pilot cleanup drops the dedicated database in `finally`; ordinary builds remain unaffected.


## 2026-08-21 - ALTER Migration M2-R1 test alignment

- Corrected two stale `MigrationSqlRendererTest` expectations introduced when M1 advanced to M2.
- Rename-safety assertion now follows the M2 header text: `SchemaForge never infers column renames`.
- SQL Server index DROP expectation now follows the existing SQL Server identifier renderer: safe ordinary identifiers remain unquoted (`DROP INDEX IX_CUSTOMER_STATUS ON APP.CUSTOMER`).
- Production SQL semantics are unchanged; the failing assertions did not indicate a renderer defect.
- Removed the obsolete `M1` label from the identity/generated-expression manual-review hint.

## 2026-08-21 — ALTER/Flyway M1.2 MySQL identity-default diff hardening

- Fixed MySQL migration diffing so a legacy Oracle `SEQ_*.NEXTVAL` default attached to a logical identity column is compared using the effective MySQL `AUTO_INCREMENT` semantics instead of being passed to the MySQL expression mapper.
- Migration discovery no longer aborts the complete CREATE + ALTER output when a dialect cannot automatically map a default expression; unsupported REVIEW changes are left as commented/manual migration hints.
- CREATE DDL remains unconditional and independent from live-table ALTER generation.
- Added regression coverage for MySQL identity/NEXTVAL equivalence and unsupported default-expression migration rendering.


## 2026-08-21 — ALTER/Flyway M1.1 dual CREATE + ALTER output

- Existing-table detection no longer changes the normal CREATE-DDL path: CREATE scripts are always generated exactly as before.
- When the same table exists in live metadata and a column diff is detected, an additional Flyway-compatible ALTER migration is emitted under `<platform>/migrations/`.
- No migration file is created when the live table is absent or when no column changes are detected.
- Flyway migration versions now use millisecond precision with a monotonic in-process guard so multi-table generation cannot create duplicate Flyway versions.
- Word/ZIP and EA per-table API outputs now follow the same additive CREATE + ALTER policy.

## 2026-08-21 — ALTER/Flyway migration foundation M1

- Added live-vs-desired column diff planning for existing tables.
- Added Flyway-compatible versioned migration naming and no-overwrite file writing.
- Added SAFE / REVIEW / DESTRUCTIVE risk classification; destructive SQL is commented unless explicitly confirmed.
- Added DBMS-specific column ALTER rendering for Oracle, PostgreSQL, Db2 for z/OS, SQL Server, and MySQL.
- Added MySQL JDBC metadata table/column repository and runtime resolver/configuration support.
- Column rename inference is deliberately forbidden; PK/FK/UK/CHECK/INDEX migration planning remains M2.

## 2026-08-21 — MySQL P2-FINAL evidence-backed recovery freeze

- Added cumulative final MySQL recovery generation audit.
- Reapplies exact DB2 and unanimous historical numeric evidence.
- Consumes only confirmed P2-R7, P2-R8, and P2-R10 recovery decisions.
- Produces one cumulative `generated/` corpus and a frozen hard-blocker report.
- Persisted canonical JSON remains unchanged; no fuzzy or conflicting mapping is guessed.
## 2026-08-20 - MySQL P2-R10 historical column-name corroboration audit

- Added `MySqlHistoricalColumnNameCorroborationAuditIT`.
- P2-R9 user-verified baseline: 619 projected remaining snapshots, 91 column-not-found snapshots, 129 occurrences, and zero strong normalized candidates.
- Evaluates only the 40 P2-R9 `REVIEW_*` typo/prefix occurrences against independent historical canonical snapshots of the same schema/table.
- Similarity alone is never accepted; coexistence, rename ambiguity, and datatype-family conflicts remain blocked.
- Audit-only: no canonical JSON, production MySQL mapping, or DDL generation behavior is changed.

## 2026-08-20 - MySQL P2-R9 remaining blocker / column reconciliation audit

- Added `MySqlRemainingColumnReconciliationAuditIT`.
- Reconstructs the exact post-P2-R8 residual snapshot set from P2-R4/P2-R7/P2-R8 evidence.
- Reports residual blocker composition using the original P2-R2 metadata classifications.
- Audits `METADATA_COLUMN_NOT_FOUND` cases against unused columns of the exact DB2 schema/table.
- Separates normalized-name evidence from prefix/edit-distance review candidates; applies no recovery.

## 2026-08-20 - MySQL P2-R8 cross-schema reconciliation

- Added an evidence-only generation pass for P2-R6 `REVIEW_EXACT_NAME_OTHER_SCHEMA` candidates.
- Other-schema DB2 tables are evidence sources only; canonical schema/table identity and persisted canonical JSON are never changed.
- Acceptance requires a unique exact-name candidate, bidirectional column-coverage thresholds, independent datatype-family corroboration, and zero observed datatype-family conflicts.
- Recovery remains limited to missing-precision exact numeric blockers backed by MySQL-supported DB2 exact numeric metadata.
- P2-R7 user-verified result entering this phase: 70 confirmed strong candidates, 50 newly unblocked snapshots, projected 4,599 generated / 722 blocked, zero generation failures.

## 2026-08-20 - MySQL P2-R6 DB2 table reconciliation audit

- Added an audit-only integration test for the largest remaining MySQL evidence gap: canonical blockers whose exact DB2 SYSCOLUMNS lookup reports `TABLE_NOT_FOUND`.
- The audit compares DB2 table names and column signatures using conservative classifications for normalized-name, prefix/truncation, near-name, unique column-signature, other-schema, ambiguous, and no-candidate cases.
- No fuzzy candidate is applied automatically; canonical JSON and production MySQL mapping remain unchanged.
- Moved accumulated MySQL patch-note text files out of the project root into `docs/patches/mysql/`.

## 2026-08-20 - MySQL P2-R5 cross-source conflict audit

- Compared remaining canonical/DB2 datatype conflicts against independent historical canonical evidence.
- Audit result: 242 conflict occurrences / 125 unique columns, with only 2 exact cross-source consensus occurrences; conflict recovery is therefore not the primary coverage lever.
- No production mapping or canonical JSON change.

## 2026-08-20 - MySQL P2-R4 historical consensus recovery audit

- Added evidence-only historical consensus over canonical snapshots after exact DB2 metadata recovery.
- Historical consensus recovered 104 occurrences / 64 unique columns and newly unblocked 12 snapshots, raising generated coverage from 4,537 to 4,549 snapshots with zero generation failures.

## 2026-08-20 - MySQL P3-R2 storage adaptation

- Added conservative MySQL storage adaptation for oversized `VARCHAR`/row-size cases while preserving logical character limits with checks and avoiding indexed/key columns.
- Focused live replay of the two remaining storage failures completed 5/5 statements successfully on MySQL 8.4.11.
- Full regression after P3-R2: 429 tests, 0 failures, 0 errors, 4 skipped.

## 2026-08-20 - MySQL P3-R1 live hardening

- Removed invalid unquoted `DEFAULT NULL` from `NOT NULL` MySQL columns without changing the literal string `'NULL'`.
- Fixed live execution failure accounting so recorded MySQL errors increment the global failed-statement counter.
- The previous 18 MySQL error 1067 failures were eliminated in focused live replay.

## 2026-08-19 - MySQL P1 API packaging regression R3

- Root-caused the two failures from the pre-SQL-Server-R2 full regression: one stale MySQL logical-FK assertion and one real ZIP packaging omission.
- Batch ZIP packaging now derives database artifact directories and `.<platform>.sql` suffix routing from `DatabasePlatform.values()` instead of a hard-coded four-platform list, so MySQL DDL is packaged under `mysql/` and future registered platforms inherit the same behavior.
- Updated the MySQL logical-FK regression expectation to preserve MySQL identifier quoting with backticks.
- Updated the legacy Phase1 CLI usage text to advertise `mysql`.
- Extended the large ZIP integration test to require one MySQL DDL per successfully processed document and the `mysql/` directory.
- MySQL DDL rendering, canonical parsing, SQL Server production DDL, and live cleanup semantics are unchanged.

## 2026-08-19 - SQL Server historical replay cleanup R2

- Root-caused the resumed SQL Server replay: all 26 actionable failures were a 13-pair chain of error 3726 (`DEPENDENT_OBJECTS_EXIST`) during cleanup followed by error 2714 (`DUPLICATE_OBJECT`) on CREATE TABLE.
- In `HISTORICAL` destructive replay, the validator now discovers incoming foreign keys from `sys.foreign_keys` for each target table and drops only constraints whose child table is inside the configured `expectedSchema` safety boundary.
- Added `sqlserver.sql.fileNumbers` for sparse 1-based reruns while preserving original directory sequence numbers in reports.
- Added regression coverage for SQL Server catalog-identifier quoting and sparse sequence selection.
- Production SQL Server DDL generation is unchanged.

## 2026-08-19 - MySQL logical DDL P1

- Registered `MYSQL` in `DatabasePlatform` and `DialectFactory`; normal CLI, REST/ZIP, and EA DDL paths can now emit `.mysql.sql` artifacts.
- Added DB-neutral dialect hooks for pre-render table validation, conditional sequence emission, and inline table/column comments without changing existing dialect defaults.
- Rendered canonical schemas as MySQL databases with idempotent `CREATE DATABASE IF NOT EXISTS` bootstrap.
- Implemented evidence-safe `AUTO_INCREMENT`: suppresses parser-generated identity backing sequences only when they are identity-only, requires an integer target and a leftmost supporting PK/UK/index, and maps exact decimal identities to signed `BIGINT` only through precision 18.
- Added inline MySQL comments, stored generated columns, functional-index support through the common generator, and explicit rejection of unsupported standalone sequences / `SET DEFAULT` referential actions.
- Deliberately deferred MySQL JDBC metadata, live execution, physical tuning, metadata CRUD, and a MySQL-specific offline validator.
- Updated API/EA/ZIP regression expectations for the fifth registered DDL platform and added focused MySQL full-DDL generation tests.

## 2026-08-19 - Oracle FK live validation R2

- Split live Oracle FK validation into dependency skips, structural FK blockers, and true database execution errors.
- Added source/referenced column capture from generated ALTER TABLE statements.
- Added Oracle catalog preflight for missing source columns, missing referenced columns, and referenced column lists that do not match an enabled PRIMARY KEY or UNIQUE constraint.
- Added `oracle-fk-validation-blockers.csv`; existing error and skip reports now include both FK column lists.
- `oracle.fk.failOnErrors` now applies only to FKs that passed structural preflight and still failed live Oracle execution; optional `oracle.fk.failOnBlockers` defaults to false.
- Production DDL generation, canonical parsing, dialect rendering, and MySQL P0 foundation are unchanged.

## 2026-08-17 - P8-C: column physical metadata comparison


## 2026-08-19 - Live SQL directory replay cleanup

- Extended Oracle, PostgreSQL, and SQL Server directory execution integration tests so `dropBeforeCreate=true` removes both tables and explicit sequences discovered in each source SQL script.
- Cleanup is destructive only when the existing `confirmDestructive=true` and `expectedSchema` safety gates are satisfied.
- Tables are dropped before sequences to avoid dependency conflicts.
- Added cross-dialect cleanup SQL regression coverage.
- Production DDL generation is unchanged.

- Added PostgreSQL column physical metadata acquisition from `pg_attribute.attstorage` and `pg_attribute.attcompression`, with `pg_type.typstorage` retained only as comparison evidence for `STORAGE DEFAULT`.
- Added `COLUMN_PHYSICAL_COMPARE` to the existing workbook.
- Column physical comparison is intentionally PostgreSQL-only because the frozen canonical column physical model currently represents PostgreSQL STORAGE/COMPRESSION only.
- `PLAIN`/`EXTERNAL` storage does not expose compression as active state; unknown future catalog codes are review-only and are never guessed.
- `STORAGE DEFAULT` compares against the type default while the Excel ACTUAL value still shows the effective current storage mode.
- No Domain, parser, snapshot, API, DDL dialect, physical renderer, generation, or non-PostgreSQL metadata behavior changed.
- P8-C adds five focused tests over the user-verified 394-test P8-B baseline, for an expected full-suite total of 399.

## 2026-08-17 - P8-B: index / PK / UK physical metadata comparison

- Extended actual physical metadata acquisition to ordinary indexes and backing indexes for PRIMARY KEY / UNIQUE constraints across Oracle, PostgreSQL, SQL Server, and Db2 for z/OS.
- Added `INDEX_PHYSICAL_COMPARE` to the existing workbook with `INDEX`, `PRIMARY_KEY`, and `UNIQUE_KEY` scopes.
- Kept database actual state comparison-only; no database metadata is promoted to design intent or index `buildOptions`.
- SQL Server mixed partition compression is `REVIEW`; FILLFACTOR 0 and 100 compare as equivalent.
- Db2 allocation/recovery/logical semantics such as PRIQTY/SECQTY reverse-engineering, COPY, and CLUSTER remain excluded.
- No Domain, parser, snapshot, API, DDL dialect, physical renderer, or generation behavior changed.
- P8-B adds nine focused tests over the verified 385-test P8-A baseline, for an expected full-suite total of 394.

## 2026-08-17 - P8-A: table physical metadata comparison

- Added table-level physical metadata acquisition to the Oracle, PostgreSQL, SQL Server, and Db2 for z/OS JDBC metadata repositories.
- Kept database-side physical metadata as actual-state comparison evidence only; it is not promoted into design intent and does not feed generated DDL.
- Added `PhysicalMetadataComparator`, `PhysicalComparisonRow`, and `PhysicalComparisonStatus` for vendor-aware expected-vs-actual table physical comparison.
- Added `TABLE_PHYSICAL_COMPARE` to the existing Excel comparison workbook with `MATCH`, `MISMATCH`, `NOT_SPECIFIED`, `NOT_AVAILABLE`, and `REVIEW` statuses.
- Oracle does not infer `SEGMENT CREATION` from current segment existence; PostgreSQL operational reloptions are excluded; SQL Server mixed partition compression is review-only; Db2 allocation quantities are not reverse-mapped to `PRIQTY`/`SECQTY`.
- No Domain, parser, snapshot, API, DDL dialect, physical renderer, or index-build-option behavior changed.
- The previously verified baseline remains 376 tests; P8-A adds nine focused tests, for an expected full-suite total of 385.


## 2026-08-17 - Physical P6: column-scoped physical options

- Added optional immutable `Column.physicalOptions` with compatibility constructors; existing callers continue to default to an empty map.
- Extended canonical snapshots with additive nullable column `physicalOptions`; older snapshots map missing values to an empty map.
- PostgreSQL now renders explicit column `STORAGE PLAIN|EXTERNAL|EXTENDED|MAIN|DEFAULT` and `COMPRESSION pglz|lz4|default` only from column-scoped source/profile evidence.
- Compression is not emitted for known fixed-width target types or when explicit `PLAIN`/`EXTERNAL` storage makes compression inactive; the reason is written inline as `[SOURCE PHYSICAL ISSUE]`.
- Unknown/custom target types remain review-only rather than assuming compression support.
- Word, EA and JDBC metadata parsers do not infer column physical options.
- No parser/cache provenance change was made.

## 2026-08-17 - Physical P5: Index Build Options

- Added `Index.buildOptions` as a separate canonical/snapshot map; persistent physical state remains in `physicalOptions`.
- Oracle explicit index builds support source/profile `ONLINE`.
- PostgreSQL explicit index builds support source/profile `CONCURRENTLY` with transaction/partition review warning.
- SQL Server explicit index builds support source/profile `ONLINE`, `RESUMABLE`, `MAX_DURATION`, `MAXDOP`, and `SORT_IN_TEMPDB` with compatibility guards.
- No build option is inferred from metadata or table-level physical defaults.
- Backward-compatible Index constructors and old snapshots remain supported.
## 2026-08-17 - Db2/z/OS table-space physical profile

- Expanded the Db2/z/OS table physical block into a source/profile-driven table-space physical profile while keeping `CREATE TABLE ... IN database.tablespace` as the only active table placement.
- Added validated review candidates for `BUFFERPOOL`, `DSSIZE`, `SEGSIZE`, `FREEPAGE`, `PCTFREE`, `PCTFREE FOR UPDATE`, `COMPRESS`, `GBPCACHE`, `CLOSE`, `DEFINE`, `LOCKSIZE`, `LOCKMAX`, `MAXROWS`, `MEMBER CLUSTER`, `INSERT ALGORITHM`, `TRACKMOD`, `LOGGED/NOT LOGGED`, `USING STOGROUP`, `PRIQTY`, `SECQTY`, and `ERASE`.
- Preserved source values only when they satisfy the Db2 syntax/range checks available offline; invalid or context-dependent values are surfaced as `[SOURCE PHYSICAL ISSUE]` / `[SOURCE PHYSICAL REVIEW]` instead of being clamped or guessed.
- `DSSIZE` remains organization-aware: values are not reinterpreted without PBG/PBR/PAGENUM context, and partitioning clauses remain out of scope.
- `PCTFREE FOR UPDATE` remains source/profile-only because its default is controlled by the Db2 `PCTFREE_UPD` subsystem parameter; the combined `PCTFREE + FOR UPDATE <= 99` rule is enforced when both are supplied.
- `CREATE TABLESPACE` provisioning is still intentionally not generated as executable SQL; the profile stays inside the DBA-reviewable physical comment block.
- No Legacy Word parser semantics changed.

## 2026-08-17 - SchemaForge V4 Physical P0 regression expectation fix

- Corrected `LegacyOracleGenerationPipelineTest` to distinguish canonical DB2 recovery from Oracle rendering: the parser still recovers physical `S` as canonical `SMALLINT`, while `OracleDialect` renders canonical integer types, including `SMALLINT`, as Oracle `NUMBER`.
- No production parser, canonical mapping, or DDL rendering behavior changed.

## 2026-08-17 - Physical P0: object-scoped index physical options

- Extended canonical `Index`, `PrimaryKey`, and `UniqueKey` models with optional immutable backing-index `physicalOptions`.
- Preserved all existing constructors; old callers default to an empty object-scoped physical option map.
- Extended canonical snapshot JSON for indexes, primary keys, and unique keys with optional `physicalOptions`; older snapshots remain readable because the fields are additive and nullable.
- Physical renderers now resolve explicit object-scoped index options first, then fall back to historical table-scoped index options.
- Explicit placement (`INDEX_TABLESPACE`) and index physical values can now differ between multiple indexes, PK backing indexes, and UK backing indexes on the same table.
- Deduplication/reconstruction in `DdlGenerator` preserves explicit index physical options.
- Added regression coverage for snapshot round-trip and object-scoped Oracle PCTFREE/tablespace override with table fallback.
- No Word parser semantics changed; Recovery10 parser/cache version remains unchanged.


## 2026-08-17 - Legacy metadata Recovery10 final

- Applies exact DB2 `schema + table + column` metadata to missing/unreliable datatypes even when the Word datatype cells are structurally merged; merged technical column names remain rejected.
- Allows DB2 `CHAR`/`VARCHAR` width evidence to fill a missing canonical `CHAR`/`VARCHAR` length, and `GRAPHIC`/`VARGRAPHIC` width evidence to fill `NCHAR`/`NVARCHAR`, without overwriting the valid Word logical datatype.
- Keeps all metadata matching exact and case-insensitive; no fuzzy table/column matching and no default lengths were introduced.
- Parser version: `0.7.1`; snapshot pipeline: `word-pipeline-v4-2026-08-17-legacy-metadata-recovery10-final`.

## 2026-08-17 - Metadata diagnostic 9

- Legacy parser version `0.7.0`; snapshot pipeline `word-pipeline-v4-2026-08-17-legacy-metadata-diagnostic9`.
- Adds exact DB2 metadata lookup diagnostics without changing recovery semantics.
- Metadata misses are classified as `USABLE`, `INCOMPLETE`, `AMBIGUOUS`, `COLUMN_NOT_FOUND`, `TABLE_NOT_FOUND`, or `INVALID_KEY`.
- Remaining datatype, character-length, and numeric precision/scale failures include `[db2Metadata=...]` in the failure message when an offline SYSIBM.SYSCOLUMNS file is configured.
- No fuzzy table/column matching and no datatype/length defaults were introduced.

## 2026-08-17 - SchemaForge V4 legacy metadata recovery8

- Added optional offline Db2/z/OS `SYSIBM.SYSCOLUMNS` recovery for unresolved legacy Word datatype evidence.
- Recovery is exact on schema + table + column and never overwrites a reliable Word datatype.
- Character length and invalid numeric precision/scale are filled only from compatible exact metadata.
- CSV/TSV/TXT and ZIP exports are accepted; conflicting duplicate metadata rows are rejected as ambiguous.
- Shared Db2 catalog datatype mapping is reused by JDBC and offline metadata paths.
- Snapshot probe property: `schemaforge.snapshot.db2SysColumnsFile`.

# Changelog

- Recovery6: allow the flat-row second-chance parser to re-evaluate a single paragraph-wrapped structural cell when exact field cardinality is still provable; mismatched multi-paragraph cells remain rejected.
- Bumped legacy parser provenance to `0.6.7` and canonical Word cache parser version to `word-pipeline-v4-2026-08-17-legacy-recovery6`.

## Recovery5 - 2026-08-17

- Bumped legacy parser provenance to `0.6.6` and canonical Word cache parser version to `word-pipeline-v4-2026-08-17-legacy-recovery5`.
- Allowed the flat-row reconstructor to perform a strict second-chance split when paragraph markers remain in the field-name cell after the paragraph-aware splitter could not align the row.
- Prevented a later duplicate row with unresolvable datatype evidence from replacing an earlier duplicate definition whose datatype is deterministic.
- Kept ambiguous `S` logical types, missing character lengths without explicit evidence, and semantically unaligned merged rows unresolved.

## 2026-08-17 - Legacy Recovery4 evidence-driven fixes
- Bumped legacy parser provenance to `0.6.5` and canonical Word cache parser version to `word-pipeline-v4-2026-08-17-legacy-recovery4`.
- Preserved real `XML` datatype evidence instead of misclassifying it as the legacy `X...` index shorthand.
- Added evidence-backed logical datatype aliases `VAR -> VARCHAR`, `NVCHAR/NVC -> NVARCHAR`; ambiguous `S` remains unresolved in the logical/source column.
- Reconstructed flattened multi-field rows when mandatory checkmark glyph cardinality exactly matches field/type cardinality.
- Recovered character lengths separated from an explicit matching type by one blank spacer cell.
- Treated explicit non-positive numeric precision as unspecified with a parser warning rather than constructing an invalid numeric datatype.

## 2026-08-17 - Legacy Word Recovery3: row reconstruction and evidence-only type/length recovery

- Bumped legacy parser provenance to `0.6.4` and canonical Word cache parser version to `word-pipeline-v4-2026-08-17-legacy-recovery3`.
- Added conservative flattened merged-row reconstruction for rows where multiple technical field names and datatype tokens were collapsed into whitespace-separated cells; splitting requires one-to-one field/type cardinality and unambiguous structural ownership.
- Added `FLAT_MERGED_DEFINITION_ROW_SPLIT` extraction provenance for reconstructed flattened rows.
- Added evidence-only datatype recovery from known raw type slots; ambiguous logical `N/C/S` values are still not guessed, while DB2-only aliases such as `S=SMALLINT` and `C=CHAR` are accepted only when they occur in confirmed physical-type slots.
- Improved missing `CHAR`/`VARCHAR` length recovery so an adjacent explicit type/length pair can be used even when other numeric key/index values exist in the same raw row.
- Added focused regressions for flattened rows, displaced type evidence, multiple numeric cells, contextual DB2 `S`, and the invariant that a logical `S` without physical evidence remains unresolved.
- Full Maven execution remains project-environment validation because this packaging environment cannot download Maven 3.9.9; isolated Java 21 compilation and runtime probes for the changed parser/reconstruction paths passed.

## 2026-08-17 - DDL Generation Core V4 final freeze

- Froze the four-dialect DDL Generation Core after the completed Oracle, PostgreSQL, SQL Server, Db2/zOS, Physical Phase 1, and Datatype Compatibility Phase 1 workstreams.
- Final validated canonical corpus: 4,768 snapshots, 0 snapshot failures, 0 stale parser sources.
- Final bulk status: Oracle 4,745 generated / 2 review findings / 21 mapping-blocked; PostgreSQL 4,768 generated / 0 findings / 0 blocked; SQL Server 3,697 generated / 0 non-blocking findings / 1,071 mapping-blocked; Db2/zOS 3,687 generated / 0 non-blocking findings / 1,081 mapping-blocked.
- Mapping blockers preserve source semantics and intentionally prevent guessed/clamped SQL for unsupported or ambiguous exact numeric definitions.
- Physical Phase 1 remains frozen. Per-index distinct source physical tuning remains deferred to Physical Phase 1.1 only if a real input contract can represent it.
- Legacy Word parser recovery failures, output-name collisions, source-document corrections, CREATE VIEW, partitioning, LOB provisioning, and environment tablespace/filegroup provisioning remain outside this core freeze.
- The 2026-08-17-0931 project supplied for this freeze was compared with the last green single-file DBA-contract baseline; application/test source was unchanged (only IDE workspace state differed).
- No production Java code, test behavior, parser semantics, datatype mapping, physical rendering, canonical model, or REST contract changed in this freeze step.

## 2026-08-16 - Datatype Compatibility Phase 1: hard numeric precision blocking

- Parser 0.6.1 corpus evidence reduced false temporal findings to zero for PostgreSQL, 9 non-blocking SQL Server documents, and 1,081 Db2 blocked documents; no temporal mapping change was needed.
- Oracle no longer clamps canonical exact numeric precision above 38 to `NUMBER(38)`; such mappings are now blocking `ORACLE_DECIMAL_PRECISION_UNSUPPORTED` findings.
- SQL Server no longer clamps exact numeric precision above 38 to `DECIMAL(38,s)`; such mappings are now blocking `SQLSERVER_DECIMAL_PRECISION_UNSUPPORTED` findings.
- Direct Oracle and SQL Server type renderers now enforce the same hard precision limits as the compatibility analyzer, so bypassing the bulk preflight cannot silently change source numeric semantics.
- PostgreSQL high-precision `NUMERIC` behavior is unchanged because the target can represent those explicit precisions; Db2/zOS already rejects precision above 31.
- Existing inline issue codes for the earlier bounded-warning behavior remain recognized for compatibility, but new analysis emits the blocking `*_UNSUPPORTED` codes.
- Physical Phase 1 remains frozen and unchanged. No new test method was added.

## 2026-08-16 - Datatype Compatibility Phase 1: legacy temporal length semantics

- Corrected the legacy Word parser so the separate `Length`/DB2-length cell is not interpreted as `TIMESTAMP(p)` fractional-second precision.
- Legacy values such as `TS` + length `10`, `12`, `15`, or `26` now map to canonical `TIMESTAMP` unless precision is explicitly written in the datatype declaration (for example `TIMESTAMP(6)`).
- Explicit inline temporal precision remains preserved and is still subject to dialect-specific compatibility validation/bounding.
- Added `LEGACY_TEMPORAL_LENGTH_IGNORED` parser provenance metadata when a separate temporal length cell is present; this does not alter executable SQL or Physical Phase 1.
- Bumped the legacy parser provenance to `0.6.1` and the Word snapshot parser version so Word-derived caches are refreshed under the corrected semantics.
- Reused the existing legacy Oracle pipeline regression; no new test method was added.

## 2026-08-16 - Datatype Compatibility Phase 1: SQL Server unbounded exact numeric blocking

- Bulk corpus evidence showed 5,023 unbounded exact-numeric columns across 1,062 snapshots.
- SQL Server no longer invents `DECIMAL(38,0)` for canonical `NUMBER`/`NUMERIC`/`DECIMAL`/`DEC` without explicit precision.
- Such mappings are now reported as blocking `SQLSERVER_EXACT_NUMERIC_PRECISION_REQUIRED` findings.
- PostgreSQL unconstrained `NUMERIC` behavior is unchanged; Db2/zOS already blocked the same lossless-mapping gap.
- Temporal precision bounding and explicit precision > target maximum remain review warnings in this increment.
- Physical Phase 1 remains frozen and unchanged.

# 2026-08-16 - Physical Phase 1 renderer refactor / output stabilization

- Refactored repeated SOURCE PHYSICAL / ISSUE / REVIEW message construction into `PhysicalSourceOptions`; DBMS-specific physical rules remain in their existing renderers.
- Removed the duplicated PostgreSQL standalone-index vs PK/UK backing-index rendering path while preserving the required placement difference (`TABLESPACE` vs `USING INDEX TABLESPACE`).
- Reused one SQL Server lookup for `OPTIMIZE_FOR_SEQUENTIAL_KEY` instead of scanning source options twice.
- Moved reusable integer-range lookup and keyword normalization into `PhysicalSourceOptions`; Oracle physical behavior is unchanged.
- Stabilized diagnostic accepted-value lists by sorting them before rendering, preventing JVM-dependent `Set.of(...)` order from causing noisy output diffs. Executable/candidate SQL clauses are unchanged.
- No parser, datatype mapping, canonical model, REST service, physical rule, or test class was added or changed.
- Java 21 compilation of the domain + physical production subset passed. A before/after probe across default, valid-source, and invalid-source physical cases was identical after normalizing the previously nondeterministic accepted-value list order; default and valid-source output was byte-for-byte identical.

# 2026-08-16 - Physical source-aware validator splitter fix

- Fixed `SqlScriptStatementParser` so statement terminators inside `--` and `/* ... */` comments are ignored while splitting generated SQL.
- This prevents Physical Phase 1 explanatory comments containing semicolons from being misread as executable Db2/SQL Server statements.
- No Physical renderer rule, source-value handling, parser, datatype mapping, or REST behavior changed.
- Reused the existing `SqlScriptStatementParserTest`; no new test class was added.
- Direct Java 21 smoke generation/validation now accepts the generated Db2 explicit PK/UK indexes and SQL Server physical blocks that failed in the full Maven run.

# 2026-08-16 - Physical Phase 1 source-aware completion tranche

- Re-centered work on Production physical DDL; no REST endpoint and no datatype-mapping change was added.
- Added source-aware physical-option handling: valid source values are retained inside review blocks; invalid/out-of-range values are surfaced as `[SOURCE PHYSICAL ISSUE]` and are not silently clamped/normalized.
- Added reviewable source/default handling for Oracle PCTFREE/INITRANS/PCTUSED, PostgreSQL table/index fillfactor, SQL Server table/index compression and fillfactor/PAD_INDEX, and Db2 z/OS index free-space/storage/cache/compression/close/padding options.
- Kept environment-specific tablespace/filegroup/stogroup/bufferpool values as active historical placement or explicit placeholders; no environment name is invented.
- Fixed a real PostgreSQL activation bug: PK/UNIQUE physical blocks now use `USING INDEX TABLESPACE <INDEX_TABLESPACE>`, while standalone CREATE INDEX continues to use `TABLESPACE <INDEX_TABLESPACE>`.
- Oracle review blocks now make NOCOMPRESS explicit and keep LOGGING/NOLOGGING as an explicit DBA recovery/workload decision.
- Added two regressions to the existing `PhysicalPhase1DdlGeneratorTest` instead of creating another test class.
- Direct Java 21 compilation of the changed production path and four-dialect smoke rendering passed; full Maven regression remains project-environment validation.


- Fixed the Physical Phase 1 PostgreSQL LOB golden assertion to follow the existing PostgreSQL identifier-rendering contract (ordinary identifiers are emitted in lower case). Production DDL behavior is unchanged.
# 2026-08-16 - Physical DDL phase 1 Maven regression fix

- Fixed `OracleDdlSanityChecker` so inline `/* ... */` physical candidate blocks are excluded from executable Oracle validation while original line numbers are preserved.
- Fixed CREATE TABLE scope tracking in the Oracle safety gate using parenthesis depth, preventing later `ALTER`, `COMMENT`, `GRANT`, and summary text from being misclassified as column definitions after table-level physical comments were introduced.
- Updated the PostgreSQL generator regression expectation so inline physical comment blocks may appear before the statement terminator without changing the logical constraint/index assertion.
- Added an Oracle sanity regression covering inline physical comments followed by active tablespace, ALTER, COMMENT, and GRANT statements.
- Direct Java 21 compilation and focused Oracle/PostgreSQL smoke verification passed. Full Maven suite requires rerun in the project environment.

# 2026-08-16 - Physical DDL phase 1 (inline DBA review)

- Added comment-only Physical Phase 1 for Oracle, PostgreSQL, SQL Server, and Db2 for z/OS without adding service/API physical override parameters or REVIEW/APPLY modes.
- Preserved all previously active placement behavior; Oracle `TS_<SCHEMA>` / `ITS_<SCHEMA>` defaults remain executable, and source-driven PostgreSQL/SQL Server/Db2 placement remains executable.
- Added inline table/index physical comment blocks at DBMS-correct grammar positions for DBA review and manual activation.
- Added Oracle PCTFREE/INITRANS guidance, PostgreSQL fillfactor guidance, SQL Server fill/compression/index-option guidance, and Db2 z/OS table/index storage-option guidance.
- Added Db2 z/OS `FOR MIXED DATA` for CHAR/VARCHAR and source-driven `WITH DEFAULT` rendering; nullable columns without an explicit source default do not receive a default.
- Added FK supporting-index analysis using same-order leading-column matching across PK, unique keys, and explicit indexes; missing coverage emits `PHYS-FK-INDEX-001` and never auto-creates an index.
- Added Db2 varying-character index-key detection with a DBA placeholder for `PADDED/NOT PADDED`.
- Added focused Physical Phase 1 regression coverage and `docs/PHYSICAL-PHASE1.md`.
- Direct Java 21 compilation and four-dialect physical smoke/regression verification passed. Full Maven execution remains environment validation because the Maven wrapper cannot download Maven 3.9.9 in the packaging environment.

# 2026-08-15 - Graphviz readability phase 2

- Added Graphviz-specific readability profiles without changing the canonical model, parsers, SQL generators, Mermaid exporter, or database deployment logic.
- Added `GraphvizRenderOptions` with `includeDisconnectedTables`, `showFkLabels`, and `clusterBySchema`.
- Preserved the Phase-1 default output exactly (`all selected tables`, FK labels visible, no clustering unless requested).
- Added batch `schema-compact.dot`: connected tables only, FK labels visible, clustered by schema.
- Added batch `schema-overview.dot`: connected tables only, FK labels hidden, clustered by schema.
- Existing `schema-dependency.dot` and `schema-clustered.dot` remain for backward compatibility.
- Added connected-table counts and explicit Full/Compact/Overview profile definitions to `graphviz/batch/summary.txt`.
- Extended focused Graphviz and ZIP-pipeline tests for disconnected-table filtering, FK-label suppression, clustering, deterministic output, and new packaged artifacts.

# 2026-08-15 - Graphviz DOT export phase 1

- Added Graphviz as a second textual diagram exporter beside the frozen Mermaid implementation.
- Added per-document `.graphviz.dot` output generated from the same prepared canonical schema used by SQL/JSON/Mermaid generation.
- Added batch `schema-dependency.dot` and schema-clustered `schema-clustered.dot` outputs under `graphviz/batch/`.
- Batch Graphviz applies the same strict duplicate policy as Mermaid: no historical version is auto-selected; duplicate and missing FK targets are reported in `issues.csv`.
- Added `graphviz/batch/summary.txt` with table/FK counts and explicit `DOT_ONLY_NO_GRAPHVIZ_EXECUTION` renderer mode.
- Added focused exporter/batch tests and extended the ZIP pipeline regressions to assert Graphviz packaging.
- Direct Java 21 compilation passed, and generated ER/dependency/clustered DOT smoke artifacts were successfully parsed by an external Graphviz `dot -Tsvg` validation probe.
- No parser, canonical-model, DDL-dialect, Mermaid-renderer, or database deployment logic was changed.

# 2026-08-15 - Batch Mermaid diagrams in ZIP generation

- Added batch-level Mermaid ER and dependency diagrams to the normal ZIP generation pipeline.
- Per-document `.mermaid.mmd` files remain unchanged and continue to be generated beside Oracle/PostgreSQL/SQL Server/Db2/JSON outputs.
- Added `MermaidBatchDiagramExporter`; it combines only qualified table names that occur exactly once in the batch.
- Duplicate qualified table names are never auto-selected. Every definition of a duplicated name is excluded from the batch graph and reported as `INPUT_DUPLICATE_TABLE`.
- Foreign keys targeting an excluded duplicate table are reported as `INPUT_DUPLICATE_TABLE_TARGET`; targets outside the unique batch are reported as `MISSING_REFERENCED_TABLE`.
- Added `batch-schema-er.mmd`, `batch-schema-dependency.mmd`, `batch-mermaid-issues.csv`, and `batch-mermaid-summary.txt` to generated ZIP output.
- Added focused regression coverage plus the `SchemaDocuments3ZipMermaidOutputIT` assertions for the new batch artifacts.
- Direct Java 21 compilation and smoke verification of the batch exporter passed. Full Maven validation remains user-environment execution because the packaging environment cannot download Maven 3.9.9.

# 2026-08-14 - Mermaid canonical JSON pilot

- Added `CanonicalJsonMermaidPilotIT` to generate real Mermaid artifacts directly from canonical JSON snapshots without reopening Word documents, generating SQL, or connecting to a database.
- Preserved the production one-definition-per-qualified-table rule; historical duplicate-version selection is test-only, opt-in via `schemaforge.diagram.pilot.allowHistoricalSelection`, deterministic, and fully reported.
- Added FK-compatible closure selection plus optional connected/disconnected expansion for useful multi-table diagram pilots.
- Generates four UTF-8 `.mmd` artifacts: full ER, full dependency, root-depth ER, and root-depth dependency diagrams, together with selected-version, manifest, and summary reports.
- Added configurable seed table, target/max table counts, minimum physical FKs, minimum FK-chain depth, dependency depth, column visibility, and datatype visibility.
- No existing production Java source was changed; this phase validates the Phase-1 Mermaid exporter against real canonical data.
- Targeted Java 21 compilation of the new integration runner and its required production classes passed. Full Maven execution remains user-environment validation because the packaging environment cannot download Maven 3.9.9.

# 2026-08-14 - Mermaid diagram export phase 1

- Added a DBMS-neutral `DiagramExporter` extension over the frozen canonical `Table` model.
- Added Mermaid ER and dependency renderers without changing the Legacy Word parser, snapshot format, DDL generators, SQL dialects, or deployment behavior.
- Added scopes `ALL`, `SCHEMA`, `TABLE`, `TABLE_WITH_DEPENDENCIES`, and `SELECTED_TABLES`, including bounded dependency traversal via `dependencyDepth`.
- Physical FK relationships are rendered by default; optional logical relationships use dashed dependency arrows.
- Preserved the strict duplicate-table rule via `INPUT_DUPLICATE_TABLE` and added same-schema resolution for unqualified FK targets.
- Added UTF-8 `.mmd` file writing, Mermaid-safe node identifiers, unresolved-FK comments, sample outputs, and focused regression tests.
- Java 21 direct compilation and runtime smoke verification passed. Full Maven execution remains user-environment validation because the packaging environment cannot download Maven 3.9.9.

# 2026-08-14 - SchemaForge V4 final baseline freeze

- Froze baseline `SCHEMAFORGE-V4-FINAL-20260814` after a full regression result of 270 tests, 0 failures, 0 errors and 3 intentionally skipped database-execution integration tests.
- Recorded successful real-database historical validation for Oracle, PostgreSQL and SQL Server.
- Recorded successful integrated FK validation on all three DBMS, including the 15-table / 13-FK large pilot in FULL mode.
- Recorded canonical dependency coverage: 1,285 physical FK definitions, 605 distinct physical FK relations, 5 distinct self-reference relations, and 2 cycle candidates classified as `HISTORICAL_AGGREGATE_ONLY`.
- Added final baseline, release-note and validation-evidence documents under `docs/release/`.
- Final packaging does not change Java production or test source.

## 2026-08-11 - V4 freeze preparation: historical dependency coverage

- Added `CanonicalJsonDependencyCoverageIT`, a test-only canonical-JSON coverage runner for self-referencing foreign keys and multi-table dependency-cycle candidates across the full historical regression corpus.
- The coverage runner intentionally accepts multiple historical definitions of the same qualified table and never selects an effective production version; production `INTEGRATED` input remains strictly one definition per qualified table.
- Added separate CSV reports for self references, aggregate dependency edges, aggregate cycle candidates, missing referenced tables, and snapshot-read errors.
- Historical multi-table SCCs are explicitly labeled `HISTORICAL_AGGREGATE_CANDIDATE` because edges from different versions may not coexist in a normal production input; self references are intrinsic to the individual canonical table definition.
- Added `HistoricalDependencyCoverageTest` covering duplicate-version counting, self-reference detection, three-table cycle detection, and missing-target handling.
- No production source, Legacy Word parser, canonical snapshot format, DDL generator, dialect, deployment planner, renderer, or database execution behavior changed.
- Java 21 direct compilation and smoke execution passed for the new dependency-coverage core. Full Maven regression could not be executed in the packaging environment because the Maven wrapper requires an unavailable Maven download; run `mvnw.cmd clean test` in the project environment before declaring the baseline frozen.

## 2026-08-10 - SQL Server FULL cleanup foreign-key pre-drop

- Fixed `SqlServerDirectoryExecutionTest` FULL-mode destructive cleanup when target tables are still linked by foreign keys from a previous integrated run.
- The runner now discovers foreign keys declared by the integrated script and drops any matching existing SQL Server constraints before dropping target tables.
- Added separate FK-cleanup counters to the execution summary while preserving table cleanup counters.
- HISTORICAL behavior and production DDL generation are unchanged.
- Added regression coverage for SQL Server `ALTER TABLE ... DROP CONSTRAINT` cleanup syntax.
- Java 21 offline compilation passed for the updated runner and regression tests; Maven wrapper execution was unavailable in the packaging environment because it could not download Maven.

## 2026-08-10 - Oracle integrated SQL*Plus directive splitter fix

- Fixed `OracleSqlDirectoryExecutionTest` so SQL*Plus client directives such as `PROMPT` are skipped even when they are preceded by a comment-only integrated-deployment header.
- Prevented a `PROMPT` line from being merged with the following `CREATE TABLE` and sent to Oracle JDBC as one invalid statement (`ORA-00900`).
- The fix is test-runner-only: no change was made to the Word parser, canonical JSON, deployment planner, integrated renderer, `DdlGenerator`, or any Oracle/PostgreSQL/SQL Server dialect.
- Added `OracleStatementSplitterTest` covering the integrated header/PROMPT case and quoted `PROMPT` text inside a real SQL literal.
- Java 21 compilation plus direct regression probe passed (`ORACLE_SPLITTER_PROMPT_FIX_OK`). Maven wrapper execution remains unavailable in the packaging environment because the wrapper cannot download Maven.

## 2026-08-10 - Integrated SQL renderer baseline

- Added `IntegratedSqlRenderer` and `IntegratedSqlScript` to render one ordered integrated deployment for Oracle, PostgreSQL and SQL Server from `IntegratedSchemaDeploymentPlan`.
- Added public integrated-rendering entry points to `DdlGenerator`; all statement syntax delegates to the same private renderers already used by the proven HISTORICAL pipeline.
- Integrated ordering is now pre-table schema/sequence work, phase 1 CREATE TABLE/PK, phase 2 CHECK/UNIQUE/index, phase 3 physical FK, and phase 4 comments/grants.
- Circular FK dependencies remain deployable because every table is rendered before phase-3 foreign keys.
- Added cross-dialect renderer tests, including the PostgreSQL unqualified index-name regression and SQL Server post-create `CHECK CONSTRAINT` behavior.
- Java 21 offline compilation and runtime smoke tests passed for all three dialects. Fixed-clock SHA-256 comparison proved the existing HISTORICAL `DdlGenerator.generate(...)` output is byte-for-byte unchanged for Oracle, PostgreSQL and SQL Server.
- Maven wrapper execution is not available in the packaging environment because it cannot download Maven; the user-side Maven regression command is provided separately.

## 2026-08-10 - Integrated schema deployment planner baseline

- Added `IntegratedSchemaDeploymentPlanner` as a DBMS-neutral layer on top of the proven FK analyzer without changing the stable Oracle/PostgreSQL/SQL Server generators or HISTORICAL runners.
- Added deterministic deployment collections for pre-table sequences, phase-1 tables, phase-2 check/unique/index objects, phase-3 physical foreign keys, and phase-4 tables containing comments/grant metadata.
- Physical foreign keys with omitted target schemas are resolved to the owner-table schema and are scheduled only after all CREATE TABLE work; logical foreign keys remain analysis-only.
- FK cycles remain deployable because all physical FKs are isolated in phase 3. Blocking FK analysis findings stop planning with `INTEGRATED_DEPLOYMENT_BLOCKED`.
- Added `IntegratedSchemaDeploymentPlannerTest` covering deterministic ordering, same-schema resolution, logical-FK exclusion, missing-parent blocking, and circular dependencies.
- Java 21 offline compilation plus runtime smoke probes verified phase planning and cycle handling; Maven could not be executed in the packaging environment because Maven wrapper download access is unavailable.

## 2026-08-09 - Integrated foreign-key analysis baseline

- Added a DBMS-neutral integrated FK analysis layer without changing the proven Oracle/PostgreSQL/SQL Server DDL generators or HISTORICAL execution runners.
- Defined the production integrated-input contract: every qualified table/sequence must be supplied exactly once; multiple historical versions are rejected as `INPUT_DUPLICATE_TABLE`/`INPUT_DUPLICATE_SEQUENCE` instead of being auto-selected.
- Added physical-FK resolution for omitted target schemas, missing parent/column detection, canonical PK/UNIQUE target validation, logical-FK reporting, self-reference reporting, and dependency-cycle detection.
- Added `CanonicalJsonForeignKeyAnalysisIT` to analyze canonical JSON directly without reopening Word documents and to write summary, issue, duplicate, and snapshot-error reports.
- Dependency cycles are warnings rather than blockers because the planned integrated deployment uses a two-phase `CREATE TABLE` then `ADD FOREIGN KEY` strategy.
- Java 21 offline probes verified valid FK resolution and strict duplicate-table rejection; full Maven execution remains environment-dependent.

## 2026-08-09 - Canonical JSON DDL output-collision preservation

- Fixed the 4,768-snapshot to 4,766-file overwrite gap caused by two Legacy Word source pairs whose names differ only by a space immediately before `.doc`.
- Added `CollisionSafeScriptTargetAllocator`: normal output names remain unchanged; only an actual same-run path collision receives a deterministic `__sf_<hash>` suffix while preserving the `.oracle.sql`, `.postgresql.sql`, or `.sqlserver.sql` suffix.
- Added a dedicated output-collision CSV report and an end-of-run invariant that the number of successful generations equals the number of unique SQL files written for every platform.
- Kept all 4,768 canonical snapshots; no Word re-parse, snapshot-format change, or dialect change is involved.
- Direct Java 21 verification confirms the two whitespace-normalized logical names reserve distinct Oracle SQL targets.

## 2026-08-09 - Canonical JSON PostgreSQL fail-fast probe correction

- Fixed `CanonicalJsonDirectoryToDdlIT` PostgreSQL invariant probe to use the valid identifier `SCHEMAFORGE_PROBE`; the previous `__PROBE__` value was rejected by the canonical `Identifier` contract before any DDL could be generated.
- Moved dialect invariant initialization outside the per-snapshot loop so a global dialect regression fails once and immediately instead of producing thousands of identical `GENERATION_FAILED` rows.
- Added exact duplicate snapshot suppression for the JSON-to-DDL path using normalized source path plus SHA-256; raw snapshot files are preserved and duplicates are reported separately.
- Kept the PostgreSQL `CREATE INDEX` fix: index names remain unqualified while target table names remain schema-qualified.
- No Legacy Word Parser or canonical JSON snapshot format changes.
- Java 21 offline probes verified PostgreSQL index rendering, Oracle reserved identifier rendering, and SQL Server precision bounding.

## 2026-08-08 - Canonical JSON clean-output directory recreation fix

- Fixed `CanonicalJsonDirectoryToDdlIT` so `cleanOutput=true` recreates the platform output directory before writing root-level snapshot DDL files.
- Prevents all generated files from failing with a missing-output-directory error after a clean generation run.
- No Legacy Word Parser or canonical snapshot format changes.

## 2026-08-08 - PostgreSQL CREATE INDEX regression guard

- Added a dedicated fast regression test that proves PostgreSQL index names are never schema-qualified while table names remain schema-qualified.
- Added `POSTGRESQL_SCHEMA_QUALIFIED_INDEX_NAME` to the PostgreSQL static DDL sanity checker so invalid `CREATE INDEX schema.index ...` output can no longer be reported as clean generation.
- Corrected the stale PostgreSQL generator assertion that still expected a schema-qualified unique index name.
- Added a PostgreSQL dialect invariant check to JSON-to-DDL batch generation.
- Added optional `schemaforge.snapshot.ddl.cleanOutput=true` to delete only the selected platform output directories before regeneration and prevent old timestamped SQL files from mixing with a new run.
- Java 21 smoke verification confirms the dialect returns an unqualified index name and the sanity checker rejects schema-qualified PostgreSQL index names.

## 2026-08-08 - JSON-driven PostgreSQL/SQL Server dialect hardening

- Corrected PostgreSQL `CREATE INDEX` rendering so the index name is not schema-qualified while the target table remains schema-qualified.
- Bounded SQL Server exact numeric precision above 38 to `DECIMAL(38,s)` and temporal precision above 7 to `DATETIME2/DATETIMEOFFSET/TIME(7)` without changing the canonical JSON snapshot.
- Added dialect-mapping findings to `CanonicalJsonDirectoryToDdlIT` so bounded SQL Server mappings remain auditable in the generation report.
- Added `SqlServerDirectoryExecutionTest` with recursive JDBC execution, `HISTORICAL`/`FULL` modes, guarded drop-before-create, `GO` batch-separator filtering, SQLSTATE/vendor-code classification, and CSV/text reports.
- Verified the changed dialect code with Java 21 smoke tests: PostgreSQL index names are unqualified, `NUMBER(70)` renders as `DECIMAL(38,0)`, `NUMBER(115,5)` as `DECIMAL(38,5)`, and `TIMESTAMP(26)` as `DATETIME2(7)`.
- Kept the Legacy Word parser and the canonical snapshot contents unchanged.

## 2026-08-08 - Canonical JSON snapshot cache for Legacy Word

- Added a versioned DBMS-neutral canonical snapshot DTO separate from the domain model and from all SQL dialects.
- Added lossless `DatabaseSchema` snapshot mapping for tables, columns, PK/FK/UK/check constraints, indexes, sequences, metadata, descriptions and physical options.
- Added SHA-256 source identity, parser/model/snapshot version cache invalidation and atomic UTF-8 JSON writes.
- Added `WordDirectoryToCanonicalJsonIT` for one-time/incremental Word-to-JSON materialization with `manifest.json` audit status.
- Added `CanonicalJsonDirectoryToDdlIT` so Oracle/PostgreSQL/SQL Server DDL can be regenerated without reopening Word documents.
- Added round-trip regression coverage and `docs/integration/CANONICAL-JSON-SNAPSHOT-CACHE.md`.
- Kept the Legacy Word parser and existing Word-to-DDL path behavior unchanged.

## 2026-08-08 - PostgreSQL directory execution test

- Added `PostgreSqlDirectoryExecutionTest` for recursive JDBC execution of generated PostgreSQL DDL.
- Added PostgreSQL SQLSTATE reporting, historical/full execution modes, safe drop-before-create support, and per-file CSV reports.
- Added PostgreSQL-aware splitting that skips `psql` meta-commands and preserves quoted/dollar-quoted SQL content.
- Smoke-verified the splitter against all 4,766 generated PostgreSQL scripts: 4,766 CREATE TABLE statements detected and 9,532 psql commands skipped.

## 2026-08-07 - Recursive Oracle/PostgreSQL/SQL Server Legacy DDL generation

- Added `WordDirectoryMultiDatabaseGenerationIT` to parse and prepare each Legacy/standard Word document once and render the same canonical model for Oracle, PostgreSQL and Microsoft SQL Server.
- Added per-platform output directories while preserving the input subdirectory structure and the centralized timestamped SQL naming policy.
- Added CSV and text summaries that distinguish parse failures, generation failures and scripts generated with static-validation findings.
- Added `PostgreSqlDdlSanityChecker` for cross-dialect leakage, malformed delimiters, explicit numeric/temporal precision and character-length checks.
- Hardened SQL Server exact-numeric mapping and offline validation so `DECIMAL` precision 0, negative scale, and scale greater than precision are reported before live execution.
- Reused the existing `OracleDdlSanityChecker` and `SqlServerOfflineDdlValidator` so all three generated dialects report pre-execution issues consistently.
- Kept discovery mode non-failing by default (`schemaforge.word.failOnErrors=false`) so a problem in one dialect or document does not prevent the rest of the 4,766-document corpus from being generated.

## 2026-08-06 - Complete class-level JavaDoc coverage

- Completed class-level JavaDoc for all 164 production and 83 test top-level Java types.
- Expanded Oracle and SQL Server CRUD controller documentation to state delegation, response and error-mapping boundaries.
- Documented the Legacy Word parser support types and their immutable intermediate extraction records.
- Added regression-boundary documentation to the remaining 23 undocumented test classes.
- Added `docs/CLASS-DOCUMENTATION-COVERAGE.md` and linked it from the documentation index.
- No runtime behavior, public API, SQL generation rule or package structure changed.
- Maven wrapper verification was attempted, but Maven 3.9.9 could not be downloaded in the execution environment.

## 2026-08-06 - Oracle execution root-cause hardening

- Added deterministic Oracle-safe rendering for exact reserved identifiers such as `ROWID`, `DESC`, `ROWNUM`, `GROUP`, `COMMENT`, `UID`, `ROW`, `USER`, and `LEVEL`; every table, column, PK, FK, index, and comment reference uses the same `SF_` physical-name mapping.
- Strengthened `LegacyDefaultValueNormalizer` with datatype compatibility, `NUMBER(p,s)` capacity, quoted-numeric, string-length, malformed signed-literal, and leaked datatype-declaration checks.
- Added a final Oracle default-expression guard in `OracleDialect` so an invalid default cannot reach executable DDL even when a non-legacy input path bypasses the legacy normalizer.
- Mapped oversized `VARCHAR2`/`CHAR` to `CLOB`, oversized `NVARCHAR2`/`NCHAR` to `NCLOB`, and oversized `RAW` to `BLOB` under the conservative Oracle `MAX_STRING_SIZE=STANDARD` policy.
- Suppressed standalone indexes that duplicate PK/UK column signatures and removed repeated columns inside a single index.
- Expanded `OracleDdlSanityChecker` for reserved names, datatype/default mismatches, numeric default capacity, string default length, malformed defaults, and standard Oracle character-length limits.
- Added `HISTORICAL` and `FULL` execution modes to `OracleSqlDirectoryExecutionTest`. Historical mode skips cross-table foreign keys and grants by default, stops a file after its `CREATE TABLE` fails, and reports cleanup counts separately to prevent cascaded errors from hiding root causes.
- Added regression coverage for the Oracle errors observed in the 4,766-file execution report: `ORA-03050`, `ORA-00932`, `ORA-01438`, `ORA-01722`, `ORA-00936`, `ORA-00910`, `ORA-01401`, `ORA-01408`, and `ORA-00957`.

## 2026-08-06 - Recursive Oracle SQL execution audit test

- Added `OracleSqlDirectoryExecutionTest` for recursively executing generated Oracle SQL files through JDBC.
- Continues after statement-level errors and writes detailed error, per-file and summary reports.
- Added guarded `dropBeforeCreate` mode for validating multiple historical versions in a disposable schema.
- Added SQL*Plus command filtering, quoted-semicolon handling and PL/SQL slash-terminator support.
- Added `docs/ORACLE-SQL-DIRECTORY-EXECUTION-TEST.md` with Windows execution instructions and safety controls.

## 2026-08-05 - EA Party probe compile and deduplication regression fix

- Fixed `EnterpriseArchitectPartyProbeTest` to use the canonical table accessor `qualifiedName().name()` instead of the nonexistent `Table.name()` method.
- Restored logical EA table deduplication by normalized `<SCHEMA>.<TABLE>` while retaining every XMI element ID for association and foreign-key resolution.
- Prevented EA internal `owner` references such as `EAID_*`, `EAPK_*`, and GUID values from being interpreted as physical database schema names.
- Verified the supplied `Party_14050514.xml` probe with Java 21: 46 EA table elements resolve to 41 logical tables and `DPS.PARTY` is emitted once.

## 2026-08-05 - Legacy Oracle default, precision, and pre-write safety gate

- Added `LegacyDefaultValueNormalizer` to the actual canonical-column construction path for legacy DOC/DOCX parsing; explanatory text after numeric defaults is removed before `Column.defaultValue` is created.
- Applied the same normalization to the standard DOCX parser so a legacy-shaped DOCX cannot bypass the safety rule by being accepted by the standard parser first.
- Unsafe or unresolved natural-language defaults are omitted from executable DDL and recorded as `LEGACY_DEFAULT_DROPPED`; recoverable values are recorded as `LEGACY_DEFAULT_NORMALIZED`.
- Bounded Oracle rendering to `NUMBER` precision 38, `NUMBER` scale 127, and `TIMESTAMP` fractional-seconds precision 9.
- Added `OracleDdlSanityChecker` immediately before Oracle SQL file writes in the REST/ZIP path, EA per-table path, offline generation service, and recursive Legacy Word batch runner.
- The safety gate rejects leaked natural-language defaults, trailing default annotations, smart quotes, unknown bare identifiers, unbalanced/default-invalid tokens, and out-of-range Oracle precision before a file reaches the output directory.
- Added focused normalizer, Oracle safety-gate, and end-to-end parser-to-DDL regression tests for the reported `JTMSCUSTOMERS` values.
- Repaired and re-audited the supplied 4,766-file Oracle output set: all 4,766 files still contain `CREATE TABLE`; the reported default signatures, `NUMBER` precision above 38, `TIMESTAMP` precision above 9, and safety-gate findings are all zero after repair.

## 2026-08-03 - Legacy Word authoritative raw-metadata precedence

- Kept the bounded raw DOC metadata result separate from the noisy HWPF aggregate during table/entity resolution.
- When the raw pair matches the table token in the source file name and contains a valid Persian entity title, it is now used as the authoritative table-title source.
- Prevented a later-page header or a field-tail candidate from replacing the correct Persian title after the raw scanner had already recovered it.
- This correction targets the five remaining Legacy Word regressions in metadata confidence, canonical Persian table name, Oracle table comments and the Legacy REST output path.

## 2026-08-03 - Legacy Word metadata-pair selection fix

- Kept `poi-ooxml` and `poi-scratchpad` aligned with Legacy Word Parser Core 0.5.8 at Apache POI 5.5.1.
- Fixed duplicate legacy DOC metadata handling: when HWPF exposes an early blank or truncated entity header and the bounded raw-container scan later exposes the complete header for the same table, the parser now ranks matching pairs instead of accepting the first labelled pair.
- Preferred a valid normalized Persian entity title, then a technical entity value, while preserving deterministic source order for equivalent candidates.
- Restored `MetadataConfidence.TRUSTED`, canonical `Table.persianName`, Oracle `COMMENT ON TABLE`, and the legacy REST output path for the affected regression documents.

## 2026-08-03 - EA Alias table-comment alignment

- Generated `COMMENT ON TABLE` now uses the EA table Alias/Persian name when available.
- Preserved the full EA documentation as separate descriptive metadata and as a non-executable SQL header comment.
- Kept backward compatibility by falling back to the table description when Alias is empty.
- Updated Excel `COMMENT_STATUS` to compare the database comment with the Persian name, using the same fallback rule.
- Added Oracle DDL, EA REST output and Excel comparison regression coverage.

## 2026-08-03 - EA table Persian name separation

- Added `Table.persianName` to the canonical model.
- EA XMI `alias` is now preserved independently from `documentation`/table description.
- Kept the legacy fallback where Alias is the only table text, so existing EA inputs continue to produce table comments.
- Added `persianName` to `model.json`.
- Added a `TABLE_METADATA` sheet to comparison workbooks.
- Added the Persian table name to generated SQL as a non-executable header comment.
- Preserved the value through audit and grant enrichment.

## 2026-08-02 - Central SQL script naming policy

- Added one public `OutputFileNamer.scriptFileName(...)` rule for every generated SQL script.
- Standardized DDL names as `<logical-name>_<yyyyMMdd_HHmmss_SSS>.<database>.sql`.
- Standardized Oracle and SQL Server CRUD names with the same timestamp rule.
- Added timestamped EA run-all names as `<source>_<timestamp>.<database>.run-all.sql`.
- Updated EA REST DDL generation, Word/ZIP REST generation, standalone CRUD services, run-all references, manifests, and regression tests to use the same naming policy.
- Removed direct SQL file-name concatenation from production services.

## 2026-08-02 - REST CRUD path regression test fix

- Updated REST comparison test expectations for `oracle/crud/` and `sqlserver/crud/`.
- Updated the EA per-table CRUD placement fixture with a non-primary-key column so CRUD generation has an updatable column.
- No production behavior changed; this patch aligns regression tests with the new CRUD directory layout.
## 2026-08-02 - REST CRUD placement and Oracle identity sequences

- REST-generated Oracle CRUD packages are stored under `oracle/crud/`.
- REST-generated SQL Server CRUD procedures are stored under `sqlserver/crud/`.
- Metadata CRUD summary CSV entries now contain the relative artifact path inside the ZIP.
- Oracle renders logical identity columns with a named `SEQ_<TABLE>` sequence and `DEFAULT <SCHEMA>.SEQ_<TABLE>.NEXTVAL` instead of native identity syntax.
- Other dialects retain their existing identity behavior, including SQL Server `IDENTITY(1,1)`.
- Added end-to-end regression coverage for CRUD directory placement and Oracle sequence-based identity generation.

## 2026-08-02 - EA API schema override and primary-key identity

- Added optional `schema` parameter to `POST /api/v1/generate/ea-xml`.
- An explicit API schema now overrides EA schema/owner tagged values and the configured fallback schema.
- EA primary-key columns imported through the REST API are normalized as identity and `NOT NULL`; conflicting defaults/generated expressions are removed.
- Added parser and end-to-end REST service regression coverage for schema override and identity generation.

## 2026-08-01 - Oracle character length semantics

- Oracle now renders unspecified `VARCHAR2(n)` lengths as `VARCHAR2(n CHAR)`.
- Oracle now renders unspecified `CHAR(n)` lengths as `CHAR(n CHAR)`.
- Explicit `BYTE` and `CHAR` semantics remain unchanged; `NVARCHAR2` and `NCHAR` are not modified.
- Added regression coverage for default, explicit byte, explicit char, and national character types.

## 2026-08-01 - EA schema, checks, comments, and audit normalization

- Changed the built-in Enterprise Architect fallback schema from `EA_SCHEMA` to `COL`; `application.yml` now uses `${SCHEMAFORGE_EA_DEFAULT_SCHEMA:COL}`.
- Added EA check-constraint extraction from the `code` tagged value and removed the outer `CHECK (...)` wrapper before canonical mapping.
- Added EA table documentation extraction from the `documentation` tagged value.
- Removed embedded HTML formatting from EA table and column descriptions before comment generation.
- Standardized `CREATED_BY`, `CREATED_DATE`, `LAST_MODIFIED_BY`, and `LAST_MODIFIED_DATE` exactly once, in fixed order, at the end of every prepared table.
- Reassigned canonical column positions after audit normalization so SQL and Excel outputs use the same ordering.
- Added regression coverage for EA defaults/checks/comments and audit replacement/order.

## 2026-07-29 - REST metadata CRUD artifacts

- Added Oracle CRUD package and SQL Server CRUD procedure artifacts to Word and ZIP REST archives.
- Added timestamped metadata CRUD summary CSV with explicit generated/skipped/failed status.
- Kept dedicated `/oracle/crud` and `/sqlserver/crud` endpoints unchanged.
## 2026-07-29 - SQL Server metadata-based CRUD procedures

- Added `POST /api/v1/generate/sqlserver/crud` with JSON `schema` and `table` input.
- Added `SqlServerCrudGenerationService` using the live SQL Server metadata repository rather than Word or EA input.
- Added centralized SQL Server CRUD naming and generation for `<TABLE>_CREATE`, `_UPDATE`, `_DELETE`, `_GET_BY_ID`, and `_SEARCH`.
- Added exact metadata-derived parameter types, identity/sequence/GUID generated-key detection, `OUTPUT INSERTED` output parameters, audit-column handling, bounded pagination, `TRY...CATCH`, and `THROW` error contracts.
- Kept transaction ownership with the caller; generated procedures contain no `BEGIN TRANSACTION`, `COMMIT`, or `ROLLBACK`.
- Added configured `GRANT EXECUTE` generation, unit/controller/service coverage, and `SqlServerCrudLiveIT` under the explicit `sqlserver-live` profile.

## 2026-07-29 - REST regression schema expectation correction

- Updated Word REST regression fixtures to use the actual `BIM` schema parsed from `MCB.BIM.TBL.PROVINCES.V1.2.docx` instead of the stale `DPS` expectation.
- Corrected comparison-workbook repository stubs and expected workbook names from `DPS.PROVINCES` to `BIM.PROVINCES`.
- Corrected Oracle tablespace, grant, PostgreSQL, Db2 for z/OS, and SQL Server assertions to the `BIM` schema.
- No production generator behavior changed; this release fixes two stale regression tests exposed by the complete Maven suite.

## 2026-07-29 - SQL Server trusted constraints and comment ordering

- SQL Server CHECK and physical FOREIGN KEY constraints are emitted with `WITH CHECK` and an explicit `CHECK CONSTRAINT` statement.
- Table and column descriptions are emitted before indexes and foreign keys so `MS_Description` metadata is preserved even when a later dependency fails.
- SQL Server generator regression assertions cover trusted-constraint syntax and comment ordering.

## Unreleased - Repository hygiene

- Added a root `.gitignore` for Maven build output, IDE metadata, logs, temporary files, local generated artifacts, environment-specific configuration, and local credentials/key stores.
- Removed generated `target` content and IntelliJ `.idea` metadata from the distributable source tree.
- Preserved Maven Wrapper files under `.mvn/wrapper`.


## 2026-07-28 - ZIP batch regression fixture correction

- Corrected `SchemaForgeApiZipBatchTest` so its invalid DOCX contains a valid metadata table but intentionally omits the column specification table.
- Aligns the fixture with the asserted parser failure: `Column specification table was not found`.
- No production behavior or REST contract changed in this correction.


## Unreleased

### Added - generated schema bootstrap blocks

- Added one schema bootstrap fragment before generated sequences and tables for every schema that owns generated objects.
- PostgreSQL now emits idempotent `CREATE SCHEMA IF NOT EXISTS ... AUTHORIZATION CURRENT_USER`.
- Microsoft SQL Server now emits idempotent `IF SCHEMA_ID(...) IS NULL EXEC(N'CREATE SCHEMA ... AUTHORIZATION [dbo]')` without requiring `GO`, so JDBC execution remains supported.
- Oracle now emits a non-executable `CREATE USER` provisioning template because an Oracle schema is a database user and secure password/tablespace decisions belong to DBA provisioning.
- Db2 for z/OS now emits a DSNHSP `CREATE SCHEMA AUTHORIZATION` template because z/OS schema definitions require the schema processor rather than ordinary interactive DDL execution.
- Added schema counts to the generated object summary and extended SQL Server offline/live validation coverage to include generated schema creation.

### Added - Oracle metadata-based CRUD package generation

- Added `POST /api/v1/generate/oracle/crud` with JSON `schema` and `table` input.
- Added `OracleCrudGenerationService` using the Oracle metadata repository rather than Word or EA input.
- Added `OracleCrudPackageGenerator` producing `PKG_<TABLE>` with `CREATE_ROW`, `UPDATE_ROW`, `DELETE_ROW`, `GET_BY_ID`, and `SEARCH`.
- Added `%TYPE` parameter anchoring, identity/sequence-default key detection, `RETURNING INTO`, audit-column handling, bounded search pagination, and exception-based errors.
- Kept transaction ownership with the caller; generated packages contain no `COMMIT`, `ROLLBACK`, or autonomous transaction.
- Added configured `GRANT EXECUTE` generation and regression coverage for generated keys, composite keys, search filters, service orchestration, and REST download responses.


### Fixed - REST ZIP batch fault isolation

- Prevented one malformed or non-specification `.docx` from aborting the entire `/api/v1/generate/zip` request with HTTP 400.
- Added per-document staging so failed documents cannot leave partial SQL/JSON/Excel artifacts in the response archive.
- Added `batch-generation-summary.csv` and `batch-generation-errors.log` to every ZIP batch response.
- Ignored Word lock files (`~$*.docx`), hidden dot files, AppleDouble files, and `__MACOSX` metadata entries.
- Added regression coverage for mixed valid/invalid ZIP input and all-invalid diagnostic archives.

### Added - Microsoft SQL Server core dialect

- Registered `SQLSERVER` with command name `sqlserver` and aliases `sql-server`, `mssql`, and `sqlsrv`.
- Added `SqlServerDialect`, `SqlServerTypeMapper`, `SqlServerIdentifierRenderer`, and `SqlServerExpressionMapper`.
- Added `SAFE` exact numeric mapping and `OPTIMIZED` lossless `SMALLINT`/`INT`/`BIGINT` mapping through the shared numeric strategy.
- Added SQL Server sequences, `IDENTITY(1,1)`, computed columns, primary/unique/check/foreign-key constraints, included and filtered indexes, filegroup placement, extended-property comments, and grants.
- Added Word/ZIP REST artifacts, EA per-table SQL artifacts, and SQLCMD-compatible `run_all.sql` files with the `.sqlserver` suffix.
- Extended strategy-aware metadata type equivalence and SQL script parsing for SQL Server.
- Added regression coverage for platform selection, capabilities, datatype mapping, identifiers, expressions, complete DDL, REST archives, EA archives, numeric equivalence, and statement parsing.
- Completed SQL Server live metadata and execution validation in the validation completion pack described below.

### Changed - logical foreign keys and PostgreSQL UTF-8 hardening

- `/Y` foreign-key references remain executable physical constraints.
- `/N` references are now emitted as `[LOGICAL FOREIGN KEY]` hints and are no longer executed as database constraints.
- Added physical/logical foreign-key counts to the generated object summary.
- PostgreSQL scripts now start with `\encoding UTF8` before `\set ON_ERROR_STOP on` to protect Persian comments when executed by `psql`.
- Confirmed SQL Server identifier output remains deterministic `UPPER_SNAKE_CASE`.

### Fixed
- Corrected `Db2ZosOfflineDdlValidatorTest` to expect the four executable statements actually generated for one table with a primary key and one unique key: `CREATE TABLE`, primary-key enforcing index, unique constraint, and unique-key enforcing index.
- The production DB2 z/OS DDL generator and offline validator were already correct; only the test statement-count assertion was wrong.

## 2026-07-27 - Strategy-aware numeric metadata comparison

- Added shared PostgreSQL and Db2 for z/OS native-integer capacity profiles.
- Added `NumericTypeEquivalenceService` for SAFE/OPTIMIZED-aware comparison.
- In OPTIMIZED mode, exact numeric metadata is equivalent to the lossless native integer selected by the active dialect.
- Removed false `METADATA_DATATYPE_MISMATCH` and `W:TYPE` findings caused only by numeric optimization.
- Kept fractional numerics, precision above BIGINT capacity, unrelated datatypes, and SAFE-mode differences as real mismatches.
- Applied the same equivalence policy to metadata validation, rename candidate matching, and Excel column comparison.
- Added regression coverage for PostgreSQL, Db2 for z/OS, SAFE behavior, fractional values, capacity boundaries, and workbook output.

- Updated REST regression tests to use the schema extracted from `MCB.BIM.TBL.PROVINCES.V1.2.docx` (`DPS`) when matching comparison workbooks, tablespaces, and grants.
- Added table-level inline validation hints directly on the `CREATE TABLE` line for schema-not-found, same-name table in other schemas, spelling, and singular table-name findings.
# SchemaForge v4.1

- Added English class-level JavaDoc across production and test sources.
- Deprecated the legacy Phase1 command-line entry point and its obsolete tests.
- Performed non-functional Java source whitespace cleanup.
- Added `docs/V4.1-DOCUMENTATION-CLEANUP.md`.
- Deferred broad refactoring until additional database dialect requirements are implemented.


## 2026-07-25 - Final script metadata validation
- Added schema existence validation for Oracle and PostgreSQL.
- Added table discovery across schemas.
- Added foreign-key referenced-schema resolution and missing/ambiguous table hints.
- Added singular column-name component validation with S-ending exceptions.
- Added SQL compact markers and JSON validation issues for all new checks.

## 2026-07-26 - Final FK grammar and table-name validation
- Parsed FK references in the forms `TABLE/Y`, `TABLE/N`, `SCHEMA.TABLE/Y`, and `SCHEMA.TABLE/N`.
- Preserved physical/logical (`Y/N`) and explicitly qualified schema information in JSON.
- Generated FOREIGN KEY DDL for both physical and logical references.
- Added `TABLE_NAME_NOT_PLURAL` / `W:TABLE-PLURAL` hints without renaming identifiers.

### Fixed
- Fixed FK parsing for plural referenced tables such as `LANGUAGES/Y`, `COUNTRIES/Y`, and `TIM. CALENDARS/N`; the trailing `S` is retained as part of the table name.
- Restored backward-compatible ForeignKey constructor semantics for deferrable and initially-deferred constraints.
- Updated Word FK parsing to use the full constructor including physical/logical and schema-explicit flags.
- Prevented PostgreSQL deferrability clauses from being lost after adding FK classification flags.

## REST regression hardening
- Restored timestamped names for JSON, Oracle SQL, PostgreSQL SQL, and downloaded ZIP archives.
- Updated the V1.2 regression document to the continuation-table version containing three foreign keys.
- Added an API service regression test covering shared timestamps, continuation columns, and all three foreign keys.

## 2026-07-26 - Phase closure: internal parser recovery hints

- Internal datatype normalization messages are no longer exposed as `RECOVERY_WARNING` in SQL or JSON validation findings.
- Only actionable parser findings remain user-visible: duplicate columns, missing datatypes, and missing Persian descriptions.
- `recovery.warningCount` and `recovery.warnings` now contain actionable recovery findings only.

## 2026-07-26 - Oracle default tablespace completion

- Oracle tables generated from Word, ZIP, EA XML, CLI and REST now receive `TABLESPACE TS_<SCHEMA>` when no explicit table tablespace is supplied.
- Oracle primary-key, unique-key and standalone indexes now receive `TABLESPACE ITS_<SCHEMA>` when no explicit index tablespace is supplied.
- Explicit physical options continue to override the schema-derived defaults.
- PostgreSQL behavior is unchanged.

## 2026-07-26 - Configured role grants

- Added `schemaforge.standards.grants` configuration for standard table privileges.
- Added the configured grants to every generated table for Oracle and PostgreSQL.
- Clarified that `U_DEVELOPER` and `U_DESIGNER` are database roles/principals, not application user ids.
- Moved all `GRANT` statements to the end of the executable SQL body.
- Corrected grant rendering to standard SQL order: `GRANT <privileges> ON <table> TO <role>`.
- Explicit table-level `GRANTS` physical options are preserved and merged without duplicates.

## 2026-07-26 - Document-to-database Excel comparison

- Restored the SchemaForge v3 one-sheet, 22-column comparison workbook layout.
- Added live Oracle and PostgreSQL table inspection without application-level caching.
- REST Word, ZIP and EA XML requests now include one comparison workbook per target database when the exact table already exists.
- Added canonical datatype, nullability, default, comment, key, index and check-constraint comparison.
- Preserved the historical `COLUMN_USAGE` and `DIFF` columns and timestamped workbook naming.
- Added automated workbook and REST ZIP regression tests.

## 2026-07-26 - Comparison Excel correction

- Canonicalized `CHECK ... IN (...)` values before comparison so order-only differences such as `IN (0,1)` and `IN (1,0)` are equal.
- Assigned sequential ordinal positions to generated audit columns.
- Avoided false Oracle identity differences when the document logical identity and database sequence default are semantically equivalent; remaining differences use `IDENTITY_MODE`.
- Qualified consolidated JSON metadata-validation paths and messages with the database dialect.
- Improved database check-constraint display to `NAME: expression`.
- Row color changes intentionally deferred.

## 2026-07-26 - Comparison CHECK canonicalization fix

- Fixed the Excel comparison of `CHECK ... IN (...)` constraints after whitespace normalization.
- `IN (0, 1)`, `IN (1, 0)` and spacing variants are now treated as semantically equivalent.
- Added a regression test to prevent false `CHECK CONSTRAINT` differences caused only by list order.

## 2026-07-26 - Comparison Excel row order and row fills

- Rows in comparison workbooks now follow the document column order first.
- Database-only rows are appended after all document rows.
- Applied the established Excel row background styles:
  - header: `GREY_40_PERCENT`
  - added/document-only column: `BRIGHT_GREEN`
  - dropped/database-only column: `RED`
  - modified or rename candidate row: `LIGHT_ORANGE`
  - position-only row: `GREY_25_PERCENT`
  - unchanged row: `LIGHT_CORNFLOWER_BLUE`
- Kept one comparison workbook per database dialect (`*.oracle.xlsx`, `*.postgresql.xlsx`).

## 2026-07-26 - Excel comparison historical fill correction

- Restored the historical v3 row-fill behavior.
- Unchanged rows now have no background fill.
- `GREY_40_PERCENT` remains for the header.
- `BRIGHT_GREEN` remains for document-only columns.
- `RED` remains for database-only columns.
- `LIGHT_ORANGE` remains for modified or rename-candidate rows.
- `GREY_25_PERCENT` remains for position-only differences.
- `LIGHT_CORNFLOWER_BLUE` is no longer applied to unchanged rows because it was not used by the real v3 comparison workbooks.

## 2026-07-26 - Excel database-object comparison completion

- Added thin borders to all cells in the column and database-object comparison sheets.
- Preserved the historical v3 row colors: green for ADD, red for DROP, orange for MODIFY, grey for position-only changes, and no fill for unchanged rows.
- Added separate comparison sheets for primary keys, foreign keys, non-unique indexes, and unique constraints/indexes.
- Composite and single-column indexes are compared by ordered key columns, sort direction, index type, include columns and predicate.
- Primary-key, foreign-key and unique-object changes now include names and complete canonical definitions rather than column membership only.
- Kept document object order first and appended database-only objects afterwards.
- Added a database-neutral writer entry point that receives the generic dialect contract; database-specific metadata adapters remain outside the Excel writer.

## 2026-07-26 - Legacy Word index-token parsing

- Reads legacy `I1`, `I2`, ... index group tokens when they are stored in the `Primary/Foreign Key` column.
- Keeps the dedicated `Index` column as the first-priority source when both layouts are present.
- Groups repeated tokens as composite indexes in document row order.
- Propagates parsed indexes through the canonical model to JSON, Oracle/PostgreSQL DDL, and Excel comparison.
- Adds `WordLegacyIndexParsingTest` for the `I1` composite-index scenario.

## 2026-07-26 - Enterprise Architect XML/XMI phase

- Replaced the minimal EA class importer with an EA XMI 1.x table-model importer.
- Added configurable `schemaforge.ea.default-schema` fallback.
- Imported ordered columns, datatype details, nullability and Persian descriptions.
- Imported PK, FK associations, referential actions and simple/composite indexes.
- Reused the existing canonical JSON, Oracle/PostgreSQL DDL and Excel comparison pipeline.

## 2026-07-26 - Enterprise Architect per-table REST output

- EA XML/XMI REST generation now writes one Oracle and one PostgreSQL SQL file per table.
- Added per-dialect folders and per-table comparison workbook folders.
- Added consolidated `model.json` and `manifest.json`.
- Added Oracle and PostgreSQL `run_all.sql` files ordered by internal foreign-key dependencies.
- Added cycle reporting in the run-all header without changing Word or ZIP input behavior.
- Added regression coverage for per-table file names, manifest contents and dependency ordering.

## Numeric mapping foundation
- Added configurable `SAFE` and `OPTIMIZED` numeric mapping strategies.
- Added a shared lossless numeric optimization service.
- PostgreSQL optimized mapping: NUMBER(1..4,0) -> SMALLINT, NUMBER(5..9,0) -> INTEGER, NUMBER(10..18,0) -> BIGINT.
- Decimal values, unbounded NUMBER, and precision above 18 remain NUMERIC.
- Default remains SAFE for backward compatibility.

## 2026-07-27 - LanguageTool test stability

- Stabilized the local LanguageTool HTTP stub used by `LanguageToolSpellCheckServiceTest`.
- Increased only the local test connect/request timeouts to tolerate slow Windows CI hosts.
- Executed the test HTTP handler directly on the server dispatcher and closed each exchange deterministically.
- Disabled fail-open for successful-response tests so transport failures are reported directly instead of appearing as spelling results.
- Production spell-check behavior and numeric mapping behavior are unchanged.

## 2026-07-27 - Db2 for z/OS numeric mapping foundation

- Added `Db2ZosTypeMapper` as the first v4.2 Db2 for z/OS component.
- SAFE mapping preserves exact numbers as `DECIMAL(p,s)`.
- OPTIMIZED mapping uses `SMALLINT`, `INTEGER` and `BIGINT` at lossless precision boundaries.
- Added explicit rejection for unbounded NUMBER and precision above the Db2 z/OS DECIMAL limit of 31.
- This foundation is now followed by the registered core dialect integration below.

## 2026-07-27 - Db2 for z/OS core dialect integration

- Registered `DB2_ZOS` with command name `db2zos` and aliases `db2-zos`, `db2`, and `zos`.
- Added `Db2ZosDialect`, identifier rendering, expression conversion, and common Oracle-to-Db2 datatype mapping.
- Connected Db2 output to CLI, REST Word/ZIP generation, and EA per-table generation.
- Added Db2 SQL and comparison-workbook artifact naming using the `.db2zos` suffix.
- Added Db2 table placement through `IN TABLESPACE` and `IN DATABASE.TABLESPACE`.
- Added Db2-aware foreign-key action handling and rejection of unsupported update/delete rules.
- Added regression coverage for type mapping, identifiers, expressions, capabilities, complete DDL, REST archives, and EA archives.
- Kept the existing dual Oracle/PostgreSQL JDBC validation runner explicitly dual; Db2 execution validation is deferred.
- Db2 catalog metadata access remains unavailable in production and resolves to the empty metadata repository.

## 2026-07-27 - Db2 for z/OS live metadata comparison

- Added the conditional `Db2ZosMetadataRepository` JDBC adapter.
- Added live catalog reads for tables, columns, primary/unique/check/foreign-key constraints and indexes.
- Connected `DB2_ZOS` to `MetadataRepositoryResolver`; disabled configurations still resolve to the empty repository.
- Added Db2 metadata datasource properties and environment-variable configuration.
- Enabled existing REST and EA comparison-workbook generation for Db2 when the exact table exists.
- Preserved strategy-aware `SAFE`/`OPTIMIZED` numeric equivalence in Db2 validation and Excel comparison.
- Added mapper and repository-resolution regression tests.
- Documented JCC configuration, catalog sources and first-phase limitations.

## 2026-07-27 - Db2 for z/OS validation completion pack

- Added explicit unique enforcing indexes for every Db2 primary key and unique constraint.
- Added deterministic `Db2ZosOfflineDdlValidator` checks for foreign-dialect syntax, unsupported `ON UPDATE`, decimal precision/scale, balanced delimiters, unexpected statement types and missing enforcing indexes.
- Added the read-only `Db2ZosConnectionProbeService` for JCC, server, schema, SQLID and catalog-access verification.
- Added `Db2ZosValidationRunner` with separate `generate`, `probe` and confirmation-gated `execute` modes.
- Added the explicitly invoked `Db2ZosLiveIT`, excluded from normal test discovery, which creates, verifies and removes disposable Db2 objects.
- Added an inactive `db2zos-live` Maven profile that accepts a local organization-approved JCC JAR without bundling or redistributing it.
- Added complete staged live-validation instructions in `docs/testing/DB2-ZOS-LIVE-VALIDATION.md`.


## 2026-07-27 - Microsoft SQL Server metadata and validation phase

- Added conditional `SqlServerMetadataRepository` and `JdbcSqlServerMetadataRepository` implementations.
- Added live reads from SQL Server `sys.*` catalog views for tables, columns, defaults, identity/computed columns, descriptions, PK/UK/FK/check constraints, rowstore indexes, include columns, filters and filegroups.
- Connected SQL Server to `MetadataRepositoryResolver`, REST comparison workbooks and EA per-table comparison output.
- Added SQL Server metadata datasource configuration and the Microsoft JDBC driver runtime dependency.
- Preserved SQL Server-native `date`, `rowversion`, max-length types and temporal scale zero in the canonical metadata model.
- Added `SqlServerOfflineDdlValidator` for datatype limits, delimiter checks, statement-family checks and foreign-dialect leakage.
- Added `SqlServerConnectionProbeService` for read-only server/database/schema and catalog-access verification.
- Added SQL Server repository, resolver, offline-validator and probe regression tests.
- Added SQL Server metadata and validation documentation.

## 2026-07-27 - Microsoft SQL Server live validation completion pack

- Added `SqlServerValidationRunner` with separate `generate`, `probe`, and confirmation-gated `execute` modes.
- Added deterministic CSV reports for SQL Server offline preflight and live JDBC execution results.
- Added the explicitly invoked `SqlServerLiveIT`, excluded from normal test discovery, for disposable schema creation, generated-DDL execution, catalog verification, metadata round-trip, Excel `SAME` verification, and cleanup.
- Covered table, column, primary key, foreign key, normal index with included columns, sequence, table descriptions, and column descriptions in the live verification model.
- Added the inactive `sqlserver-live` Maven profile using Failsafe; normal unit and regression builds remain database-independent.
- Replaced the deferred live-test notes with staged generate, probe, execute, and integration-test instructions in `docs/testing/SQL-SERVER-VALIDATION.md`.

## 2026-08-03 - Legacy Word parser first integration

- Added `POST /api/v1/generate/legacy-word?schema=<SCHEMA>` for legacy `.doc` and `.docx` table specifications that do not declare a schema.
- Integrated Legacy Word Parser Core 0.5.8 under `com.behsazan.schemaforge.specification.parser.legacy`.
- Legacy documents are mapped to the existing canonical `DatabaseSchema`, `Table`, `Column`, `PrimaryKey`, `UniqueKey`, `Index`, `IndexColumn`, and `ForeignKey` classes; the generation pipeline and output layout are shared with current Word documents.
- Added `poi-scratchpad` for binary `.doc` support.
- Added `WordDirectoryOracleGenerationIT`, an explicitly invoked recursive directory test that generates only Oracle DDL scripts for accepted current or legacy Word table documents.

## 2026-08-03 - Legacy DOC authoritative Persian title parsing

- Fixed canonical raw DOC metadata parsing for entity titles that legitimately begin with `تاریخچه تغییرات`.
- Added a dedicated bounded parser for `LegacyDocRawMetadataScanner` output instead of reusing the unbounded HWPF stop rules.
- Preserved short, clean history-title phrases while continuing to reject change-log grids, dated history rows, field metadata, and embedded labels.
- No REST contract, canonical domain model, output archive layout, or DDL generator behavior was changed.

## 2026-08-09 - SQL Server historical cleanup syntax fix
- Fixed `SqlServerDirectoryExecutionTest` cleanup SQL from PostgreSQL-style `DROP TABLE IF EXISTS ... CASCADE` to SQL Server `DROP TABLE IF EXISTS ...`.
- Added a regression test to prevent the PostgreSQL-only `CASCADE` clause from returning.

## 2026-08-09 - SQL Server HISTORICAL FK validation skip fix

- Fixed `SqlServerDirectoryExecutionTest` so a `CHECK CONSTRAINT` statement is skipped only when it matches a foreign key that was intentionally skipped earlier in the same script under `HISTORICAL` mode.
- Prevents SQL Server error 4917 (`Constraint ... does not exist`) after skipped cross-table foreign keys.
- Ordinary CHECK constraint validation remains executable and is not broadly suppressed.
- Added `SqlServerHistoricalForeignKeySkipTest` regression coverage.

## Integrated deployment pilot from canonical JSON

- Added `CanonicalJsonIntegratedDeploymentPilotIT` as a test-only real-data pilot runner.
- Keeps the production integrated input contract strict: one definition per `schema.table`.
- Historical multi-version selection is opt-in and test-only; every selected snapshot/source is reported.
- Builds an FK-compatible dependency closure, validates it, renders ordered integrated SQL for Oracle, PostgreSQL and SQL Server, and performs no database execution.

## 2026-08-10 - Integrated SQL Server FK type compatibility

- Added pre-render dialect-specific FK column type validation for integrated deployment.
- SQL Server now blocks incompatible FK pairs before database execution with `SQLSERVER_FK_TYPE_MISMATCH`.
- Historical generation remains unchanged.
- Integrated pilot auto-selection now requires the selected closure to render successfully on every requested platform.
- SQL Server execution runner suppresses the derivative `CHECK CONSTRAINT` after a failed FK creation so reports keep the root failure only.

## 2026-08-10 - Integrated Large Pilot selection

- Extended `CanonicalJsonIntegratedDeploymentPilotIT` with configurable large-pilot requirements.
- Added deterministic expansion from an FK-connected seed toward a target table count.
- Added cross-dialect compatibility checks for every expansion candidate.
- Added optional disconnected expansion for test-only historical corpora when one connected component is too small.
- Added minimum physical-FK count and FK-chain-depth requirements.
- Added summary metrics for chain depth, connected components, and self references.
- No production parser, canonical model, dialect, DDL generator, or historical execution behavior changed.

## 2026-08-11 - Self-reference and cycle freeze coverage pilots

- Added `CanonicalJsonSpecialDependencyPilotIT` as a test-only freeze-coverage runner for real self-referencing foreign keys and historical aggregate cycle candidates.
- Added deterministic `SpecialDependencyPilotSelector` logic that selects a cross-dialect deployable self-reference closure without changing the production one-version-per-table input rule.
- Added cycle classification that distinguishes a real coexisting deployable cycle from `HISTORICAL_AGGREGATE_ONLY`, canonical blockers, cross-dialect portability blockers, and an explicit combination-limit inconclusive state.
- Generates dedicated Oracle, PostgreSQL, and SQL Server integrated SQL only when a self-reference/cycle can coexist in one canonical schema and pass all requested DBMS render checks.
- Added four focused regression scenarios covering self-reference, external closure, a true two-table cycle, and a historical aggregate-only cycle.
- No `src/main` production source, parser, canonical model, dialect, historical generator, or execution behavior was changed.

## 2026-08-11 - Special dependency self-reference fallback

- Fixed `CanonicalJsonSpecialDependencyPilotIT` so historical self-reference coverage does not abort before cycle assessment when unrelated historical FK targets cannot be resolved.
- Added test-only `ISOLATED_SELF_REFERENCE` fallback: preserves the real table definition, local constraints/indexes/comments/options, and only the real self-referencing physical FK(s); unrelated external physical FKs are omitted only in this dedicated coverage pilot.
- Added explicit self-reference status/reason/mode and omitted-FK counts to special dependency reports.
- Added regression coverage for a real self-reference whose unrelated external FK closure is unavailable.
- Production `src/main` remains byte-for-byte unchanged from the previous baseline.

## 2026-08-15 - Mermaid Production REST Integration

- Added production canonical JSON diagram input loader with strict one-version-per-qualified-table policy.
- Added production Mermaid generation service returning deterministic `.mmd` artifacts.
- Added `POST /api/v1/diagram/mermaid/canonical-json` to the supported Spring Boot REST runtime.
- Endpoint accepts one `*.schema.json` snapshot or a ZIP of unique snapshots.
- Added ER/dependency type and ALL/SCHEMA/TABLE/TABLE_WITH_DEPENDENCIES/SELECTED_TABLES request options.
- Added ZIP traversal, entry-count, and uncompressed-size guards.
- Historical duplicate definitions fail with `INPUT_DUPLICATE_TABLE`; production code performs no historical version selection.
- Legacy Word parsing and existing Oracle/PostgreSQL/Db2/SQL Server DDL generation sources were not modified by this phase.

## 2026-08-15 - Batch ZIP output packaging

- Organized `generateFromZip(...)` output into `oracle/`, `postgresql/`, `sqlserver/`, `db2zos/`, `excel/`, `json/`, `mermaid/`, and `reports/` directories.
- Moved per-table Mermaid files to `mermaid/tables/` and batch Mermaid artifacts to `mermaid/batch/`.
- Kept artifact generation logic unchanged; this change only affects placement inside ZIP batch output.

## 2026-08-15 - Mermaid + Graphviz final baseline

- Froze Mermaid per-table and batch ER/dependency output after real 75-document ZIP validation.
- Froze Graphviz DOT Phase 1 and Phase 2 after per-table, batch dependency, clustered, compact, and overview validation.
- Preserved strict duplicate qualified-table handling with no automatic historical version selection.
- Preserved DOT-only runtime behavior; SchemaForge does not execute Graphviz binaries.
- Final regression: 313 tests, 0 failures, 0 errors, 3 skipped; BUILD SUCCESS.
- Baseline ID: `SCHEMAFORGE-V4-MERMAID-GRAPHVIZ-FINAL-20260815`.

## 2026-08-16 - Physical Phase 1 corpus hardening: LOB boundary and Word variants

- Added regression coverage that keeps BLOB/LOB placement outside Physical Phase 1 for Oracle, PostgreSQL, SQL Server, and Db2/zOS.
- Confirmed Phase 1 does not emit Oracle LOB storage clauses, SQL Server `TEXTIMAGE_ON`, or Db2 auxiliary/LOB tablespace provisioning.
- Added conservative recovery for the real DOCX datatype typo `NUMBER)5)` -> `NUMBER(5)`.
- Added support for the real column-header variant `Data RANGE` as a datatype header while keeping the ordinary `Range` column distinct.
- Added an in-memory Word regression fixture for those two document defects; the fixture intentionally excludes `SPACE_FREE_NAME`.
- Previous project regression checkpoint: 320 tests, 0 failures, 0 errors, 3 skipped; `BUILD SUCCESS`.

### 2026-08-16 - Physical Phase 1 real-source regression corpus
- Added `RealSourcePhysicalPhase1RegressionTest` over four project-supplied table-design documents.
- Covers `COUNTRIES`, `VOUCHER_TEMPLATE_HEADER_ROWS`, `CTSMSServiceDetails`, and `CTMSourcePermissionDetail` through the actual Word/legacy parser path and all four DDL dialects.
- Freezes Oracle active `TS_<SCHEMA>` / `ITS_<SCHEMA>` placement, Db2 `FOR MIXED DATA`, physical comment blocks, indexed VARCHAR review placeholder, and source-only default behavior.
- `SPACE_FREE_NAME` is intentionally not used as a regression contract.
- Legacy schema values remain explicit API inputs; no schema is inferred from the legacy document name.

### 2026-08-16 - Legacy revision-history default reconciliation
- Fixed the real `CTMSourcePermissionDetail` regression exposed by the Physical Phase 1 real-source corpus.
- Legacy default normalization now treats the explicit Persian numeric word `صفر` as SQL numeric literal `0`.
- Added conservative revision-history reconciliation: an already-extracted field-grid default is removed only when a later change-log entry explicitly says that the default was removed for that technical field.
- One-character technical-name typos in revision rows are tolerated only when the match to a real column is unique; this covers the source typo `ReuestAmnt` -> `RequestAmnt` without broad fuzzy matching.
- The resolver never creates a default from revision prose; it only removes stale grid defaults.
- Bumped the legacy parser version to `0.6.0` and the canonical snapshot parser version so cached snapshots are reparsed under the corrected semantics.

### 2026-08-16 - two-source corpus bulk validation preparation
- Added `CorpusInventoryIT` to inventory the new-format Word corpus and classify/version-check legacy JSON before long bulk runs.
- Added `schemaforge.word.parserMode=standard|legacy|auto`; `standard` keeps the new-format Word corpus isolated from legacy fallback.
- Extended `WordDirectoryMultiDatabaseGenerationIT` to Db2 for z/OS and Db2 offline validation.
- Extended `CanonicalJsonDirectoryToDdlIT` to Db2 for z/OS and Db2 offline validation.
- Documented the separate New Word and Legacy JSON bulk workflows in `docs/integration/CORPUS-BULK-VALIDATION.md`.

### 2026-08-16 - persisted JSON compatibility split
- Split canonical snapshot compatibility into contract compatibility (`snapshotVersion` + `modelVersion`) and strict Word-cache compatibility (contract + current `parserVersion`).
- Kept Word cache reuse strict; stale parser snapshots are still rejected by the normal cache path.
- Added `CanonicalSnapshotMapper.toDomainPersistedSource(...)` for a persisted JSON corpus that is itself the input source.
- Updated `CorpusInventoryIT` to report contract-compatible, cache-compatible, stale-parser, bulk-DDL-eligible and incompatible-contract counts separately.
- Updated `CanonicalJsonDirectoryToDdlIT` to accept contract-compatible persisted JSON while reporting stale parser provenance in the batch summary.
- Added regression coverage proving a stale-parser snapshot is not cache-compatible but remains mappable as a persisted JSON source.

## 2026-08-16 - Bulk canonical JSON mapping diagnostics
- Expanded `CanonicalJsonDirectoryToDdlIT` dialect-mapping diagnostics to Oracle, PostgreSQL and Db2/zOS.
- Db2/zOS lossless numeric blockers are now reported per table/column before generation (`DB2_NUMBER_PRECISION_REQUIRED`, `DB2_DECIMAL_PRECISION_UNSUPPORTED`) instead of only as a generic generator exception.
- Oracle now reports bounded NUMBER/TIMESTAMP precision and conservative LOB fallbacks used by the existing dialect renderer.
- PostgreSQL now reports explicit TIMESTAMP precision currently dropped by the existing mapper; this release does not change production mapping semantics.
- No production DDL generation behavior was changed by this diagnostics-only update.
- Fixed `CanonicalSnapshotMapperTest.distinguishesPersistedSourceCompatibilityFromWordCacheFreshness` to compare restored table semantics instead of `Table` object identity. No production behavior changed.


## 2026-08-16 - PostgreSQL temporal precision preservation
- Preserve explicit PostgreSQL TIMESTAMP precision when it is within the supported 0..6 range.
- Bound higher canonical TIMESTAMP precision to 6 explicitly instead of silently dropping the modifier.
- Bulk diagnostics now report only genuinely lossy PostgreSQL temporal precision mappings (`> 6`).
- Bulk JSON summary now distinguishes deliberate `GENERATION_BLOCKED_BY_MAPPING` from actual generation failures.

## 2026-08-16 - Physical Phase 1 corpus audit runner
- Added `PhysicalPhase1CorpusAuditIT` for a physical-only audit of persisted canonical JSON sources.
- The runner audits all four supported dialects without making datatype compatibility a failure gate.
- Canonical/renderer checks cover active source placement, Oracle `TS_<SCHEMA>` / `ITS_<SCHEMA>` defaults, activation-ready placement placeholders, table/index physical comment blocks, Db2 storage placeholders and conditional PADDED review markers, FK supporting-index recommendations, and the rule that CHECK/FK statements do not receive storage options.
- When full DDL is renderable, the runner additionally verifies block counts, active placement preservation, placeholder containment inside comments, and that Phase-1 recommendations have not become executable SQL.
- A datatype or other non-physical DDL exception is recorded as `PHYS-DDL-UNAVAILABLE-001` and does not count as a physical violation; the model/renderer audit still completes for that source.
- No production parser, canonical model, datatype mapper, dialect renderer, or DDL generation behavior was changed by this audit-only addition.

## 2026-08-16 - Physical Phase 1 Word corpus handoff
- Added `schemaforge.snapshot.parserMode` to `WordDirectoryToCanonicalJsonIT` with `auto`, `standard`, and `legacy` modes.
- `standard` mode disables legacy fallback so the new-format Word corpus is audited through the new Word parser only.
- `PhysicalPhase1CorpusAuditIT` now ignores `manifest.json`, allowing it to audit the snapshot directory produced by `WordDirectoryToCanonicalJsonIT` directly.
- No production parser, canonical model, datatype mapper, dialect, or DDL generation behavior changed.

## 2026-08-16 - Physical source-aware hardening 2
- Kept the Physical Phase-1 scope focused on production DDL physical options; no datatype/parser/REST changes.
- Oracle table compression is now source-aware for NOCOMPRESS, COMPRESS, ROW STORE COMPRESS [BASIC|ADVANCED]; basic compression uses Oracle's documented PCTFREE default of 0 when source PCTFREE is absent.
- Oracle index compression source values are syntax/context checked; invalid prefix/advanced compression remains visible as SOURCE PHYSICAL ISSUE instead of being silently accepted.
- Oracle PCTFREE/PCTUSED are cross-validated; conflicting source values are not silently adjusted and both become review placeholders.
- PostgreSQL B-tree `deduplicate_items` is honored only when explicitly supplied by source/profile; otherwise it remains a workload/index-method decision.
- SQL Server index options IGNORE_DUP_KEY, STATISTICS_NORECOMPUTE, ALLOW_ROW_LOCKS and ALLOW_PAGE_LOCKS are now source-aware; OPTIMIZE_FOR_SEQUENTIAL_KEY is source/profile-only.
- Db2/zOS PRIQTY and SECQTY source values are validated without normalization; invalid values remain visible as SOURCE PHYSICAL ISSUE and fall back to placeholders.
- Db2/zOS PADDED/NOT PADDED supplied for a key with no varying-length string column is surfaced as a source issue instead of being silently ignored.
- Physical corpus audit accepts valid Db2 source values in place of environment placeholders and records SOURCE PHYSICAL ISSUE lines as REVIEW findings.
- Oracle no longer infers a PCTFREE default when the source table-compression mode itself is unresolved; the physical block keeps both issues visible for DBA review.
- Oracle source PCTUSED is explicitly marked as MSSM-context dependent because ASSM ignores it.
- SQL Server `IGNORE_DUP_KEY=ON` is context-reviewed for independent indexes because Microsoft restricts ON to unique indexes; PK/UK backing indexes are treated as known-unique.
- Physical corpus audit now uses the comment-aware SQL statement parser, distinguishes source ISSUE from source CONTEXT REVIEW, and audits PK/UK blocks through `constraintIndexOptions(...)` rather than generic index rendering.

## 2026-08-16 - Physical Phase 1 finalization hardening 3
- Kept the change set restricted to production physical rendering and its existing regression/audit coverage; no parser, datatype mapping, REST contract or logical-schema changes.
- Db2/zOS table physical blocks are now storage-only: AUDIT, DATA CAPTURE, CCSID, VOLATILE, APPEND and RESTRICT ON DROP are no longer presented as activation-ready physical recommendations.
- Added source/profile-only Db2 `PIECESIZE` validation with explicit ISSUE/REVIEW behavior rather than guessed values.
- Added source/profile-only PostgreSQL `toast_tuple_target`; invalid minimum values are exposed as SOURCE PHYSICAL ISSUE and valid offline values remain block-size-dependent REVIEW items.
- Passed known UNIQUE-index context from DDL generation into Oracle and SQL Server physical renderers so context-sensitive validation does not guess uniqueness for standalone or explicit backing indexes.
- Documented the current table-scoped source physical-option granularity boundary; distinct per-index source tuning is not fabricated when the canonical source model cannot represent it.
- Physical corpus audit summaries now report rendered source-value ISSUE markers and context REVIEW markers explicitly, so a zero renderer violation count is not mistaken for source correctness.

## 2026-08-16 - Datatype compatibility Phase 1: source-visible mapping assessment
- Started the datatype workstream from the frozen Physical Phase-1 baseline; no physical renderer, parser, canonical-model, or REST behavior was changed.
- Added production `DatatypeCompatibilityAnalyzer` / `DatatypeCompatibilityAssessment` so dialect mapping risk is no longer confined to an integration-test diagnostic.
- Generated SQL now includes datatype compatibility findings in the existing validation header and inline column issue markers for deliberate bounded/fallback mappings.
- Oracle findings cover NUMBER precision/scale bounds, TIMESTAMP precision bounds, and conservative character/RAW large-object fallback already performed by the Oracle dialect.
- PostgreSQL findings cover only temporal precision above the supported 0..6 range; valid explicit precision remains preserved.
- SQL Server findings cover DECIMAL precision above 38, temporal precision above 7, and unbounded exact numeric source types; the existing `DECIMAL(38,0)` rendering is retained for this phase but is now explicitly review-visible instead of silently implying integer-only semantics.
- Db2 for z/OS findings remain blocking for exact numeric types with missing precision or precision above 31.
- Added the missing Db2 for z/OS TIMESTAMP hard limit: precision above 12 is now rejected instead of emitting invalid `TIMESTAMP(p)` DDL.
- Reused the production datatype analyzer from `CanonicalJsonDirectoryToDdlIT` so bulk classification and generated-SQL warnings share one rule source.
- No automatic repair was introduced for source values such as `NUMBER(70)`, `NUMBER(115,5)`, or unbounded `NUMBER`; the source condition remains visible for review.

### 2026-08-16 - Datatype compatibility visibility regression assertion fix
- Corrected three generator regression assertions to reflect the existing SQL column-comma placement before inline datatype issue comments.
- No production DDL, datatype mapping, physical rendering, parser, canonical model, or REST behavior changed.

### 2026-08-16 - Physical Phase 1 final freeze
- Revalidated the per-object physical-option boundary against the current production model and DDL generation path.
- Confirmed that production source ingestion does not currently carry distinct physical tuning values per standalone index / PK / UK; index physical candidates are intentionally rendered from the table-scoped source/profile option map plus object context (key columns, uniqueness, placement).
- No IndexPhysicalOptions / canonical-model expansion was introduced because there is no production source contract to populate it yet.
- Physical Phase 1 is frozen. Distinct per-index source tuning is deferred to a future Phase 1.1 only when an input contract can represent it without guessing or merging values.
- No production DDL behavior changed in this freeze step.

### 2026-08-16 - Final single-file DBA DDL delivery contract
- Re-audited the production `DdlGenerator` ordering for Oracle, PostgreSQL, Microsoft SQL Server, and Db2 for z/OS against the one-source/one-SQL-file DBA delivery requirement.
- Confirmed that validation findings, schema/bootstrap metadata, table DDL, constraints, indexes, physical-review comments, FK supporting-index recommendations, comments, grants, object summary, and generation footer are already present in the generated SQL artifact.
- Kept DBMS-specific ordering that is required for survivability/execution semantics (for example SQL Server descriptions before foreign-key dependencies) rather than introducing cosmetic section reshuffling.
- Documented that fatal datatype mappings are reported as `GENERATION_BLOCKED_BY_MAPPING` and do not publish guessed SQL.
- Corrected stale README wording that still described Oracle precision above 38 as bounded; the current production rule blocks unsupported exact-numeric precision instead of silently clamping it.
- No production Java code, physical rendering, parser, canonical model, REST contract, or mapping behavior changed in this final delivery-contract audit.

### 2026-08-17 - Legacy parser recovery evidence probe
- Added `LegacyWordFailureEvidenceIT`, a diagnostic-only integration test that re-runs the low-level legacy extractor for `PARSE_FAILED` entries from a prior manifest and writes raw row/type/length evidence to CSV.
- No production parser, canonical model, DDL generation, physical rendering, or datatype mapping behavior changed in this diagnostic step.
- Intended use: classify the remaining Recovery-1 failures without another full 58k-document run or unsafe type/length guessing.

### 2026-08-17 - Legacy parser recovery 2: explicit length-evidence hardening
- Analyzed the Recovery-1 evidence probe over the remaining 272 failed Word sources (232 unique content hashes): 151 unresolved datatype rows, 113 missing character lengths, 4 scale>precision sources, 2 non-positive precision sources, and 2 missing-table-name sources.
- Kept ambiguous logical `S`, blank/merged datatype cells, invalid numeric definitions, and missing table names unresolved; no datatype or length is invented for them.
- Added logical-source trust for standard Unicode/Oracle character types (`NVARCHAR`, `NVARCHAR2`, `NCHAR`, `VARCHAR2`, `NCLOB`, `RAW`) without changing the stricter DB2 physical-type whitelist.
- Extended the legacy datatype declaration recognizer to allow standard type names containing digits/underscores (for example `VARCHAR2` / `NVARCHAR2`).
- Added conservative character-length recovery only from explicit source evidence: logical length when a trusted physical character type resolves ambiguous `S`; an explicit physical-length cell when physical type is absent; inline declarations such as `VARCHAR(1000)` / `VC20`; a unique numeric cell immediately adjacent to a later character-type cell; and a one-cell-shifted unique numeric length after the logical type.
- Added normalization for isolated Word formatting wrappers such as `` `50`` and `\\20`; competing numeric groups such as `30 15` remain ambiguous.
- Every new length recovery path emits a dedicated `LEGACY_CHARACTER_LENGTH_*` provenance warning.
- Bumped the legacy parser version to `0.6.3` and canonical snapshot parser version to `word-pipeline-v4-2026-08-17-legacy-recovery2`.
- DDL Generation Core V4, datatype mapping rules, physical rendering, and canonical model semantics remain unchanged.

### 2026-08-17 - Oracle generic physical options Phase 2
- Added source/profile-aware Oracle table LOGGING/NOLOGGING, PARALLEL/NOPARALLEL[/degree], and SEGMENT CREATION DEFERRED/IMMEDIATE candidates to the existing DBA physical review block.
- Added object-scoped Oracle index LOGGING/NOLOGGING and PARALLEL/NOPARALLEL[/degree] with historical table-level fallback through the P0 physical-option model.
- Oracle LOGGING remains intentionally unspecified when absent because redo/recovery policy is not inferred from source documents; index logging is kept independent of table logging.
- Oracle NOPARALLEL is rendered as the documented default when no explicit parallel source/profile value exists.
- SEGMENT CREATION remains a review placeholder when absent so SchemaForge does not override the database/session DEFERRED_SEGMENT_CREATION policy; explicit DEFERRED/IMMEDIATE values are retained with restriction review for DEFERRED.
- Invalid logging, parallel, and segment-creation source values are surfaced as SOURCE PHYSICAL ISSUE markers and are never silently normalized.
- No Legacy Word parser, datatype mapping, REST contract, or non-Oracle physical behavior changed.

### 2026-08-17 - PostgreSQL physical options Phase 3
- Added source/profile-aware PostgreSQL table `parallel_workers`; absent values remain server-derived instead of being guessed.
- Added index access-method evidence (`btree`, `hash`, `gist`, `spgist`, `gin`, `brin`) to the DBA physical review block without changing executable CREATE INDEX syntax.
- Added GiST `buffering`, GIN `fastupdate` / `gin_pending_list_limit`, and BRIN `pages_per_range` / `autosummarize` storage candidates from explicit source/profile evidence only.
- Method-specific options are never silently applied to a conflicting explicit access method; conflicts are surfaced as `SOURCE PHYSICAL ISSUE` markers.
- Preserved the historical B-tree fillfactor/deduplicate behavior when the access method is absent, while avoiding a fabricated fillfactor default for explicit non-B-tree methods whose default varies or does not support fillfactor.
- Autovacuum and column STORAGE/COMPRESSION remain outside this phase as operational/column-level policy.
- No Legacy Word parser, datatype mapping, REST contract, Oracle, SQL Server, or Db2 physical behavior changed.

### 2026-08-17 - SQL Server physical options Phase 4
- Added source/profile-aware SQL Server table and index `XML_COMPRESSION` candidates; the option remains version-aware (SQL Server 2022+) and is never invented when absent.
- Added source/profile-aware `STATISTICS_INCREMENTAL` for indexes; absent values are not promoted to ON and unsupported partition/statistics contexts remain DBA-review concerns.
- Activated explicit SQL Server rowstore index organization when evidence already exists: canonical `IndexType.CLUSTERED/NONCLUSTERED` or object-scoped `SQLSERVER_INDEX_ORGANIZATION`. Unspecified indexes retain SQL Server's normal default behavior.
- Added backing-index organization support for PRIMARY KEY and UNIQUE constraints through the P0 object-scoped physical map without changing other dialects.
- Fixed SQL Server metadata reconstruction to retain `sys.indexes.type_desc` as `SQLSERVER_INDEX_ORGANIZATION` and the index data space as object-scoped `INDEX_TABLESPACE` instead of discarding both values.
- Conflicting/invalid organization evidence is surfaced in the DBA physical block rather than silently normalized.
- Build/deployment options (`ONLINE`, `RESUMABLE`, `MAX_DURATION`, `MAXDOP`, `SORT_IN_TEMPDB`) remain outside this physical-state phase.
- No Legacy Word parser, datatype mapping, REST contract, Oracle, PostgreSQL, or Db2 physical behavior changed.

### SQL Server Physical P4 R1
- Preserved the legacy single-line table physical clause `WITH (DATA_COMPRESSION = ...)` when `XML_COMPRESSION` is absent.
- Multi-line table physical options are used only when explicit XML compression evidence is present.
- No SQL Server physical semantics changed; this is a backward-compatible rendering fix for existing DDL/golden regressions.

### SQL Server Physical P4 R2
- Preserved legacy `UNIQUE(columns)` rendering when a unique constraint has no explicit SQL Server index organization.
- Emit `UNIQUE CLUSTERED(columns)` / `UNIQUE NONCLUSTERED(columns)` only when organization evidence is explicit.
- No parser, canonical datatype, or non-SQL Server behavior changed.

### 2026-08-17 - PostgreSQL Column Physical P6 R1
- Fixed only the backward-compatibility test fixture: an INTEGER snapshot incorrectly used BYTE length semantics with no length.
- The fixture now uses DEFAULT length semantics while keeping column physicalOptions null, which is the actual P6 compatibility condition under test.
- No production code, parser, snapshot schema, or DDL behavior changed.

## 2026-08-17 - Db2/zOS Index Build Options P7
- Added explicit Db2/zOS CREATE INDEX `DEFINE YES|NO` and `DEFER YES|NO` support through `Index.buildOptions`.
- `DEFINE NO` is emitted only when explicit index STOGROUP evidence exists; otherwise an INDEX BUILD ISSUE is rendered and the directive is omitted.
- `DEFER YES` renders an INDEX BUILD REVIEW warning about rebuild-pending behavior for populated tables.
- Updated the Db2 physical comment block to keep COPY/CLUSTER outside build-option handling.
- Added `docs/PHYSICAL-FINAL-GAP-AUDIT.md` to record remaining vendor-specific gaps and scope decisions.

## 2026-08-17 - Physical DDL P0-P7 baseline freeze
- Froze the Physical DDL workstream on the green Db2/zOS Index Build P7 baseline.
- Recorded the user-verified full Maven result: 376 tests, 0 failures, 0 errors, 3 skipped, BUILD SUCCESS.
- Added `docs/PHYSICAL-BASELINE-FREEZE.md` and `BASELINE-MANIFEST.txt` as the handoff contract for subsequent SchemaForge V4 work.
- Updated the final gap audit and coverage matrix to reflect P0 object-scoped physical options, P5 separate index build options, P6 column physical options, and P7 Db2 DEFINE/DEFER.
- Corrected stale README wording that still described per-index physical tuning as an unimplemented model boundary.
- Remaining Oracle LOB, PostgreSQL access-method/partition, SQL Server TEXTIMAGE/FILESTREAM/partition, and Db2 recovery/organization/partition features stay explicitly deferred until a dedicated source/domain model exists.
- Documentation-only finalization: no production Java, test logic, parser version, snapshot version, datatype mapping, or DDL behavior changed.

## 2026-08-17 - P8-D Physical Metadata Comparison baseline freeze
- Froze the expected-vs-actual Physical Metadata Comparison workstream after the user-verified P8-C full regression: 399 tests, 0 failures, 0 errors, 3 skipped, BUILD SUCCESS.
- Recorded final P8 coverage: table physical comparison for Oracle/PostgreSQL/SQL Server/Db2 z/OS; index/PK/UK physical comparison for all four platforms; PostgreSQL column STORAGE/COMPRESSION comparison.
- Frozen Excel physical sheets are `TABLE_PHYSICAL_COMPARE`, `INDEX_PHYSICAL_COMPARE`, and `COLUMN_PHYSICAL_COMPARE`.
- Reaffirmed that JDBC actual database metadata is comparison evidence only and is never promoted into design intent or generated DDL.
- Reaffirmed conservative reverse-engineering guards: no buildOptions inference, no Oracle segment-creation inference, no Db2 allocation-to-PRIQTY/SECQTY reconstruction, and mixed partition state remains REVIEW.
- Updated the physical coverage matrix and README to distinguish the 376-test P0-P7 DDL freeze from the 399-test P8 physical-comparison freeze.
- Documentation/baseline finalization only: no production Java, test code, parser, snapshot, datatype mapping, dialect, physical renderer, API, or DDL generation changes.

## 2026-08-17 - Documentation Finalization
- Added `docs/reference/` as the authoritative current SchemaForge V4 documentation set.
- Consolidated architecture, canonical domain model, supported inputs/outputs, four-database support matrix, Physical DDL, P8 physical metadata comparison, Excel workbook behavior, no-guess/evidence policy, known limitations, developer guidance, testing, and current release-baseline documentation.
- Updated `docs/README.md` and the root README so current documentation is clearly separated from historical phase/release evidence.
- Marked the older `docs/release/V4-FINAL-BASELINE.md` as a historical 270-test milestone to prevent confusion with the current 399-test baseline.
- Updated `BASELINE-MANIFEST.txt` and added `DOCUMENTATION-MANIFEST.txt` for the current documented baseline `SCHEMAFORGE-V4-DOCFINAL-20260817`.
- Recorded the latest user-verified regression: 399 tests, 0 failures, 0 errors, 3 skipped, BUILD SUCCESS, finished 2026-08-17T08:40:09-07:00.
- Documentation-only finalization: no production Java, test Java, parser, snapshot, datatype, dialect, physical renderer, API, or DDL behavior changed.

## 2026-08-17 - EA Excel identity equivalence and REST graph parity

- Treat an EA logical identity column as equivalent to Oracle's persisted sequence-backed default only when the database default is the deterministic SchemaForge sequence for that exact table/column (`SEQ_<TABLE>[ _<COLUMN>].NEXTVAL`).
- Suppress both `IDENTITY_MODE` and `DATA_DEFAULT` false-positive differences for that exact equivalence; arbitrary sequences remain mismatches.
- Add Mermaid and Graphviz artifacts to EA XML generation so all create-table REST generation paths include graph outputs.
- Add Mermaid/Graphviz paths to the EA output manifest.
- No parser, canonical-domain, DDL-renderer, physical-option, or metadata-comparison semantics were changed.

## 2026-08-17 - EA tables without primary keys

- REST create-table workflows now treat a document table without a primary key as a valid DDL case.
- Metadata CRUD generation is skipped with `SKIPPED_NO_PRIMARY_KEY` instead of falling through to generator failure.
- The same explicit skip is used when the live metadata table exists but itself has no primary key.
- DDL, comparison output, Mermaid, and Graphviz generation remain unaffected.
- No primary key is inferred from `*_ID` naming or identity/sequence behavior.

## 2026-08-17 - R3 compile fix
- Fixed missing `QualifiedName` import in `SchemaCompareExcelWriter`.
- No production behavior change; Identity comparison and no-PK CRUD skip logic are unchanged.
## 2026-08-18 - Conceptual ERD Phase 1

- Added `CONCEPTUAL_ERD` as a third diagram view; existing `ER` and `DEPENDENCY` outputs are unchanged.
- Added field-free Mermaid and Graphviz conceptual ERD artifacts beside normal create-table REST outputs and in ZIP batch diagrams.
- Added a shared evidence-based cardinality resolver: FK nullability determines 0..1 vs 1 parent participation; an exact PK/UK match on the FK columns determines 0..1 vs 0..N children.
- Parent-to-child minimum remains zero because relational constraints alone cannot prove that every parent must have a child.
- No relationship, relationship verb, strong/weak entity type, or associative collapse is inferred from naming.
- EA manifests now include `conceptualErdMermaid` and `conceptualErdGraphviz`.
- No parser, canonical snapshot version, datatype mapping, DDL dialect, physical metadata, Excel comparison, or CRUD semantics changed.
- Adds four focused tests over the user-verified 402-test baseline; expected full-suite total is 406.


## 2026-08-18 - Canonical JSON full artifact audit runner

- Added explicit `CanonicalJsonDirectoryAllArtifactsIT` for corpus-wide validation from persisted `*.schema.json` snapshots without reopening Word documents.
- Generates DDL for every currently registered database platform plus API-style JSON, Mermaid ER/dependency/conceptual-ERD, Graphviz ER/dependency/conceptual-ERD, and corpus batch diagrams.
- Reuses dialect mapping analysis and existing offline SQL validators and writes artifact-index, validation-issue, and failure reports for manual review.
- Does not fabricate comparison Excel/P8/metadata-CRUD artifacts when no Actual database metadata repository exists; these database-dependent omissions are explicitly reported.
- Test-only audit tooling; no production Java, parser, snapshot version, canonical model, DDL renderer, metadata comparison, or REST behavior changed.

## 2026-08-20 - MySQL P2-R7 strong table reconciliation

- Added `MySqlStrongTableReconciliationGenerationIT`.
- Revalidates only P2-R6 `STRONG_SAME_SCHEMA_*` table candidates with an independent DB2 datatype-family corroboration gate.
- Rejects cross-schema, ambiguous, weak, and type-conflicting candidates; canonical JSON is never mutated.
- Applies evidence-backed mapped-table numeric metadata only in memory and generates only newly unblocked SQL under `generated-new`.
- Moved MySQL patch application notes out of the project root into `docs/patches/mysql/`.


### MySQL P2-R10-R1 - historical snapshot loader hardening
- Restricts the P2-R10 historical corroboration audit to canonical `*.schema.json` files.
- Skips malformed/non-canonical snapshot artifacts instead of aborting the audit with `snapshot schema` NPE.
- No production mapping or canonical JSON mutation.
## 2026-08-21 - ALTER / Flyway Migration M2

- Extended live-to-document migration diff from columns to table-owned PK, FK, UK, CHECK and standalone INDEX objects.
- Added deterministic structural ADD/DROP/REPLACE changes with object-specific SAFE/REVIEW/DESTRUCTIVE risk classification.
- Migration renderer now orders owned-object DROP/REPLACE before column changes and ADD/REPLACE after column changes.
- Destructive constraint drops and both sides of destructive replacements remain commented unless explicitly confirmed.
- Reused the validated DDL generator for structural ADD syntax so Oracle, PostgreSQL, Db2/zOS, SQL Server and MySQL migration syntax stays aligned with CREATE DDL.
- Expanded `JdbcMySqlMetadataRepository` to read primary/unique constraints, foreign keys, checks and standalone indexes from `information_schema`.
- MySQL catalog primary-key name `PRIMARY` is structurally equivalent to a named canonical PK and no longer causes false replacement.
- CREATE DDL remains unconditional and independent; Flyway migration remains an additional artifact only when a live table differs.
- Incoming foreign keys owned by other tables and physical-option migration are deliberately not auto-applied in M2.
## 2026-08-21 - ALTER / Flyway Migration M2-R3 MySQL CHECK metadata equivalence

- Fixed the real MySQL M2 pilot residual false-positive for CHECK constraints after successful ALTER execution.
- MySQL CHECK comparison now ignores information_schema catalog decoration that is not logical drift: identifier backticks and automatic `_utf8mb4`/`_utf8mb3` string-literal introducers.
- Added a focused regression test proving `STATUS IN ('A','I','S')` is equivalent to MySQL metadata form ``(`STATUS` in (_utf8mb4'A',_utf8mb4'I',_utf8mb4'S'))``.
- The live-pilot password read from `MYSQL_JDBC_PASSWORD` is trimmed to avoid cmd.exe trailing-space surprises.
- CREATE generation, destructive-confirmation policy, structural migration ordering, and all non-MySQL dialect behavior are unchanged.

## 2026-08-22 - ALTER / Flyway Migration M2-R4 MySQL CHECK formatting normalization

- Hardened MySQL CHECK equivalence after the R3 live pilot still reported the same CHECK as a residual replacement.
- Added quote-aware normalization of insignificant whitespace around CHECK commas and parentheses while preserving literal contents such as `'A, B'` exactly.
- Kept catalog-only normalization narrow: identifier backticks and automatic `_utf8mb4`/`_utf8mb3` introducers are still ignored only for MySQL CHECK comparison.
- Added focused regressions for punctuation whitespace and for preserving commas/spaces inside quoted literals.
- Live-pilot residual diagnostics now include the raw live and desired CHECK expressions if a CHECK replacement remains.
- No CREATE-DDL behavior, destructive-confirmation policy, non-MySQL dialect logic, canonical JSON, or structural migration ordering changed.

## 2026-08-22 - ALTER / Flyway Migration M2-R5 MySQL CHECK escaped-literal normalization

- Fixed the real MySQL 8.4 live-pilot CHECK residual where `information_schema.check_constraints.CHECK_CLAUSE` returned charset-prefixed string delimiters as `_utf8mb4\'A\'`.
- MySQL CHECK comparison now reconstructs only charset-prefixed catalog escaped literals before existing quote-aware formatting normalization.
- Internal apostrophes remain semantically preserved by converting catalog `\'` escapes inside a literal to SQL-standard doubled apostrophes for comparison.
- Added regressions for the exact live-pilot metadata form and for an `O'Reilly` literal safety case.
- CREATE generation, ALTER SQL, destructive-confirmation policy, canonical JSON, and all non-MySQL dialect behavior are unchanged.

## 2026-08-22 - ALTER / Flyway Migration M2-R6 Oracle live-pilot readiness

- Added `OracleMigrationM2LivePilotIT` to execute a real column + PK/FK/UK/CHECK/INDEX migration against a disposable/test Oracle schema user.
- The Oracle pilot proves CREATE generation remains unconditional, executes a confirmed migration, preserves seed data, and requires an empty post-migration metadata diff.
- `JdbcOracleMetadataRepository` no longer double-counts PK/UK enforcing indexes as standalone indexes; those backing-index physical values remain attached to their constraint objects.
- The pilot refuses `SYS`/`SYSTEM`, requires the configured schema to equal the connected user, and cleans up only the fixed `SF_M2_PARENT`/`SF_M2_CHILD` tables.
- MySQL M2-R5 behavior and all CREATE-DDL semantics remain unchanged.

## 2026-08-22 - ALTER/Migration M2-R7 PostgreSQL live pilot

- Added `PostgreSqlMigrationM2LivePilotIT` for real live-to-document M2 execution validation.
- The pilot verifies CREATE generation remains independent when the table already exists, executes confirmed Flyway-style ALTER SQL, re-reads PostgreSQL catalog metadata, requires zero residual diff, checks row preservation, and cleans up its fixed `SF_M2_*` tables.
- PostgreSQL metadata now excludes primary-key and unique-constraint backing indexes from the standalone index collection so M2 does not emit false INDEX drift for catalog-owned enforcement indexes.

## 2026-08-22 - ALTER/Migration M2-R7.1 PostgreSQL live-pilot CREATE assertion fix

- Fixed the PostgreSQL live-pilot CREATE verification to compare both the generated SQL and the expected `CREATE TABLE` marker in the same lowercase form.
- The previous assertion lowercased only the generated SQL while leaving the `CREATE TABLE` prefix uppercase, causing a false failure before any migration execution.
- No production CREATE/ALTER generation, PostgreSQL metadata logic, destructive-confirmation policy, or migration SQL changed.

## 2026-08-22 - ALTER/Migration M2-R10 SQL Server ALTER COLUMN dependency refresh

- Fixed the real SQL Server M2 live-pilot failure where an unchanged standalone index on `PARENT_ID` blocked `ALTER COLUMN`.
- SQL Server migration rendering now detects unchanged table-owned PK/UK/FK/CHECK/INDEX objects that depend on columns whose datatype or nullability is being changed, temporarily drops them before `ALTER COLUMN`, and recreates the desired definitions afterward.
- Semantic diff remains unchanged: temporary dependency refreshes are operational migration steps and are not reported as design drift.
- Structural DROP ordering is dependency-aware (`FK -> INDEX -> CHECK -> UK -> PK`) and ADD ordering is the safe reverse dependency direction (`PK -> UK -> CHECK -> INDEX -> FK`).
- SAFE rendering now comments an SQL Server `ALTER COLUMN` when its prerequisite dependency DROP is blocked, preventing a knowingly non-executable half-migration.
- Incoming foreign keys owned by other tables remain explicitly outside automatic per-table migration scope.
- Added a focused regression for an unchanged SQL Server index that must be dropped/recreated around a nullability change.

## 2026-08-22 - ALTER/Migration M2-R12 SQL Server CHECK catalog normalization

- Fixed the SQL Server M2 live-pilot residual drift where `sys.check_constraints.definition` returned catalog formatting such as `([ID]>(0) AND [PARENT_ID]>(0))` for the authored `(ID > 0) AND (PARENT_ID > 0)` expression.
- SQL Server CHECK comparison now ignores only catalog-only bracket quoting of ordinary identifiers, numeric scalar parentheses, redundant atomic predicate parentheses, and whitespace adjacent to operators.
- Boolean grouping that changes precedence and string-literal contents/case remain significant, so semantic CHECK changes are still reported.
- CREATE SQL, migration SQL, SQL Server dependency refresh, destructive-confirmation policy, and the other four database dialects are unchanged.

## 2026-08-25 - R7.2.1 recovered-corpus SAFE acceptance baseline

- Froze the first evidence-backed recovered SAFE corpus baseline across Oracle, PostgreSQL, Db2/zOS, and SQL Server.
- The 5,321-snapshot recovered corpus produced 5,296 successful Oracle scripts, 5,321 PostgreSQL scripts, 4,693 Db2/zOS scripts, and 4,703 SQL Server scripts with zero snapshot failures, canonical errors, or generation failures.
- Evidence-backed canonical recovery newly unblocked exactly 880 Db2/zOS snapshots and 880 SQL Server snapshots versus the raw corpus.
- Added optional regression-aware acceptance mode to `CanonicalJsonDirectoryToDdlIT`; known No-Guess hard blockers are allowed only within frozen per-platform ceilings and only for explicitly allowed mapping codes.
- The regression gate fails on lower successful-script counts, higher blocker counts, warning regressions, generated SQL validation errors, generation/snapshot/canonical failures, unknown blocking codes, or broken corpus accounting.
- No production Java, dialect mapping, canonical model, parser, DDL renderer, or source canonical snapshot is changed by this gate hardening.

## 2026-08-26 - R7.3.3 New Word strict corpus regression gate

- Verified the 660-document standard Word corpus has identical SAFE and OPTIMIZED coverage after R7.3.2.
- Added optional `schemaforge.word.failOnRegression=true` exact acceptance mode to `WordDirectoryMultiDatabaseGenerationIT`.
- Strict mode freezes document/skip/parse counts, per-platform generated/with-issues/failed counts, corpus accounting, and exact structured failure-code counts.
- Added numeric mapping strategy to the Word multi-database text/console summary for explicit SAFE vs OPTIMIZED evidence.
- No production parser or DDL semantics changed in R7.3.3.

## R7.10 - DB2 LUW P6 FK Structural Audit
- Added offline `Db2LuwForeignKeyStructuralAuditTest` for post-P5 classification.
- Classifies FK blockers/skips as version drift, naming/alias drift, missing generated dependency, or canonical/key evidence gaps.
- Recognizes historical PK/UNIQUE evidence and unique-index evidence without auto-promoting indexes to constraints.
- P6 is read-only: no DB2 connection and no DDL/canonical mutation.

## 2026-08-29 - DB2 LUW P7.2 parallel Legacy Unique-Key probe
- Parallelized `LegacyUniqueKeyRecoveryProbeIT` with a bounded fixed worker pool; the prior P7.1 probe parsed the corpus serially.
- Added `schemaforge.uk.probe.threads` with a default of `min(8, availableProcessors)` so concurrency is explicit and memory remains bounded for DOC/DOCX parsing.
- Added `schemaforge.uk.probe.progressEveryDocuments` (default `250`) and concurrent progress reporting.
- Result aggregation and CSV ordering remain deterministic; worker tasks return immutable per-document results and the main thread owns report aggregation.
- No production parser, canonical model, DDL generator, or DB2 LUW rendering semantics changed.

## 2026-08-29 - EA naming / migration convergence final merge on 1017 baseline

- Merged the six-DBMS physical object naming policy onto `schema-forge-v4-2026-08-29-1017` without replacing the newer P7 Word-parser work.
- Preserves logical EA object spelling, including repeated underscores such as `IX_PATTERN_OPERATION__2`; removed underscore collapsing from EA identifier sanitization.
- Added deterministic hash-based shortening for generated/supporting objects when a target DBMS identifier limit is exceeded: PostgreSQL 63, MySQL 64, Oracle/SQL Server/Db2 LUW/Db2 z/OS 128. Plain truncation is not used.
- Added target-aware namespace/collision auditing and fail-closed validation for overlength business schema/table/column identifiers.
- Unified DDL rendering with the physical naming policy for PK/UK/FK/CHECK/INDEX/SEQUENCE objects and Oracle PK backing-index naming.
- Added `CHECK-COL-001`: CHECK constraints referencing columns absent from the owning table are retained as diagnostics but not emitted as executable SQL.
- Migration comparison now treats sequence-backed Oracle logical identity, `TIMESTAMP` vs `TIMESTAMP(6)`, PK/UK-covered redundant indexes, and logical-vs-shortened physical object names as equivalent.
- `primaryKeyAsIdentity` no longer infers identity for PK columns that also participate in an FK. This keeps shared-PK extensions such as `PDL.LOAN_ELIGIBILITY_EXTENSION.ELIGIBILITY_RULE_ID` non-identity.
- Verification against `Final_4(2).xml`: 50 tables, 49 inferred identities, shared PK/FK identity=false, 48 Oracle CREATE INDEX names with zero duplicates, repeated-underscore index names preserved, invalid collateral CHECKs blocked, and the focused Oracle migration convergence plan is empty.
- Root remains free of `NOTES*` files; naming documentation is under `docs/architecture/`.

## 2026-08-29 - EA Migration Convergence R3
- Fixed comparison-side index equivalence to be asymmetric: redundant desired indexes covered by PK/UK are suppressed, while extra database indexes remain visible as DROP candidates.
- Restored explicit index physical-property comparison even when the modeled index is structurally redundant with a PK/UK, so properties such as SQL Server FILLFACTOR remain comparable.
- Kept R2 migration behavior unchanged: NULL default equivalence, Oracle name-only RENAME rendering, and rename-before-add Flyway ordering.
