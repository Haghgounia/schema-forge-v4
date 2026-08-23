# SchemaForge V4 - Consolidation Version and Repair History

**Purpose:** authoritative traceability for consolidation candidates, repairs, verification runs, and official freezes.  
**Current official baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C5.3`  
**Current frozen source fingerprint:** `8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37`

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
| C5.3 official freeze | OFFICIAL | Documentation freeze after exact R1 source passed full regression | Targeted `50/50`; repair `1/1`; full `492 / 0 / 0 / 4` | `8566f2218d2737b0c571452e465760908a8c527c05fa0b2bc0b6d8f1a04bad37` | CURRENT |
| C5.3-R2 MySQL NUMBER(19) identity repair | REPAIR CANDIDATE | `NUMBER(19,0)` AutoNum -> `BIGINT UNSIGNED`; internal FK type propagation; per-table schema type context; real EA regression | Java 21 core compile + real EA compatibility probe PASS; Maven pending | `de0eaac67c9488f71d8a57fe36a55459b6b558dcc61161976def3b25aa29a42c` | PENDING VERIFICATION |
| C6.1 Standard Artifact Manifest design | DOC/DESIGN | Fixed Manifest V1 JSON shape, integrity/self-entry policy, deterministic order, package invariants, EA migration, and test plan | Documentation review; no C6 source change | unchanged from pending R2 candidate | DESIGN COMPLETE / C6.2 WAITS FOR R2 |

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

C5 remains the current official completed baseline. A post-freeze corrective candidate, C5.3-R2,
repairs MySQL `NUMBER(19,0)` AutoNum compatibility discovered by real EA input. It is not official
until targeted and full Maven regression are user-verified.

C6 is still the next controlled roadmap stage. C6 design/document work may proceed, but no C6
production-source implementation should be layered on the R2 candidate until its regression gate is green.

Before any C6 implementation begins, its manifest schema, producer/writer changes, compatibility impact, checksum policy, and test plan must be stated explicitly. A later C6 repair must follow the same traceability rule in section 1.
