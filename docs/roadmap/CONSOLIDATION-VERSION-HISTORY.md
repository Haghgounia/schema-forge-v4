# SchemaForge V4 - Consolidation Version and Repair History

**Purpose:** authoritative traceability for consolidation candidates, repairs, verification runs, and official freezes.  
**Current official baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C11`  
**Current frozen source fingerprint:** `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`

## 1. Documentation rule for corrective versions

Every corrective or repair version created during the remaining V4 consolidation work must be recorded here and in `CHANGELOG.md` before the next stage is frozen. Each record must state:

1. version/stage identifier;
2. reason for the correction;
3. exact production/test/documentation scope changed;
4. whether consumer-visible behavior changed;
5. targeted verification result;
6. full regression result when required;
7. source fingerprint for any source/test change;
8. promotion status: `CANDIDATE`, `REPAIR`, `OFFICIAL`, or `SUPERSEDED`.

Historical stage-input statements are not rewritten merely because a later baseline exists. For example, a C5.1 design document may correctly say that its input baseline was C4.3. Current-state documents, indexes, and release references must always point to the latest official baseline.

## 2. Consolidation version history

| Version / checkpoint | Type | Change scope | Verification | Source fingerprint | Final status |
|---|---|---|---|---|---|
| C1 consolidated baseline | OFFICIAL | Capability inventory; MySQL GRANT parity; capability cleanup; MySQL physical-comparison guard; documentation alignment | `467 / 0 / 0 / 4`, reverified | `77e038a4acb5631d4a407174d9e075cc3d773d21b96a7e884410d9fbdc00525c` | SUPERSEDED BY C4.2 |
| C4.2 Artifact Contract candidate | CANDIDATE | Added Artifact Contract core metadata model and 8 tests; no generation-path behavior change | Targeted `8/8` | `8b76049ff698850bfd79cd497c309ea61bda607db719d33bb93b9c3f6721ad75` | PROMOTED |
| C4.2 official freeze | OFFICIAL | Documentation freeze only after exact candidate regression | Full `475 / 0 / 0 / 4` | `8b76049ff698850bfd79cd497c309ea61bda607db719d33bb93b9c3f6721ad75` | SUPERSEDED BY C4.3 |
| C4.3 Artifact tracking candidate | CANDIDATE | Request-local generation context/ledger; production-path descriptor tracking; no naming/layout change | Targeted `23/23` | `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423` | PROMOTED |
| C4.3 official freeze | OFFICIAL | Completed Artifact Contract V1 production-path mapping | Full `482 / 0 / 0 / 4` | `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423` | SUPERSEDED BY C5.3 |
| C5.1 naming/layout analysis | DOC/DESIGN | Source-derived Current -> Proposed inventory; no source/test/runtime change | Documentation review | unchanged from C4.3 | INPUT TO C5.2 |
| C5.2 naming/layout decisions | DOC/DESIGN | Fixed artifact-first roots, timestamp policy, collision policy, compatibility constraints | Documentation review | unchanged from C4.3 | INPUT TO C5.3 |
| C5.3 naming/layout candidate | CANDIDATE | Central `ArtifactNamingPolicy`; canonical artifact-first layout; request timestamp; collision handling; updated tests | Targeted `50/50`; first full run `492` with 1 failure | `5b600c90b3d42ea0fdbf18ef48d8832f8336d22fa2c39406f01ac82a5821c1a6` | REPAIRED BY C5.3-R1 |
| C5.3-R1 regression repair | REPAIR | Test-only repair in `DirectoryDualDatabaseGenerationRunnerTest`; production code unchanged | Repair `1/1`; full `492 / 0 / 0 / 4` | `8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37` | PROMOTED |
| C5.3 official freeze | OFFICIAL | Documentation freeze after exact R1 source passed full regression | Targeted `50/50`; repair `1/1`; full `492 / 0 / 0 / 4` | `8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37` | SUPERSEDED BY C5.3-R2 |
| C5.3-R2 MySQL NUMBER(19) identity repair | REPAIR / OFFICIAL | `NUMBER(19,0)` AutoNum -> `BIGINT UNSIGNED`; internal FK type propagation; per-table schema type context; real EA regression | Targeted `38/38`; full `496 / 0 / 0 / 4` | `de0eaac67c9488f71d8a57fe36a55459b6b558dcc61161976def3b25aa29a42c` | SUPERSEDED BY C6.2 |
| C6.1 Standard Artifact Manifest design | DOC/DESIGN | Fixed Manifest V1 JSON shape, integrity/self-entry policy, deterministic order, package invariants, EA migration, and test plan | Documentation review; no C6 source change | unchanged from R2 | DESIGN COMPLETE / INPUT TO C6.2 |
| C6.2 Standard Artifact Manifest implementation | OFFICIAL | Common Word/Legacy/ZIP/EA manifest; checksum/size integrity; self-entry; deterministic order; EA legacy-manifest replacement | Targeted `46/46`; full `504 / 0 / 0 / 4` | `b9fa369b5e9b079279fb577d40c77e1b14c8193fedea2f85ab0e6edadaf8969f` | SUPERSEDED BY C7.2 |
| C7.1 REST Response/Error Contract design | DOC/DESIGN | Fixed versioned error shape, request correlation, status/code mapping, success compatibility, and non-goals | Documentation review; no C7 source change | unchanged from C6.2 | DESIGN COMPLETE / INPUT TO C7.2 |
| C7.2 REST Response/Error Contract implementation | OFFICIAL | Central advice, request-correlation filter, stable error codes, dedicated service-unavailable exception, controller handler removal, REST contract tests | Targeted `31/31`; full `525 / 0 / 0 / 4` | `763dcea0451ee0420c1886a11858452288c34e02a721a1aab166de673daa0a26` | SUPERSEDED BY C8.1 |
| C8.1 DiagramArtifactProducer extraction | OFFICIAL | Moved per-document and batch Mermaid/Graphviz artifact production out of `SchemaForgeApiService`; added focused producer regression | Targeted `55/55`; full `527 / 0 / 0 / 4` | `128900965948b2686b4d1fa7d5b8b78278756b3be8e4926d48db320f271cba8e` | SUPERSEDED BY C8.2 |
| C8.2 MigrationArtifactProducer extraction | CANDIDATE | Moved migration artifact orchestration out of `SchemaForgeApiService`; preserved diff/rendering/Flyway naming; added focused producer regression | Targeted `45 / 0 / 3 / 0` - test fixture timestamp invalid | `7b9b012c74d53524acbb83cb09d1304f4a4bb19d4fbf742381a85fbafeb31f79` | SUPERSEDED BY C8.2-R1 |
| C8.2-R1 MigrationArtifactProducer test repair | REPAIR / OFFICIAL | Test-only correction of fixed ArtifactGenerationContext timestamp to C5 `yyyyMMdd_HHmmss_SSS`; production source unchanged; exact repaired source promoted | Targeted `45/45`; full `530 / 0 / 0 / 4` | `aa77b6bfe9248ebe7b061b2cd39a75ece5e34e765fd121a0ea62d1701a6f1e14` | SUPERSEDED BY C8.3 |
| C8.3 ComparisonArtifactProducer extraction | OFFICIAL | Moved document/EA comparison-workbook orchestration out of `SchemaForgeApiService`; preserved writer, lookup, paths, PostgreSQL EA lowercase naming, and ledger semantics | Targeted `52/52`; full `533 / 0 / 0 / 4` | `90b8fcb7c8a2998b0aa878e01b59bb1d77f6916560514ce4e65dc2afdc927cab` | SUPERSEDED BY C8.4 |
| C8.4 CrudArtifactProducer extraction | OFFICIAL | Moved metadata-based Oracle/SQL Server CRUD artifact orchestration out of `SchemaForgeApiService`; preserved generators, grants, lookup/fallback, summary CSV, paths and ledger semantics | Targeted `44 / 0 / 0 / 0`; full `536 / 0 / 0 / 4` | `8f134a74c2967a3c005b0250280d09cb75854d0c185f9485de947a749b2e8c57` | SUPERSEDED BY C8.5 |
| C8.5 BatchArchiveSupport extraction | OFFICIAL | Moved ZIP-batch filesystem/ledger helper mechanics out of `SchemaForgeApiService`; preserved orchestration, collision paths, Ledger remap, summary/error and manifest behavior | Targeted `39 / 0 / 0 / 0`; full `539 / 0 / 0 / 4` | `49eaad1760cd693a32fb0f9571b404cd395d679a1b45d084cc30ee33eb80ae0f` | SUPERSEDED BY C8.6 |
| C8.6 ArtifactPackageBuilder extraction | OFFICIAL | Moved common directory-to-ZIP packaging, path normalization, and best-effort recursive cleanup out of `SchemaForgeApiService`; preserved archive entry paths/content and all orchestration | Targeted `34 / 0 / 0 / 0`; full `542 / 0 / 0 / 4` | `a5f01c4c0fa180632743ae6be7a4d7cd0b49f618c1eb087a09bd0375658e8afb` | SUPERSEDED BY C8.7 |
| C8.7 DocumentGenerationOrchestrator extraction | OFFICIAL | Moved shared Standard/Legacy Word orchestration out of `SchemaForgeApiService`; preserved parsers, preparation, DBMS loop, producer dispatch, canonical JSON, paths and Ledger semantics | Targeted `35 / 0 / 0 / 0`; full `545 / 0 / 0 / 4` | `a8e0f2d94895ab2142ac8bc88aefa7f5b8d4029df2bf3ea297b883f076add57d` | SUPERSEDED BY C8.8 |
| C8.8 BatchGenerationOrchestrator extraction | CANDIDATE | Moved ZIP-batch orchestration out of `SchemaForgeApiService`; preserved request validation, document generation, collision remap, diagnostics, aggregate diagrams, manifest, archive paths and Ledger provenance | Targeted `39 / 1 / 0 / 0` - test assertion over-selected non-batch summary producers | `779e3751ccb29e90885027b3dce3b94e62a422e7c67003d25a38bcda63a6dc31` | SUPERSEDED BY C8.8-R1 |
| C8.8-R1 Batch diagnostic provenance test repair | REPAIR / OFFICIAL C8.8 | Test-only narrowing of the provenance assertion to `logicalName=batch-generation`; production source unchanged | Targeted `39 / 0 / 0 / 0`; full `548 / 0 / 0 / 4` | `b8fb0a328ef0ad382e963227a050e14f8f128cadc2a2ff38afd55eb05379b396` | SUPERSEDED BY C8.9 |
| C8.9 EaGenerationOrchestrator extraction | OFFICIAL | Moved EA XML/XMI preparation and multi-table generation orchestration out of `SchemaForgeApiService`; preserved five-DBMS DDL, PostgreSQL naming, dependency/run-all, manifest and Ledger semantics | Targeted `42 / 0 / 0 / 0`; full `551 / 0 / 0 / 4` | `9ef7e1315e82b86817864d94431b20ce02a367ca1777c622dd5a5f7102db6898` | SUPERSEDED BY C8.10 |
| C8.10 ArtifactGenerationService extraction | CANDIDATE | Moved shared Standard/Legacy Word workspace, upload transfer, manifest, package and cleanup workflow out of `SchemaForgeApiService`; preserved facade validation/context and all generation semantics | Main compile failed before tests: missing `PreparedSchema` import in facade at 2026-08-23T06:03:25-07:00 | `dfe575066ace7ac8de555e9f1a561c00f1a0b217d54b6c8147680d1aa552db40` | SUPERSEDED BY C8.10-R1 |
| C8.10-R1 / C8.10 official | OFFICIAL / FROZEN | Restored only the still-required `PreparedSchema` import in `SchemaForgeApiService`; no method-body or test changes; C8 service decomposition complete | Targeted `43 / 0 / 0 / 0` at 2026-08-23T06:15:32-07:00; full `554 / 0 / 0 / 4` at 2026-08-23T06:22:23-07:00; BUILD SUCCESS | `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba` | `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.10` |
| C9 test matrix / live-validation classification | DOC / TEST-GOVERNANCE CHECKPOINT | Added authoritative 189-file test matrix, live-evidence vocabulary, DBMS prerequisites and gate policy; no source/test changes | Inherits exact C8.10 green source: targeted `43/43`, full `554 / 0 / 0 / 4`; matrix audit `189/189` unique rows | `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba` | SOURCE BASELINE UNCHANGED; C10 DONE |
| C10 documentation consolidation | DOC CHECKPOINT | Aligned current reference/index documents to C8.10+C9, corrected stale test count/five-DBMS architecture wording, preserved intentional four-DBMS physical scope, audited local links | Source unchanged; reference/index link audit 0 broken; inherits C8.10 targeted `43/43` and full `554 / 0 / 0 / 4` | `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba` | SOURCE BASELINE UNCHANGED; C11 NEXT |
| C11 final consolidation verification | OFFICIAL / FROZEN | No source change; 95-test consolidation gate + exact-source full regression; current references finalized; Git-based package integrity required | Targeted `95 / 0 / 0 / 0` at 2026-08-24T10:54:16+03:30; full `554 / 0 / 0 / 4` at 2026-08-24T10:16:01+03:30; BUILD SUCCESS | `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba` | `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C11` |

Counts are reported as `tests / failures / errors / skipped` where four values are shown.

## 3. C5.3-R1 root-cause record

The first full C5.3 regression exposed one stale test assumption, not a production generation defect:

- `DirectoryDualDatabaseGenerationRunnerTest` enumerated only direct children of the output root;
- C5 intentionally places generated DDL under `ddl/oracle/` and `ddl/postgresql/`;
- all expected scripts were generated, but the old root-only assertion counted zero;
- R1 changed only the test to recursively enumerate regular files and assert the canonical C5 DDL roots;
- no parser, DDL renderer, SQL content, migration logic, REST behavior, or naming/layout production code changed in R1.

Verification:

```text
Targeted C5.3 suite : 50 tests, 0 failures, 0 errors, 0 skips
R1 repair test      : 1 test, 0 failures, 0 errors, 0 skips
Full clean regression: 492 tests, 0 failures, 0 errors, 4 skips
Build               : SUCCESS
Finished            : 2026-08-22T23:33:56-07:00
```

## 4. Current stage boundary

C11 is the current official consolidated V4 baseline. Its exact source remains the C8.10-R1 source: `276` main Java / `189` test Java with fingerprint `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`. C11 passed the targeted `95 / 0 / 0 / 0` gate and full `554 / 0 / 0 / 4` clean regression on 2026-08-24. C8, C9, C10 and C11 are complete. The C4-C11 consolidation track is closed; any deferred feature must be explicitly promoted into a new stage before source changes begin.
