# SchemaForge Operations Console — React V0.3

A small, **project-wide** React + TypeScript + Vite client that calls **only the existing REST controllers** in SchemaForge V4. One common navigation supports Generation, Conformance, CRUD, Mermaid and all 14 Glossary REST operations. The Java backend remains authoritative for H2, Oracle/DBMS metadata, DDL, validation, review and permission checks.

Full 24-route contract and acceptance status: `docs/roadmap/SCHEMAFORGE-CONSOLE-UI-ROADMAP.md`. Glossary P1–P5 milestones: `docs/roadmap/GLOSSARY-V4145-CLOSURE-AND-UI-ROADMAP.md`.

## Requirements

- Node.js compatible with installed Vite (the project uses Vite 8); npm.
- SchemaForge backend started separately on `http://127.0.0.1:9090` (default from `src/main/resources/application.yml`).
- For Glossary: enabled **on an isolated test H2**; admin key set only in the Java process.
- Windows CMD works; PowerShell is not needed.

## Start backend in a separate CMD window

For a **nonproduction acceptance instance** (do not reuse your real Glossary DB):

```cmd
cd /d D:\Projects\schema-forge-v4
mkdir "D:\Sample-Docs-Scripts\SchemaForge-UI-Acceptance"
set "SCHEMAFORGE_GLOSSARY_ENABLED=true"
set "SCHEMAFORGE_GLOSSARY_DATABASE_PATH=D:\Sample-Docs-Scripts\SchemaForge-UI-Acceptance\glossary"
set "SCHEMAFORGE_GLOSSARY_CANDIDATE_REGISTRATION_MODE=DRY_RUN"
set "SCHEMAFORGE_GLOSSARY_ADMIN_KEY=<choose-a-unique-local-test-secret>"
mvnw.cmd spring-boot:run
```

Remove the literal angle-bracket placeholder from the secret; it is not a suggested password. If your DB2 LUW Java runtime requires it, use the project's existing `-Pdb2luw-live` Maven profile. Leave this CMD window running. Do not change DBMS connections without authorization.

Test the proxy target **before** launching React:

```cmd
curl.exe -i "http://127.0.0.1:9090/api/v1/glossary/terms?page=0&size=1"
```

- `200` — Java REST is reachable and Glossary is enabled.
- `404` — Glossary controller may be disabled or wrong application/path.
- Connection refused or timeout — Java is not listening at port 9090.
- UI `HTTP 502` at `127.0.0.1:5173` — Vite is running, but its `/api` proxy could not get a valid response from the target; check Java startup logs, port, and proxy configuration.

**Optional port override:** If Java deliberately runs on a different local port, set `SCHEMAFORGE_API_PORT` **in the UI terminal** before starting Vite. The proxy always targets `127.0.0.1` and rejects invalid/out-of-range ports. For example, to pair with a backend explicitly launched with `SERVER_PORT=8080`, run `set "SCHEMAFORGE_API_PORT=8080"` before `npm run dev`. Restart Vite after changing this variable. Keep the default `9090` when possible.


## Install and run React in another CMD window

```cmd
cd /d D:\Projects\schema-forge-v4\ui
npm install
npm run test
npm run typecheck
npm run build
npm run dev
```

Open `http://127.0.0.1:5173`. The frontend sends `/api/*` through the Vite development proxy to `127.0.0.1:9090`; do not enable public/wildcard CORS. The built `/dist` contains static files and is excluded from source ZIPs. After a verified install, commit `package-lock.json` in your own repo and use `npm ci` for repeatable deployment.

## Inspecting generated ZIP reports in-place (UI-C6a)

After any of the five existing Generation REST operations returns a ZIP, the browser **first downloads that unchanged ZIP** and then reads its reports **locally**, without calling a new REST endpoint:

- **Result status** comes from `manifest.json` (`requestStatus`, `artifactOutcomes`, `validation`). The UI distinguishes `SUCCESS` from `PARTIAL_SUCCESS`; HTTP 200/ZIP download alone does **not** mean every DDL was generated. The Status tab lists `BLOCKED` and `SKIPPED` artifacts with backend-provided reasons.
- **Glossary tab** reads `reports/glossary/summary.json`: `newTerms`, `eligibleTerms`, `manualReviewTerms`, `suspectedTypos`, `pendingTerms`, plural-column terms and `candidateRegistration.mode/insertedCandidates`. Selecting a term reads source table/column evidence from `reports/glossary/unknown-terms.csv`. **`DRY_RUN` never inserts a candidate**, and the UI never performs an implicit Glossary import or Steward approval.
- **Files tab** previews `manifest.json` and JSON, CSV or text files under `reports/`, with a 75-row CSV preview and direct download of individual reports. Other formats can be downloaded rather than rendered. Entire original ZIP can be downloaded again from the same in-memory Blob, without regenerating backend artifacts.
- ZIP inspection is lazy (`jszip@3.10.1`, loaded only after generation); it has 100 MiB compressed archive, 2 MiB per text preview, and 10 MiB per individual file extraction bounds. Excessive/malformed ZIP content disables only the **preview**, never the original download. ZIP bytes and admin keys are not persisted in local/session storage. UI renders report text escaped via React, not HTML.
- **No Java, REST Controller, H2, Canonical Model, DDL, or server-side Validation change.** Previewing a report never changes the server's authoritative status.

Acceptance fixture: real `schemaforge-ea-20261022-134452-919.zip` was checked locally — 193 generated, 3 blocked, 7 skipped, `PARTIAL_SUCCESS`, Glossary 145 new, 122 eligible, 1 suspected (`ISIC`), 22 manual, `DRY_RUN` 0 inserted, 2 `ISIC` evidence rows. This is **local archive parsing acceptance**, not yet a Windows Chrome end-to-end UI acceptance. For a local run:

```cmd
cd /d D:\Projects\schema-forge-v4\ui
npm install
npm run test
npm run typecheck
npm run build
npm run dev
```

## UI modules

| Navigation | Existing REST operations |
| --- | --- |
| Home | Entry points only; no artificial metrics or fabricated `/dashboard` API |
| Generate | Standard Word, Legacy Word, Legacy ZIP batch, Word ZIP batch, EA XML/XMI; target DBMS, audit and timestamp options; download ZIP; local read-only ZIP report preview |
| Conformance | Table/schema audit for one or more DBMSs; server findings/summary, download JSON |
| CRUD | Oracle and SQL Server live metadata CRUD SQL generation and download |
| Diagram | ER, Dependency, Conceptual ERD; canonical JSON/ZIP with schema/table scope; download `.mmd` text |
| Glossary Dictionary | Search, read by ID, kind/status, pagination, CSV/XLSX export, spelling suggestions |
| Glossary Review | Queue (pending/resolved), reasoned decision (APPROVED/REJECTED), unknown-terms.csv report import |
| Glossary History | Workflow history and term filter |
| Glossary Management | Create, update, soft-delete, Import Atomic Excel, bulk status changes |

## Governance and intentionally limited UI

- **All 14** Glossary endpoints have a frontend affordance. The backend alone enforces the rules and returns authoritative results.
- `X-Glossary-Admin-Key` is manually entered and kept **only in volatile React state**. It is never stored in localStorage, sessionStorage, frontend build variables, or URLs. Browser reload clears it. This remains local acceptance only; shared production access needs actor-specific server auth/RBAC/audit, HTTPS, gateway/BFF and relevant CSRF controls.
- Direct `PUT /terms/{id}`, `POST /bulk-status`, and privileged import statuses exist in the backend but do not produce individual `REVIEW_DECISION` audit events. UI warns and requires confirmation; bulk requires typing `BULK`. **The queue's reasoned decision endpoint is the preferred route for Steward approval.**
- Deleting a term is the backend's soft delete, not file or physical H2 deletion. Read-only conformance does not modify the target DBMS.
- There is no new React database, no direct DBMS credentials in browser, no dynamic user-defined backend URLs, no Redux/component kit, no server business logic copied into JavaScript.
- The project uses basic hash navigation for browser back/forward. API strings are rendered as text, not injected HTML; identifiers and SQL have LTR direction.

## Acceptance (not yet complete)

Windows acceptance on 2026-10-22 verified 11 Node REST tests, TypeScript typecheck, npm install and production Vite build. Later local work added 4 ZIP inspection tests (total **15**), and validated a real EA ZIP; **the updated V0.3 UI still needs a Windows typecheck/build and live browser acceptance**. Run the commands above and record results in the UI roadmap.

Perform all Create/Update/Delete/Bulk/Import/Steward action acceptance tests **only on disposable H2**. Test live Oracle/SQL Server CRUD and multi-DBMS conformance against explicitly approved nonproduction instances. Preserve Canonical/DDL regressions through Maven tests. UI work does not close Glossary P1–P5 acceptance gates.
