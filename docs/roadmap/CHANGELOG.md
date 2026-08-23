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
