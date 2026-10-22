# SchemaForge V4 — Glossary closure and lightweight React UI roadmap

**Roadmap ID:** SF-V4-GLOSSARY-2026-10-22  
**Baseline:** SchemaForge 4.0.1 / V4.14.5, source archive `schema-forge-v4-2026-10-21-1815.zip` plus root-hygiene source delta  
**Last updated:** 2026-10-22  
**Status:** **ACTIVE — do not mark V4.14.5 production-accepted yet**  
**Owner of approval:** project maintainer / authorized Glossary Steward  
**Next gate:** Word corpus native Glossary DRY_RUN, followed by isolated-H2 idempotence evidence.

This file is the **canonical ongoing checklist** for Glossary and its intentionally small UI. Update its status, evidence, and next gate **in the same change** as implementation; do not rely on chat transcripts as the only project record. Historic designs remain in `docs/GLOSSARY_NAMING_GOVERNANCE_V14.md`, `docs/GLOSSARY_V14_3_OPERATIONAL_AND_WORKFLOW.md`, `docs/GLOSSARY_V4144_PLURAL_SPELLING_XLSX_EXPORT.md`, `docs/GLOSSARY_V4145_CANDIDATE_REGISTRATION_P1_P2.md` and the separately authored `SchemaForge.docx` V4.14.5 P1–P5 plan.

## 1. Immutable governance constraints

1. A discovered term may become `CANDIDATE` only; **never** automatically `APPROVED` or `REJECTED`.
2. Only an explicit authorized Steward decision changes a live `CANDIDATE` to `APPROVED` or `REJECTED`, with a reason. Protect soft-deleted/rejected terms.
3. Prefer canonical atomic singulars. Recognized plurals never generate duplicate atomic candidates; column plural rules remain in effect.
4. Offline spelling is **non-authoritative**; specialist terminology and suspicious spelling require human review. Never invent Persian translations.
5. Glossary scanning/registration occurs once in the shared generation path, not once per DBMS; no automatic change to executable DDL or Canonical Model.
6. `DRY_RUN` must cause **zero H2 changes**; `AUTO_CANDIDATE` requires explicit opt-in, on isolated H2 during acceptance. Never overwrite customer H2 data or include `.mv.db` in packages.
7. UI must call the existing REST contract; **server-side authorization is mandatory** regardless of UI affordances. No browser-embedded shared key or persistent token storage.

## 2. Evidence-based baseline (as of 2026-10-22)

| Evidence | Result | Meaning |
| --- | --- | --- |
| Maven targeted Glossary acceptance on Windows 2026-10-22 10:43:44 +03:30 | **32 tests, 0 failures, 0 errors, BUILD SUCCESS** | Unit/integration subset verified; **not** whole application regression |
| EA `DRY_RUN` vs `AUTO_CANDIDATE` Oct 21 | 159 auto-eligible registrations, 1,024 evidence occurrences in the AUTO run | Initial integrated flow; acceptance H2 and rerun gate still open |
| EA generated SQL / Canonical comparison | 791 SQL files semantically matched; Canonical JSON structures unchanged | Confirms compared cohort, not all possible inputs |
| Word directory `manifest(3).json` 2026-10-22 | 851 discovered / 836 snapshots / 15 parse failures | **Word-to-Canonical** success; not yet native Glossary scanner acceptance |
| Static Word corpus audit | 836 tables, 8,939 columns, 25 schemas; 1,147 unique tokens | Exploratory **not** authoritative glossary classification |
| Baseline lexical comparison of corpus | 458 exact baseline hits, 689 other tokens (135 known plural, 43 short/noncanonical, 56 possible plural, 455 other) | No assumption that 689 tokens are registrable candidates |
| Historical baseline glossary export | 804 `CANDIDATE` ATOMIC terms | Historical exported workbook, **not** current live H2 status |
| Extra corpus QA | 7 byte-identical repeated documents, 28 repeated qualified table-name groups, 201 column plural uses | Evidence to stratify regression, not automatic errors |

**Known sample investigations:** `DEALLER` vs `DEALER`; `GRANTOR` and `RECALC` classification; banking abbreviations (`SATNA`, `PAYA`, `TXN`, `IBAN`); `PROFILES`/`PROFILE`; compound `SUMOF`; 15 Word parser failures (4 identifier length, 9 non-table, 1 missing column specification, 1 missing table metadata).

## 3. Phases, open work and acceptance criteria

Legend: `[x]` evidence-backed complete; `[~]` implemented but acceptance/open details remain; `[ ]` uncompleted.

### P1 — Terminology discovery / classification

- [x] Common Glossary scanner with status distinctions; singular/plural policy; offline spelling path.
- [x] 836 successful Word Canonical snapshots available as exploration corpus.
- [ ] **P1-G1:** Execute **native** `GlossaryScanner` and offline lexical classifier over a stratified subset of those snapshots; preserve term-to-source evidence; `DRY_RUN` only.
- [ ] **P1-G2:** Investigate short codes, known bank terminology, false positives, `DEALLER` and plural/compound cases with Steward; record explicit decisions.
- [ ] **P1-G3:** Expand evaluation to all 836 valid snapshots; compare actual native classifications and changes across reruns. Track the 15 parser failures **separately** (not silently as Glossary failures).

### P2 — Safe automatic CANDIDATE registration

- [x] `OFF`, `DRY_RUN`, `AUTO_CANDIDATE`; transactional H2 intake and evidence; guard for deleted/curated keys.
- [x] Targeted Windows test gate: 32/32 green (2026-10-22).
- [~] One EA DRY_RUN/AUTO pair compared with unchanged executable SQL and Canonical structure.
- [ ] **P2-G1:** On a **separate** H2 DB: take backup / record starting counts, run DRY_RUN then AUTO twice; second run must have `insertedCandidates=0`; no approved/rejected/deleted mutation, no duplicate evidence or new plural term.
- [ ] **P2-G2:** Verify rollback and concurrent registration behavior on the accepted corpus and record stable Maven/SQL evidence.

### P3 — Steward governance and quality

- [x] Existing REST queue, term search, decision, history, CSV report import and read-only CSV/XLSX export.
- [~] Lightweight React UI V0.1 **source added**: queue + explicit reasoned decision, term search/export/spelling, history, report import (see section 4). Frontend build and browser/REST smoke **not yet accepted**.
- [ ] **P3-G1:** Review representative banking terms in the real queue and document why each was approved/rejected or kept pending.
- [ ] **P3-G2:** Define enterprise Steward identity/RBAC, immutable actor-aware audit and coverage of **all** mutation paths (`PUT /terms`, `bulk-status`, import, etc.) before shared production use.
- [ ] **P3-G3:** Document queue prioritization / source filters to support large candidate volume. If required, extend the **backend pagination/filter API** first; do not fake global filtering client-side.

### P4 — Multi-entry integration

- [x] Shared `GlossaryReportWriter` reused from EA, Standard Word, Legacy Word and Batch ZIP.
- [ ] **P4-G1:** End-to-end acceptance for all four entry paths with one request-level terminology pass; verify SQL, manifest, reports and no duplicated registration.
- [ ] **P4-G2:** If refresh of shared H2 lexicon is required, design it as a small backend contract with cache invalidation tests; do not duplicate validation logic in UI.

### P5 — Full regression, performance, release closure

- [ ] **P5-G1:** `mvnw.cmd clean test` on current source; archive Surefire results.
- [ ] **P5-G2:** Offline precision/recall sample, spelling false positives, concurrent registration and repeated Word corpus comparison.
- [ ] **P5-G3:** Evaluate EA P95 and AUTO mode against the **<35 sec** target (historical DRY_RUN example 27.38 sec); preserve logs outside root.
- [ ] **P5-G4:** Release evidence: official acceptance note, explicit default `DRY_RUN` or authorized change, rollback and H2 backup/restore instructions, operator guide.

## 4. React UI integration: project-wide SchemaForge Console

**Scope correction (2026-10-22):** the React UI is a shared **SchemaForge Operations Console**, not a Glossary-only application. The canonical cross-project UI roadmap and 24-endpoint coverage matrix are now maintained in [SCHEMAFORGE-CONSOLE-UI-ROADMAP.md](SCHEMAFORGE-CONSOLE-UI-ROADMAP.md). Every Glossary REST operation is represented in its module, but Glossary P1–P5 acceptance remains independent of UI acceptance.

| UI gate | Result | Status |
| --- | --- | --- |
| Project-wide navigation / 10 non-Glossary operations | Word/Legacy/EA ZIPs, Conformance table/schema, Oracle/SQL Server CRUD, Mermaid | source implemented; browser acceptance pending |
| Glossary operations, 14/14 | Search, get, create, update, soft delete, bulk status, Atomic XLSX import, CSV/XLSX export, spelling, report import, queue, decision, history | source implemented; live H2 acceptance pending |
| Mock-REST contract | 11 Node tests verified on Windows; TypeScript and Vite build passed | Windows **PASS** on 2026-10-22 |
| Local production build, cross-browser and E2E | Windows `npm run test`, `typecheck`, `build` **passed**; Chrome shell visible; Java/REST live, keyboard/mobile, security still pending | partially accepted |

**Shared-key limitation:** current `X-Glossary-Admin-Key` is **not actor-specific**. It is entered manually and kept only in transient React state for local acceptance; no localStorage, sessionStorage, token in URL, or build-embedded admin key. Before enterprise shared usage implement backend/gateway user identity, RBAC, actor-attributed audit and CSRF controls. Existing direct term edit/status change and bulk-status endpoints bypass per-term `REVIEW_DECISION` audit; UI warns and asks confirmation, it does not invent server-side audit.

**Development reverse proxy:** `/api` forwards to localhost:9090 by default (matches Spring `application.yml`, configurable by `SCHEMAFORGE_API_PORT` in Vite CMD). HTTP 502 in the browser while UI at port 5173 is loading means inspect the Java process/port first. Separate `404` can indicate disabled Glossary controller. The frontend never connects directly to H2 or customer DBMS credentials.

## 5. Immediately next execution order

1. Verify the project-wide Console with `npm run test`, `npm run typecheck`, `npm run build` on Windows, then browser/REST smoke of its 24 operations; exercise Glossary writes only on a separately configured H2 database.
2. Run native Glossary `DRY_RUN` on the Word corpus; capture term classification report and zero H2 changes.
3. Curate spelling/plural/acronym exceptions using documented Steward review.
4. Run controlled AUTO twice on isolated H2; close P1/P2 safety gates.
5. Run P4 multi-entry + P5 full regression/performance; close release when accepted.

## 6. Status / evidence update template

When one gate passes, **edit this file** with:

```text
Gate ID:
Date / SchemaForge commit or source ZIP:
Input corpus / output paths:
Command and runtime environment:
Tests / results / quantitative counters:
Result: PASS | FAIL | DEFERRED
Open anomalies and assigned next gate:
```

Do not mark an unexecuted Windows/npm/browser step "PASS" based on source inspection. Keep generated logs/Excel/corpus files under ignored `logs/` or `test-output/`, not the project root.


## 2026-10-22 — Read-only ZIP Glossary report visualization (Console UI-C6a)

The project-wide Console now reads **the existing generation ZIP Blob** in the browser and displays `reports/glossary/summary.json` (new, eligible, typo, manual-review, pending and plural groups) and identifier evidence from `reports/glossary/unknown-terms.csv`. It **does not** call a new REST endpoint, import a report automatically, change H2, or promote any term. The manifest's `PARTIAL_SUCCESS` is displayed independently of HTTP download success. The existing EA `DRY_RUN` sample reports 145 new / 122 eligible / 1 possible typo (`ISIC`) / 22 manual / 0 inserted, with ISIC source evidence from two column identifiers. This is a presentation improvement, **not** proof that P1–P5 are complete; Windows browser acceptance of this UI addition remains open under UI-C6a.
