# SchemaForge Operations Console — React V0.2

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

## UI modules

| Navigation | Existing REST operations |
| --- | --- |
| Home | Entry points only; no artificial metrics or fabricated `/dashboard` API |
| Generate | Standard Word, Legacy Word, Legacy ZIP batch, Word ZIP batch, EA XML/XMI; target DBMS, audit and timestamp options; download ZIP |
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

The authoring environment passed 11 Node mock-HTTP contract tests and a static TypeScript pass using local QA stubs. **An actual Vite dependency installation/build and live browser/Java integration on the user's Windows system remain unverified.** Run the commands above and record results in the UI roadmap.

Perform all Create/Update/Delete/Bulk/Import/Steward action acceptance tests **only on disposable H2**. Test live Oracle/SQL Server CRUD and multi-DBMS conformance against explicitly approved nonproduction instances. Preserve Canonical/DDL regressions through Maven tests. UI work does not close Glossary P1–P5 acceptance gates.
