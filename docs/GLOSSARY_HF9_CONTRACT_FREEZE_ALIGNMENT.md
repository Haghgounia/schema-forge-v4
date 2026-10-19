# SchemaForge V4.14.2-HF9 — Contract Freeze alignment for Glossary extension

## Evidence (Windows HF8, 2026-10-19)

* Maven `dependency:resolve`: **BUILD SUCCESS**, H2 `2.4.240` selected.
* Glossary dedicated gate: **17 tests, 0 failures, 0 errors**, including persistent close/reopen snapshots at 804 entries / 4 source multiword phrases.
* Full `mvnw.cmd clean test`: **972 tests, 2 failures, 0 errors, 10 skipped**.
* The only full-suite failures are stale exact-enumeration freeze tests:
  1. `ApiContractFreezeTest.restErrorContractAndCorrelationHeaderAreFrozen` expected the 13 pre-Glossary codes; the domain now exposes additive `UNAUTHORIZED` and `CONFLICT` codes for Glossary write protection / duplicate terms.
  2. `ArtifactContractTest.artifactTypesShouldCoverEveryC41InventoryFamilyWithoutTransportSpecificZipType` expected the 12 original C4.1 families; Glossary reports are now tracked as `GLOSSARY_REPORT`.

## HF9 scope / release decision

* **Tests only** (plus docs): Explicitly extend the exact REST error-code freeze with `UNAUTHORIZED` and `CONFLICT`. Existing 13 enum codes and `schemaforge-rest-error/v1` payload / correlation header remain frozen.
* Preserve all 12 C4.1 artifact families. Extend the exact enum freeze with the single approved `GLOSSARY_REPORT` family; no transport-specific ZIP enum is permitted.
* Strengthen the existing Glossary MockMvc test to assert machine-readable JSON `$.code` and `$.contract` for HTTP 401 and 409. This prevents a superficial enum-only update from concealing broken REST error responses.
* **No Production source, H2 version, seed data, API URI, Report, Manifest, DDL, or schema mutations.** No reimport or database reset.
* `V4.14.2-HF9` is a test-contract rebaseline of an existing additive functionality; callers that integrate the new Glossary endpoints must be prepared to handle the new codes. Existing endpoint payload contracts are unchanged.

## Windows acceptance

Apply `HF9 Delta` to the already-installed `HF8` project.

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean -Dtest=ApiContractFreezeTest,ArtifactContractTest,GlossaryRestControllerTest test
mvnw.cmd clean test
```

Expected targeted tests: **15 test methods** (6 + 8 + 1), all passing.
Expected full suite on the same environment: **972 tests, 0 failures, 0 errors, 10 skipped** assuming no unrelated environmental changes. Run actual full suite to confirm.

If this gate succeeds, Glossary test regression can be considered closed; production readiness remains dependent on the organization's deployment, security and backup checks.
