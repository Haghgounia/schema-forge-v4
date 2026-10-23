# SchemaForge V4 — REST-first Operations Console roadmap

**Roadmap ID:** SF-V4-UI-2026-10-22  
**Baseline:** SchemaForge 4.0.1 (eight Java REST controllers / 24 mapped operations)  
**Status:** **WINDOWS NODE TEST / TYPECHECK / VITE BUILD PASS; LIVE REST AND BROWSER ACCEPTANCE OPEN**  
**Canonical Glossary closure plan:** [GLOSSARY-V4145-CLOSURE-AND-UI-ROADMAP.md](GLOSSARY-V4145-CLOSURE-AND-UI-ROADMAP.md)

## Scope decision

The React frontend is a **project-wide SchemaForge Console**, not a Glossary-only site. It MUST consume existing REST endpoints; it MUST NOT duplicate parsing, canonical models, Oracle/DBMS metadata logic, DDL rendering, validation, Glossary review policy, or H2 access. Any operation lacking a supported production REST endpoint remains accessible only through its existing external interface until a separate backend API gate is approved. Backend builds never require Node.js.

**UI philosophy:** Minimal React + TypeScript + Vite, native HTML forms, one shared transport module, CSS only; accessible responsive Persian RTL with LTR technical identifiers; server-side pagination where provided; no Redux, extensive UI kit, bespoke API, new database, elaborate dashboard, or artificial business counters.

## Verified inventory: existing production REST methods (24 operations)

| Module | Existing route | Frontend action | Status |
| --- | --- | --- | --- |
| Generation | `POST /api/v1/generate/word` | Standard Word -> ZIP | implemented |
| Generation | `POST /api/v1/generate/legacy-word` | Legacy Word + schema -> ZIP | implemented |
| Generation | `POST /api/v1/generate/legacy-word/zip` | Legacy batch ZIP + schema -> ZIP | implemented |
| Generation | `POST /api/v1/generate/zip` | Standard batch ZIP -> ZIP | implemented |
| Generation | `POST /api/v1/generate/ea-xml` | EA/XMI + optional schema/identity -> ZIP | implemented |
| Conformance | `GET /api/v1/conformance/table` | One table audit | implemented |
| Conformance | `GET /api/v1/conformance/schema` | Schema audit | implemented |
| CRUD | `POST /api/v1/generate/oracle/crud` | Oracle SQL package | implemented |
| CRUD | `POST /api/v1/generate/sqlserver/crud` | SQL Server SQL package | implemented |
| Mermaid | `POST /api/v1/diagram/mermaid/canonical-json` | Diagram text download | implemented |
| Glossary | `GET /api/v1/glossary/terms` | Query, kind/status, server pagination | implemented |
| Glossary | `GET /api/v1/glossary/terms/{id}` | Fetch by ID / editor | implemented |
| Glossary | `POST /api/v1/glossary/terms` | Create term | implemented |
| Glossary | `PUT /api/v1/glossary/terms/{id}` | Update term | implemented |
| Glossary | `DELETE /api/v1/glossary/terms/{id}` | Soft delete term | implemented |
| Glossary | `POST /api/v1/glossary/bulk-status` | Change reviewed cohort status | implemented, guarded |
| Glossary | `POST /api/v1/glossary/import` | XLSX Atomic Terms import | implemented |
| Glossary | `GET /api/v1/glossary/export.csv` | CSV export | implemented |
| Glossary | `GET /api/v1/glossary/export.xlsx` | Excel export | implemented |
| Glossary | `GET /api/v1/glossary/spelling-suggestions` | Read-only spelling advice | implemented |
| Glossary | `POST /api/v1/glossary/review/import-report` | Generated NEW CSV evidence import | implemented |
| Glossary | `GET /api/v1/glossary/review/queue` | Pending/resolved review queue | implemented |
| Glossary | `POST /api/v1/glossary/review/decision` | Reasoned approve/reject | implemented |
| Glossary | `GET /api/v1/glossary/review/history` | Event history | implemented |

**Important semantics:** `GlossaryController` permits direct status edits and bulk approvals that do **not** emit one `REVIEW_DECISION` event per row. The Console supports the existing contract without fabricating audit entries, labels these operations as bypassing review history, and requires confirmation (typed `BULK` for bulk operations). For individually auditable decisions use the Review Queue. The preferred long-term policy is to enforce role-based and actor-attributed review in the server **before** shared production rollout.

## Implementation gates

- [x] **UI-C0:** Inventory all `@RestController` endpoints and classify requests, responses, authentication and file downloads (24 operations).
- [x] **UI-C1:** Project-wide top-level nav (Home, Generation, Conformance, CRUD, Mermaid, Glossary); compact Glossary subnav (Terms, Queue, History, Management); no fake endpoints.
- [x] **UI-C2:** One same-origin REST transport, Unicode-safe multipart and query parameters, GET read-only, server error states, streaming attachment download; `502` guidance; no changes in Java.
- [x] **UI-C3:** All 14 Glossary REST operations wired; dangerous direct/bulk writes deliberately confirmed; server owns persistence and authorization.
- [x] **UI-C4:** 11 isolated Node/mock-HTTP API contract tests passed in authoring environment, TypeScript transpilation with local QA stubs passed. **Neither means npm production build passed.**
- [x] **UI-C5a:** Windows `npm install` succeeded (0 vulnerabilities reported); `npm run test`: **11 passed, 0 failed**; `npm run typecheck`: **PASS**; `npm run build`: **PASS** (Vite 8.3.5). Evidence: operator CMD log and Chrome overview screenshot from 2026-10-22. Lockfile remains local; commit it to the project repository and use `npm ci` for reproducible installs.
- [ ] **UI-C5b:** Cross-browser, mobile/responsive, keyboard/accessibility checks and loaded-result browser flows remain OPEN. Initial Chrome overview is visible, but API connectivity failed before live data could load.
- [ ] **UI-C6:** Live backend smoke for all 24 operations. Use isolated H2 and nonproduction DBMS connections. Keep request response evidence and compare generated ZIP/SQL with Swagger for identical parameters. Confirm term statuses and Review History.
- [ ] **UI-C7:** Security gate for shared use: HTTPS same-origin ingress, gateway/BFF identity, role-based Steward authorization, CSRF design if using cookies, request correlation/actor audit, safe upload limits, and no static admin key bundled or stored in browser storage.
- [ ] **UI-C8:** Deploy artifact and rollback instructions; document supported browser versions and accessibility baseline.

## Operational safety rules

1. UI never accesses H2 directly; do not include real `.mv.db` in source ZIPs. During acceptance set `SCHEMAFORGE_GLOSSARY_DATABASE_PATH` to a new test directory and `SCHEMAFORGE_GLOSSARY_CANDIDATE_REGISTRATION_MODE=DRY_RUN`.
2. The current backend uses shared `X-Glossary-Admin-Key` for write/queue/history: React holds the user-entered key **in volatile in-page state only**. This remains local acceptance only. Production requires server-side identities and audit, not hard-coded secrets in React.
3. Vite proxy `/api/*` targets `http://127.0.0.1:9090` by default, matching `src/main/resources/application.yml` (`server.port: 9090`). Optional `SCHEMAFORGE_API_PORT` in the **Vite process** changes only the numeric loopback port. A `502` means the Java server is stopped or unreachable/mismatched; no wildcard CORS or unrestricted network exposure.
4. Only server-returned success counts as success; React must never locally pretend that a rejected/approved term changed.
5. Avoid accidental repeated writes: disable submit while pending. Human confirmation for irreversible/large-scope changes.
6. Live Conformance and CRUD operate on the configured DBMS connection on the Java side. UI must not ask for or store database passwords.
7. Treat backend content as plain text, not arbitrary HTML; maintain LTR direction for code/SQL/identifier cells.

## Immediate acceptance checklist (Windows CMD)

1. Start Spring Boot separately; for Glossary acceptance enable it with an isolated H2 path and a server-side admin key.
2. Run `curl.exe -i "http://127.0.0.1:9090/api/v1/glossary/terms?page=0&size=1"`. Interpret: `200` = backend responds; `404` = Glossary controller disabled/missing; connection refused = Java/port not started.
3. Run `cd /d D:\Projects\schema-forge-v4\ui`, then `npm install`, `npm run test`, `npm run typecheck`, `npm run build`, `npm run dev`.
4. Use read-only calls (terms, export, spell, conformance) first. Test all write/import/bulk/delete with **only disposable H2** and explicitly approved test DBMSs. Confirm all outputs in Java and manifest. Do not use a customer database.
5. Record evidence, failures and patches against `UI-C5` / `UI-C6`; do not mark production accepted on source inspection alone.

## Relationship to Glossary P1–P5

UI implementation does not close Glossary V4.14.5 P1–P5: native scanner `DRY_RUN` on 836 Word Canonical snapshots, safe second-run AUTO idempotence on isolated H2, bank-term Steward curation, four entry-path integration, full Maven regression, and performance/precision remain separate acceptance gates. Maintain both roadmap files together.

## 2026-10-22 — Windows UI-C5a pass and backend port reconciliation

- Operator verified `npm install` (0 reported vulnerabilities), `npm run test` (11/11), `npm run typecheck` and Vite production `npm run build` all successful; Vite served the Chrome landing screen at `127.0.0.1:5173`.
- Live request produced Vite `ECONNREFUSED 127.0.0.1:8080`: the frontend default was `8080` while Java `src/main/resources/application.yml` specifies `server.port: 9090`. Corrected development proxy default to **9090** and accepted a tightly validated numeric port override `SCHEMAFORGE_API_PORT` (localhost only). No REST/Java changes.
- **Next gate:** start Java on 9090 with isolated test H2, verify direct curl and same-origin proxy GET, then complete all 24-operation live acceptance; update UI-C6. Do not run mutating Glossary operations on customer H2.


## 2026-10-22 — UI-C6a: generated ZIP report viewer, no REST change

**Status:** IMPLEMENTED locally; Windows build/browser acceptance PENDING.

- [x] Every existing Generation POST first downloads the ZIP as before, then **in-memory, browser-only** ZIP inspection (lazy JSZip 3.10.1). No new HTTP calls, Java changes, or Glossary/H2 mutation.
- [x] Read `manifest.json`: display `requestStatus`, generated/blocked/skipped counts, validation error/warning counts; show detailed `BLOCKED` and `SKIPPED` reasons. **Do not equate a successful download with `SUCCESS`.**
- [x] Read `reports/glossary/summary.json`: distinguish `newTerms`, `eligibleTerms`, `suspectedTypos`, `manualReviewTerms`, `pendingTerms` and plural violations. Display `candidateRegistration.mode` and `insertedCandidates` without claiming `DRY_RUN` performed a write.
- [x] Display term-to-table/column evidence from `reports/glossary/unknown-terms.csv`; allow report text/CSV/JSON preview and individual report downloads, plus reuse of the original ZIP download without regeneration.
- [x] Robust failure boundaries: a damaged/oversized ZIP affects preview only; keep original Blob/download. Compression limits 100 MiB archive; streaming decompression capped at 2 MiB per text preview / 10 MiB per extracted individual report. Show backend strings as text, not injected HTML.
- [x] Four new Node ZIP-inspection tests passed; previous 11 REST-contract tests still passed (**15/15**), and parser passed standalone strict TypeScript check. Verified against uploaded EA sample: `PARTIAL_SUCCESS`, 193 generated, 3 blocked, 7 skipped, 124 validation errors, Glossary 145 new / 122 eligible / 1 typo / 22 review, mode `DRY_RUN`, inserted 0; `ISIC` appears in `ORGANIZATION.ISIC_CODE` and `PARTY_EMPLOYMENT.ISIC_CODE`.
- [ ] **UI-C6a acceptance gate:** On user's Windows, run `npm install`, `npm run test`, `npm run typecheck`, `npm run build`; generate EA ZIP through UI and confirm preview counts and source evidence against `manifest.json`/`summary.json`/CSV. Test CSV preview, individual download, original ZIP re-download, and failure fallback.
- [ ] Full backend-integration acceptance for all 24 REST operations and UI-C7 security remain separate open gates.

**Decision:** This extension is presentation only. Do not change an existing Controller or rewrite `requestStatus` in frontend. Glossary P1–P5 remain open until their own acceptance evidence is recorded.


## 2026-10-22 — UI-C6b: diagram previews and contextual Glossary review

**Implementation:** React source integrated, no Java/REST/controller modification. **Acceptance:** Vite/browser and live reviewer mutation still pending.

- [x] `Generation ZIP > نمودارها`: enumerate actual `.mmd`/`.dot` files in `diagram/mermaid` and `diagram/graphviz`; browser-only lazy Mermaid and Viz.js Graphviz WASM rendering, zoom, SVG download, source and original graph download; bounded input/outputs and safe SVG image context.
- [x] `Diagram > Mermaid REST`: show a rendered result from the existing `.mmd` response without calling another endpoint. No Graphviz generation route was added or invented.
- [x] `Generation ZIP > Glossary`: term evidence → direct in-memory handoff to `Glossary > Review Queue`; exact public-term lookup, explicit existing admin-key `/review/import-report` if needed, and existing individually audited `/review/decision` for actual `CANDIDATE` rows.
- [x] CSV import explicitly warns that **all NEW occurrences in the selected ZIP report** may create candidates; **not only** the clicked word. Re-import is governed by backend SHA and row ordinal idempotency. No silent import, no auto-approval, no backend change.
- [x] Four focused ZIP/diagram/handoff tests added; older tests retained. Tests and build in authoring environment blocked by npm registry DNS (`EAI_AGAIN`) and MUST NOT be treated as a PASS.
- [ ] Windows `npm install`, `npm run test`, `npm run typecheck`, `npm run build`, Chrome Mermaid/Graphviz rendering, SVG zoom/download verification and fallback on malformed/large diagram.
- [ ] Glossary Queue integration on isolated H2: `DRY_RUN` term not presumed persisted; explicit import, retry-idempotence, subsequent individual reasoned decision and event history checks.
- [ ] Production authorization, identity and CSP review (UI-C7) remain open. Client admin header is for local acceptance, not production RBAC.

**Evidence:** Uploaded `schemaforge-ea-20261022-211119-890.zip` includes two Mermaid `.mmd` and two Graphviz `.dot` diagrams plus `reports/glossary/summary.json` and `reports/glossary/unknown-terms.csv`; uploaded operator screenshot displays ZIP Glossary 55 new / 39 eligible / 16 manual, `DRY_RUN`, 0 inserted. Browser-level new visualization and queue handoff are not yet verified.

- Diagram screen also supports local `.mmd`, `.dot` and `.gv` file preview without any upload/REST call.

### 2026-10-22 operator evidence — C6a visual

The operator's Chrome capture for `schemaforge-ea-20261022-211119-890.zip` visibly confirms generation response `SUCCESS`, 37 generated, 0 blocked, 78 skipped and 79 validation warnings; the Glossary tab shows 55 new terms, 39 eligible, 16 manual review, mode `DRY_RUN` and 0 inserted. The uploaded ZIP contains four graph sources. This confirms ZIP report visualization, **not yet** client-side Graphviz/Mermaid rendering or live review-import acceptance. Preserve this distinction in UI-C6b.
