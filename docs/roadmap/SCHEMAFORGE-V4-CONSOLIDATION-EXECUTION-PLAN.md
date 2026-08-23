# SchemaForge V4 - Consolidation Execution Plan

**Track:** V4 Consolidation and Baseline Hardening
**Baseline at plan creation:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C1`
**Plan status:** ACTIVE / AUTHORITATIVE FOR NEXT-STAGE EXECUTION
**Source fingerprint at plan creation:** `77e038a4acb5631d4a407174d9e075cc3d773d21b96a7e884410d9fbdc00525c`

## 1. Purpose

This document is the execution control point for the remaining SchemaForge V4 consolidation work. It exists to prevent stage order, scope, design decisions, and completion criteria from being lost across chat sessions or implementation iterations.

The current source baseline remains governed by `docs/reference/CURRENT-RELEASE-BASELINE.md`. This roadmap governs **what is done next and in what order**.

## 2. Mandatory stage-start protocol

Before any stage in this plan starts, the stage must first be described using the following checklist. No implementation change should begin before this pre-stage description is given.

| Item | Required stage-start content |
|---|---|
| Objective | Exact problem the stage will solve |
| Current state | Current source/runtime behavior relevant to the stage |
| Work list | Explicit list of tasks to be performed |
| Files/classes | Expected files, packages, or classes to be changed |
| Change type | `FIX`, `CHANGE`, `REFACTOR`, `TEST`, `DOC`, or combination |
| Behavioral impact | Whether runtime/API/artifact behavior will change |
| DBMS impact | Oracle / PostgreSQL / Db2 for z/OS / SQL Server / MySQL impact |
| Test plan | Unit, integration, regression, live, or compatibility tests required |
| Risks | Main regression/compatibility risks |
| Exit criteria | Conditions that must be true before the stage is marked complete |
| Deliverables | Code, tests, documents, artifacts, or baseline evidence produced |

If investigation during a stage discovers a materially new gap, that gap must be described before it is implemented. It must either be added to the current stage explicitly or recorded as a new later stage.

## 3. Change classification

Every implementation item should be classified as one of the following:

- **FIX** - correction of a verified defect in existing intended behavior.
- **CHANGE** - deliberate change to an API, artifact, naming, layout, or behavioral contract.
- **REFACTOR** - internal restructuring with no intended external behavior change.
- **TEST** - regression, compatibility, integration, or live-validation coverage.
- **DOC** - documentation/baseline alignment only.

## 4. Completed consolidation foundation

The following consolidation foundation work was completed and frozen at the historical C1 checkpoint. The current project baseline is C5.3; C1 remains the foundation checkpoint for these rows.

| ID | Stage | Status | Evidence / result |
|---|---|---|---|
| C0 | Freeze pre-consolidation source for review | DONE | 2026-08-22 source selected as consolidation input |
| C1 | Current capability inventory | DONE | Canonical, parser, DDL, metadata, migration, CRUD, diagram and REST capabilities inventoried |
| C2 | Five-DBMS capability matrix | DONE | Oracle, PostgreSQL, Db2/zOS, SQL Server and MySQL compared by implemented capability |
| C3 | REST/API inventory | DONE | 7 current REST endpoints identified and reference documentation aligned |
| C3.1 | MySQL GRANT parity fix | DONE | `DialectFeature.GRANT` enabled and regression-covered |
| C3.2 | Capability-model cleanup | DONE | Unused `DatabaseCapability` removed; `DialectFeature` remains the active contract |
| C3.3 | MySQL physical-comparison guard | DONE | MySQL physical sheets suppressed until a real physical contract exists |
| C3.4 | Documentation alignment | DONE | Current baseline, DBMS matrix, API and OpenAPI descriptions aligned |
| C3.5 | C1 official freeze | DONE / REVERIFIED | `467` tests, `0` failures, `0` errors, `4` environment-gated skips; BUILD SUCCESS; reverified 2026-08-22T07:28:27-07:00 |

The foundation checkpoint for C0-C3.5 is:

```text
SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C1
```

The current official project baseline is tracked in `docs/reference/CURRENT-RELEASE-BASELINE.md`; candidate/repair/freeze history is tracked in `CONSOLIDATION-VERSION-HISTORY.md`.

Official source inventory note: the corrected baseline contains **242** files under `src/main/java`. The previously observed compiler count of 243 was traced to a stale, unused `DatabaseCapability.java` in an intermediate workspace and is not part of the frozen source.

No later stage should silently alter the semantic behavior of the frozen parser, canonical model, DDL mapping, metadata recovery, or migration safety logic unless that change is explicitly declared in the relevant stage.

## 5. Remaining execution stages

### C4 - Artifact Contract V1

**Status:** DONE / USER-VERIFIED - ARTIFACT CONTRACT V1 COMPLETE
**Primary objective:** Define one explicit SchemaForge artifact model that all generation paths can use.

Progress evidence:

- `C4.1 - Artifact Inventory`: **DONE**; source-derived inventory recorded in `docs/architecture/SCHEMAFORGE-ARTIFACT-INVENTORY.md`.
- `C4.2 - Common artifact attributes / contract model`: **DONE / USER-VERIFIED**; core model recorded in `docs/architecture/ARTIFACT-CONTRACT.md`; targeted 8/8 and full 475-test regression are green.
- `C4.3 - Pipeline mapping to Artifact Contract`: **DONE / USER-VERIFIED**; request-local generation context/ledger tracks all current REST artifact paths without layout or filename changes; targeted 23/23 and full 482-test regression are green.
- `C4.2 official source fingerprint`: `8b76049ff698850bfd79cd497c309ea61bda607db719d33bb93b9c3f6721ad75`.
- `C4.3 official source fingerprint`: `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`; source inventory `251` main Java / `170` test Java; full regression `482/0/0/4` green.

Planned work:

1. inventory exact artifacts emitted by Word, Legacy Word, ZIP batch, EA, CRUD, and Mermaid endpoints; **DONE (C4.1)**
2. define common artifact concepts such as type, platform, logical identity, media type, relative path, generation ID, status, and provenance; **IMPLEMENTED (C4.2)**
3. define `Artifact Contract V1` and contract-version semantics; **IMPLEMENTED (C4.2)**
4. separate artifact production from HTTP/ZIP packaging concerns; **DONE (C4.3): descriptor tracking is request-local and transport remains unchanged**
5. add contract-level tests before changing existing layouts; **DONE (C4.2 + C4.3); archive-to-ledger mapping and standalone descriptor tests are regression-green**

Expected change type: `NEW / REFACTOR / TEST / DOC`.

Explicit non-goals:

- no parser algorithm changes;
- no canonical-domain semantic changes;
- no datatype-mapping changes;
- no migration-diff semantic changes;
- no MySQL physical-contract implementation.

Exit criteria:

- Artifact Contract V1 is documented;
- common artifact metadata model is covered by tests;
- all existing artifact families can be represented without loss of information;
- existing generation semantics remain regression-green.

---

### C5 - Artifact Naming and Layout Consolidation

**Status:** DONE / USER-VERIFIED - C5.1 + C5.2 + C5.3-R1 COMPLETE
**Primary objective:** Replace endpoint-specific naming and directory conventions with one controlled policy.

Planned work:

1. extend the central naming policy beyond SQL artifacts;
2. define stable paths for DDL, migration, CRUD, comparison, canonical JSON, Mermaid, Graphviz, reports, and validation artifacts;
3. remove manual filename construction where the common policy applies;
4. define collision handling and one-generation timestamp/generation-ID policy;
5. add compatibility tests for Word, Legacy Word, ZIP and EA generation.

Expected change type: `CHANGE / REFACTOR / TEST / DOC`.

Key risk: changing consumer-visible ZIP paths. Compatibility must therefore be explicit and tested.

---

### C6 - Standard Artifact Manifest

**Status:** C6.1 DESIGN DONE / C6.2 IMPLEMENTATION WAITS FOR C5.3-R2 REGRESSION
**Primary objective:** Give every generated package an authoritative machine-readable inventory.

Planned work:

1. define `manifest.json` schema/version;
2. record generation ID, source/provenance, schema/table identity, artifact list, DBMS, media type, status and relative path;
3. record validation/recovery state without duplicating entire reports;
4. optionally include artifact checksums when appropriate;
5. make Word, Legacy Word, ZIP batch and EA use the same manifest contract.

C6.1 fixed design decisions are documented in
`docs/architecture/ARTIFACT-MANIFEST-C6.1.md`. They include the
`schemaforge-manifest/v1` JSON shape, request timestamps, source/model identity, validation counts,
Artifact Contract outcome serialization, SHA-256/size integrity, manifest self-entry behavior,
deterministic ordering, EA legacy-manifest migration, and the C6.2 test plan.

Expected change type: `NEW / CHANGE / TEST / DOC`.

Exit criterion: a consumer can determine exactly what was generated without inferring package contents from filenames.

---

### C7 - REST Response and Error Contract

**Status:** PENDING / DEPENDS ON C4-C6
**Primary objective:** Make HTTP-level behavior consistent while preserving generation semantics.

Planned work:

1. define a shared REST error model;
2. introduce central exception mapping (`@RestControllerAdvice` or equivalent);
3. normalize error code, HTTP status, message, request/generation identifier, timestamp, and details;
4. review response headers, media types and download filenames;
5. preserve endpoint-specific payload type only where the use case requires it.

Expected change type: `CHANGE / REFACTOR / TEST / DOC`.

Explicit non-goal: redesigning the business behavior of DDL/CRUD/migration generation.

---

### C8 - API/Application Service Decomposition

**Status:** PENDING / DEPENDS ON C4-C7
**Primary objective:** Decompose the current large API service after contracts are stable.

Candidate decomposition:

```text
DocumentGenerationOrchestrator
EaGenerationOrchestrator
BatchGenerationOrchestrator
ArtifactGenerationService
ArtifactPackageBuilder
ArtifactManifestWriter
ComparisonArtifactProducer
MigrationArtifactProducer
DiagramArtifactProducer
CrudArtifactProducer
```

Planned work:

1. identify orchestration boundaries from the now-stable artifact contract;
2. extract responsibilities in small regression-safe steps;
3. keep controllers thin;
4. preserve endpoint behavior and artifact semantics;
5. add focused unit tests around extracted services.

Expected change type: `REFACTOR / TEST`.

Key rule: do not refactor mature parser/recovery internals merely because they are large.

---

### C9 - Test Matrix and Live-Validation Classification

**Status:** PENDING
**Primary objective:** Separate source coverage, standard regression, opt-in integration tests, and actual live DB evidence.

Planned work:

1. create an authoritative test/capability matrix;
2. classify tests as unit, offline integration, directory execution, opt-in `*IT`, and live DB pilots;
3. distinguish `LIVE_TEST_AVAILABLE` from `LIVE_TEST_EXECUTED_AND_PASSED`;
4. record DBMS/environment prerequisites;
5. define which test sets are required for normal changes, DBMS-specific changes, and release freeze.

Expected change type: `TEST / DOC`.

Current checkpoint evidence:

```text
Current official baseline: SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C5.3
Current standard clean regression: 492 tests
Failures: 0
Errors: 0
Skipped: 4 configuration-gated directory tests
Current C5.3-R1 verification: 2026-08-22T23:33:56-07:00
Targeted C5.3 regression: 50 tests at 2026-08-22T23:19:22-07:00
C5.3-R1 repair verification: 1 test at 2026-08-22T23:31:05-07:00
Previous C4.3 verification: 482 tests at 2026-08-22T22:53:13-07:00
Previous C4.2 verification: 475 tests at 2026-08-22T21:39:20-07:00
Result: BUILD SUCCESS
```

Candidate, repair, and freeze-level traceability is maintained in `CONSOLIDATION-VERSION-HISTORY.md`.

---

### C10 - Documentation Consolidation

**Status:** PARTIALLY DONE / FINAL PASS PENDING
**Primary objective:** Keep one current reference set and preserve older phase documents strictly as historical evidence.

Planned work:

1. align current reference documents to the final artifact and REST contracts;
2. ensure five-DBMS terminology is used where appropriate;
3. preserve four-DBMS wording only where the scope is intentionally physical-contract limited;
4. link the roadmap, test matrix, artifact contract, and current baseline from authoritative entry points;
5. avoid rewriting historical phase/freeze evidence unless it is factually corrupt.

Expected change type: `DOC`.

---

### C11 - Final Consolidation Regression and Baseline Freeze

**Status:** PENDING / FINAL STAGE
**Primary objective:** Freeze a new post-contract SchemaForge V4 baseline.

Planned work:

1. run targeted regressions for every contract/refactor stage;
2. run `mvnw.cmd clean test` on the exact final source;
3. run any required DBMS-specific/live tests defined by C9;
4. record tests/failures/errors/skips and environment evidence;
5. compute and record final `src` fingerprint;
6. package and freeze the new official V4 baseline;
7. update current reference documentation and changelog.

Expected change type: `TEST / DOC / BASELINE`.

No new feature should be introduced during this stage.

## 6. Deferred feature backlog outside this consolidation track

The following capabilities are intentionally not silently inserted into C4-C11. They require their own future stage and stage-start explanation:

- MySQL physical DDL and physical comparison contract;
- PostgreSQL metadata-based CRUD;
- Db2 for z/OS metadata-based CRUD;
- MySQL metadata-based CRUD;
- incoming-FK migration/deployment planning across externally owned tables;
- physical-option migration;
- Oracle LOB-specific physical storage;
- partition/subpartition physical modeling;
- SQL Server `TEXTIMAGE_ON`, `FILESTREAM_ON`, and partition-scheme support;
- additional Db2/zOS physical recovery/cluster/storage semantics;
- front-end/UI work.

A deferred item may only enter the active track after it is explicitly promoted and its scope is described.

## 7. Stage status rules

Use only these roadmap states:

- `NEXT` - next stage to be started;
- `PENDING` - approved sequence item not yet started;
- `IN PROGRESS` - stage has started after its pre-stage description;
- `BLOCKED` - cannot proceed because a required dependency/evidence is missing;
- `DONE` - exit criteria and required tests have been satisfied;
- `DEFERRED` - intentionally outside the current execution track.

When a stage is completed, this roadmap must be updated before the next stage is started.

## 8. Current next action

The next controlled roadmap stage is:

```text
C6 - Standard Artifact Manifest
```

C5 is complete and user-verified. Targeted naming/layout regression passed `50/50`; the R1 repair verification passed `1/1`; and the exact repaired source passed full `mvnw.cmd clean test` with `492` tests, `0` failures, `0` errors, and `4` environment-gated skips at `2026-08-22T23:33:56-07:00`. The current official frozen source inventory remains `253` main Java files / `172` test Java files with fingerprint `8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37`.

A post-freeze repair candidate `C5.3-R2` now addresses the real-EA MySQL `NUMBER(19,0)` AutoNum compatibility gap. Its candidate source inventory is `253` main Java files / `173` test Java files with fingerprint `de0eaac67c9488f71d8a57fe36a55459b6b558dcc61161976def3b25aa29a42c`. C6.1 manifest-schema design may proceed, but C6 production-source implementation must wait until R2 passes its targeted and full regression gate. C6 must still be explained with its exact manifest schema, producer/writer changes, compatibility impact, and test plan before implementation starts.
