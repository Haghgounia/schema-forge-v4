## V0.5.9 — Target selection in Audit / CRUD / Diagrams

First choose a DBMS. If exactly one usable connection exists, it is selected or activated from Backend configuration without asking for a password. If multiple connections exist for the DBMS, explicitly choose a profile. Newly activated custom profiles from «تنظیمات اتصال به دیتابیس» are reused across pages in the current browser session. Schema is independent from JDBC username (e.g., SYSTEM may read another Schema when authorized). CRUD and live Mermaid use only selected metadata tokens; the existing offline JSON/ZIP diagram path remains available without a DBMS.

# SchemaForge Operations Console — React V0.5.4

A small, **project-wide** React + TypeScript + Vite client that calls **only the existing REST controllers** in SchemaForge V4. One common navigation supports Generation, Conformance, CRUD, Mermaid and all 14 Glossary REST operations. The Java backend remains authoritative for H2, Oracle/DBMS metadata, DDL, validation, review and permission checks.

Full 24-route contract and acceptance status: `docs/roadmap/SCHEMAFORGE-CONSOLE-UI-ROADMAP.md`. Glossary P1–P5 milestones: `docs/roadmap/GLOSSARY-V4145-CLOSURE-AND-UI-ROADMAP.md`.

## Requirements

- Node.js compatible with installed Vite (the project uses Vite 8); npm.
- SchemaForge backend started separately on `http://127.0.0.1:9090` (default from `src/main/resources/application.yml`).
- Glossary is **enabled by default**. Use an isolated test H2 for acceptance; its admin key is set only in the Java process. `DRY_RUN` does not auto-register newly detected candidates.
- Windows CMD works; PowerShell is not needed.

## Start backend in a separate CMD window

For a **nonproduction acceptance instance** (do not reuse your real Glossary DB):

```cmd
cd /d D:\Projects\schema-forge-v4
mkdir "D:\Sample-Docs-Scripts\SchemaForge-UI-Acceptance"
rem Glossary is enabled by default; no SCHEMAFORGE_GLOSSARY_ENABLED setting is required.
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
- ZIP inspection is lazy (`jszip@3.10.1`, loaded only after generation); it has a 100 MiB compressed archive limit, a 32 MiB uncompressed Manifest limit, 8 MiB Glossary-summary limit, a 2 MiB bounded prefix per generic text preview, and a 64 MiB per-report download ceiling. Excessive/malformed ZIP content disables only the **preview**, never the original download. ZIP bytes and admin keys are not persisted in local/session storage. UI renders report text escaped via React, not HTML.
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


## 2026-10-23 — Interactive Graphviz/Mermaid viewer (UI-C6c)

The **same React viewer** is used for ZIP diagram reports, locally opened `.dot/.gv/.mmd`, and `.mmd` generated by the existing Mermaid REST endpoint. SVG stays in an `<img>` rather than being injected into page HTML.

- **Fit to Screen** initially frames the entire SVG. **Fit to Width** frames its width; **100%** uses the SVG user-unit scale for readable labels. **Reset** returns to Fit to Screen.
- **Pan:** left-drag with mouse or drag on a touch screen; focused viewport also accepts arrow keys. **Zoom:** mouse wheel around pointer, `+` / `−` buttons and keys. Use key `0` to reset.
- **Full Screen:** native browser Fullscreen API when permitted; falls back to a viewport-filling overlay. Escape exits.
- **Find Table:** names are indexed from `.dot` node declarations or Mermaid entity declarations, including `%% SF_... = SCHEMA.TABLE` aliases. Choose or type `SCHEMA.TABLE`; the viewer attempts to center and mark its location using Graphviz SVG node geometry or supported Mermaid SVG entity IDs. If a Mermaid rendering mode does not expose usable coordinates, it says so rather than pointing to an arbitrary node.
- All SVG parsing for navigation is **inert** and local; source and downloaded SVG are unchanged. Rendering bounds, ZIP caps, and backend contracts are unchanged. No new REST endpoint, Java change, database access or remote service.

**Acceptance:** the user has already confirmed baseline Graphviz and Mermaid rendering in Chrome and all 19 earlier Node tests on Windows. The new zoom/pan/search/fullscreen controls and Vite build **still require Windows Chrome confirmation**. Run `npm install`, `npm run test` (expect 24 tests), `npm run typecheck`, and `npm run build`. Then use a generated ZIP containing Graphviz and Mermaid files, test both modes, search for an exact entity name, download SVG, and verify the browser console is clear.

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


## 2026-10-22 — ZIP diagrams and Glossary review handoff (Console UI 0.4.0)

- **No new REST route or Java change.** `Generation > Results > Diagrams` lists only `diagram/mermaid/**/*.mmd` and `diagram/graphviz/**/*.{dot,gv}` from the ZIP returned by existing Generation endpoints. Select an existing graph to render using lazy local Mermaid (`11.17.2`) or Viz.js / Graphviz WASM (`@viz-js/viz 3.31.0`). The direct Mermaid REST page also shows the rendered SVG after generating it.
- Rendering requires no internet or remote diagram service after `npm install`; the source stays inside the browser. The SVG is displayed with `<img src=blob:...>` **instead of injecting untrusted SVG into DOM**. Mermaid uses `securityLevel: strict`, no active click binding and HTML labels disabled. Resource bounds: ZIP <=100 MiB, graph source <=256 KiB, SVG <=12 MiB. Graphs that are too large can still be retrieved from the ZIP.
- Each diagram has **zoom, SVG download, source text and original DOT/MMD download**. `Graphviz` diagrams must be DOT; they are rendered with the dot layout engine. Rendering is visual only: no validation or SQL generation occurs in React.
- From `Generation > Results > Glossary`, select a word and choose **پیگیری در صف Glossary**. A transient in-memory handoff holds the original `reports/glossary/unknown-terms.csv` (max 5 MiB) if present. In `Glossary > Review Queue` the existing public terms search looks for the exact atomic token; if `CANDIDATE` exists, standard reasoned review can proceed through **existing** `/review/decision` and admin key.
- For `DRY_RUN`, no newly discovered candidate is necessarily persisted. The user **may explicitly confirm** importing the **entire** `unknown-terms.csv` with existing `/review/import-report` (admin-key protected); it processes **all rows with review_status=NEW**, not only the clicked term. No auto-approval, no implicit DB write and no secret/file storage in localStorage, hash or URL. Restrict this acceptance step to an isolated test H2, never the production H2.
- `npm run test`, `npm run typecheck`, `npm run build` are mandatory on operator Windows following `npm install`. The frontend renderer dependencies could not be installed in the authoring environment because `registry.npmjs.org` was unreachable (`EAI_AGAIN`); full Vite build and live rendering are therefore **unverified** until those operator tests and Chrome walkthrough.

### Acceptance checklist

1. Apply the DELTA, run `cd ui`, `npm install`, `npm run test`, `npm run typecheck`, `npm run build`, `npm run dev`.
2. Generate the same `Collateral_EXPORT.xml` EA request and select both Mermaid `.mmd` and Graphviz `.dot` under **نمودارها**; verify the visible graph, zoom, and SVG/source downloads against the ZIP.
3. Run the standalone Diagram REST page with Canonical JSON and verify Mermaid renders without a second network request.
4. From Glossary terms in the ZIP, select a token, inspect table/column evidence and click **پیگیری در صف Glossary**. Check H2 status with the queue/terms endpoint. If the term is absent, confirm that **no candidate was created automatically**.
5. Navigate from Generation to Glossary Queue and back: the last downloaded generation ZIP and its report preview remain available in volatile App memory without regeneration (until page reload). **Only on isolated disposable H2:** set server admin key and explicitly import the CSV using the contextual action; verify server result counts, re-import idempotency, queue population and an individual reasoned decision via the existing controller. `DRY_RUN` is a scanner mode, **not** a read-only prohibition on an explicit admin API call.

- Diagram screen also supports local `.mmd`, `.dot` and `.gv` file preview without any upload/REST call.

### V0.4.2 — Mermaid table search regression fix (UI-C6d)

When searching an entity such as `DPS.DEPOSIT_ACCOUNT_ATTRIBUTE` inside a large conceptual ERD, the viewer now resolves both Mermaid node IDs and **exact rendered entity labels** (including nested SVG / foreignObject labels). This avoids a V0.4.1 issue where source aliases were indexed but rendered node coordinates were unavailable. The fix is local to the browser; no API or database operation occurs on search.

To verify on Windows:

```cmd
cd /d D:\Projects\schema-forge-v4\ui
npm run test
npm run typecheck
npm run build
npm run dev
```

Open the same ZIP, choose its Mermaid conceptual-erd, search the full table name, and check that a marker appears at the selected table. This browser acceptance on the 112-table example is still required.


## 2026-10-23 — Focus Mode, 1- or 2-hop relationships (UI-C6e, V0.4.3)

In a generated ZIP's **Diagrams** tab or the standalone local diagram viewer, first choose a generated Mermaid `.mmd` or Graphviz `.dot/.gv` ERD, and enter a table in the existing `SCHEMA.TABLE` search input. Select **Focus / Show Relationships** and choose **Direct (1 hop)** or **Two levels (2 hops)**. The viewer renders a new **local SVG** containing the selected table and its related tables, using only relationship statements that already exist in the source. It does **not** clip the original giant SVG, re-derive foreign keys, or call the backend. The selected root gets a location marker when supported by the SVG version. **Back to Full View** restores the original full graph, and source download always provides the untouched original `.mmd/.dot`.

Both FK directions count toward adjacency, but rendered Mermaid cardinality/label text and Graphviz arrow/edge labels stay unchanged. Isolated tables remain visible when selected. A breadth-first 1-hop or 2-hop neighborhood is used; edges between any included nodes are retained. A limit of 150 nodes / 450 edges prevents a pathological focused rendering. Unknown DOT/Mermaid syntax is not guessed: Focus Mode disables itself and the full view continues to work. Focused SVG exports are named `.focus-1.svg` or `.focus-2.svg` to avoid conflating them with full exports. The original generated ZIP/Canonical/DDL is unaffected.

**Local tests:** nine new focus tests passed. Eight original generator outputs from two user-supplied ZIPs were parsed successfully, 1/2-hop source generated and re-parsed, and scoped DOT output rendered to valid SVG with Graphviz. The isolated TypeScript structural check passed; the full React/Vite Build remains a **Windows acceptance gate**, not yet a verified result for this version.

```cmd
cd /d D:\Projects\schema-forge-v4\ui
npm install
npm run test
npm run typecheck
npm run build
npm run dev
```

Run the 112-table Mermaid and Graphviz browser acceptance for one exact table (for example `DPS.DEPOSIT_ACCOUNT_ATTRIBUTE`) and both depths. Verify the relation counts and directions, reset/return to full view, scrolling, fullscreen and focused SVG download. No Java or database restart is required.

### Runtime metadata connections (V0.5.1 - supersedes V0.5.0)

The Connections screen is a **self-service** workspace. No `SCHEMAFORGE_CONNECTIONS_ADMIN_KEY`,
no server configuration editing, and no Java restart are needed. It reads the existing
non-secret seven-DBMS startup defaults only as optional editable starting values.
The password is never returned to the browser or reused from server configuration.

1. Select Oracle, PostgreSQL, SQL Server, MySQL, MariaDB, DB2 LUW or DB2 z/OS.
2. Supply JDBC URL, database username, password and one schema for metadata validation.
3. `Test only` checks JDBC login plus vendor metadata `schemaExists` and `findTableNames`.
4. `Test metadata and use connection` rechecks and, only on success, issues a short-lived
   opaque browser connection token. No H2 write, no SQL DDL/DML, no change to application.yml.
5. The token is kept **in React memory**, not localStorage, cookies, ZIP, or URL; the
   transport automatically attaches it only to Conformance, CRUD and Generation REST calls.
   The server's `MetadataRepositoryResolver` uses the selected, vendor-specific JDBC repository.
   The user can disconnect; the backend expires tokens after 30 minutes.

`GET /api/v1/connections`, `POST /test`, `POST /activate`, `GET /active`, `DELETE /active` are
new versioned API operations. All provisioning requests require loopback remote address AND
loopback Host/Origin as an interim safeguard: **this local-only iteration is not a remotely
accessible multi-user production connection manager**. An authenticated HTTPS gateway and
an organization-level permission model are prerequisites before enabling untrusted remote access.
Do not bypass this restriction by exposing the Spring Boot port or using an unauthenticated
proxy. The user's JDBC account should be read-only at the database level.

Runtime selection is per browser and per DBMS, not a global repository mutation. While selected,
other DBMS metadata calls are rejected to prevent silent mixing of startup/default connections.
On browser refresh the in-memory selection is forgotten, and the server entry expires naturally.
A successful schema catalog check does not certify SELECT privilege on every table. The target
DBMS continues to authorize subsequent reads. For DB2, the IBM JCC driver must already be installed.

Windows acceptance: start Java normally (`mvnw.cmd spring-boot:run`), run Maven focused tests
for `DatabaseConnectionAdministrationServiceTest,DatabaseConnectionControllerTest,RuntimeMetadataConnectionRegistryTest,RuntimeMetadataResolverSelectionTest`,
then `npm install`, `npm run test`, `npm run typecheck`, `npm run build`, `npm run dev` in `ui`.
Use a **nonproduction** database, activate, run a Conformance Table request and compare it to
that same DB's catalog. Check the server returns an error for mismatched DBMS or expired token.

### V0.5.2 — Optional Schema and database discovery (UI-C9c)

`Schema for metadata` is now optional. Leave it blank and choose **Test & discover schemas** to retrieve a bounded set of JDBC-visible schemas (or catalogs for MySQL/MariaDB) from the running Java backend. A `<datalist>` in the editor suggests results and the JDBC default is displayed as a hint; manual entry remains possible. The test and activation operations clear the password field, so reenter it if you test and then activate separately.

With a blank Schema, activation requires successful metadata enumeration; it does **not** certify permissions on a specific table. With an explicit Schema, the previous vendor repository `schemaExists` and `findTableNames` checks remain enforced. The active selection has an empty `verifiedSchema` when no explicit schema was checked. During actual Conformance/CRUD/Generation, the request's schema/table is sent to the same selected runtime repository and the database continues to enforce permissions. A JDBC driver that cannot enumerate schemas must be given an explicit schema for strict validation; no guessed schema is silently used. No additional REST route or authentication key was added. The connection API remains loopback-only pending organizational authentication.

See `docs/roadmap/SCHEMAFORGE-CONSOLE-UI-V052-CONNECTION-SCHEMA-DISCOVERY.md` for acceptance gates.


## 2026-10-24 — REST upload cap 500 MiB (Console UI 0.5.3)

The Spring Boot multipart input limit for generation uploads is now **500MB per file**,
with a **520MB request** ceiling to allow multipart framing. Both values are
configurable without source changes via environment variables
`SCHEMAFORGE_UPLOAD_MAX_FILE_SIZE` (default `500MB`) and
`SCHEMAFORGE_UPLOAD_MAX_REQUEST_SIZE` (default `520MB`). The same defaults
are present in the packaged `application.yml` and both external distribution YAML files.
Spring Boot must be restarted after changing the configuration.

The React Generation screen shows the default 500 MB maximum and rejects a
file larger than 500 MiB **before issuing a network request**. This client
check is a usability guard, not the authoritative Spring limit. Other upload
parsers may have stricter per-document limits (e.g. legacy Word) and are not
changed. Glossary CSV/Excel limits remain unchanged.

The 100 MiB cap in `ui/src/zipReports.ts` is for **read-only inspection of a
returned ZIP in browser memory**, not for uploads. Larger generated ZIPs may
still download while preview is skipped. Uploading a 500 MB archive does not
guarantee sufficient Java heap, server temp-disk capacity, or processing time;
monitor the backend during actual large-file acceptance.

Windows acceptance: `npm run test`, `npm run typecheck`, `npm run build`,
`mvnw.cmd -Dtest=SchemaForgeRestExceptionHandlerTest test`, then start Java and
Vite and exercise the generation endpoint with an approved test ZIP between
100 and 500 MB. A 413 response is expected above the configured server limit.


## V0.5.4 - large Batch ZIP previews (UI-C10h)

A compressed Batch ZIP can be small while `manifest.json` is larger than the prior 2 MiB uncompressed report limit. The browser now reads a bounded 32 MiB manifest, compacts non-generated artifact examples to 100 and displays authoritative counts. Batch document counts are separated from artifact outcome counts. Large report previews use bounded prefixes and explicitly report truncation, while individual report downloads allow up to 64 MiB. If even the manifest limit is exceeded, only the **explicitly sourced** request status and document counts in `reports/schemaforge-generation-summary.txt` are shown. The original ZIP remains downloadable, and server upload maximum 500 MiB / request 520 MiB is **not** a browser preview allowance.

The Generation page can also open an **already downloaded output ZIP** directly from disk (no REST regeneration); the same 100 MiB compressed-archive preview ceiling applies.

No Java/REST/H2 change; see `docs/roadmap/SCHEMAFORGE-CONSOLE-UI-V054-LARGE-BATCH-ZIP-PREVIEW.md`.


## V0.5.6 — Large-Batch Glossary evidence preview

Selecting an individual term in `Generation > Results > Glossary` scans the already
retrieved ZIP's `reports/glossary/unknown-terms.csv` incrementally. The full
CSV is **not** copied into a JavaScript row array: the browser shows an exact
occurrence count and the first 25 matching source/table/column records, even
when CSV contents exceed the ordinary 2 MiB report-preview limit. The scan
supports UTF-8 and RFC-4180 quotations, has a 128 MiB uncompressed safety
ceiling and is canceled when the selected term changes. Malformed/over-limit
inputs show an error rather than invented evidence counts.

Manual Glossary Import retains its independent 5 MiB limit and confirmation,
while the original ZIP and reporting download behavior stay unchanged. No
backend change, REST call, H2 mutation or abbreviation analysis is involved.
Windows: run `scripts\accept-console-v056.cmd` from the project root to
check focused Canonical/DDL/seven-DBMS and UI regression gates. Then reopen
an existing Legacy Batch **output ZIP** in the Generation view and select ACC,
ISIC, and TIMEX to validate occurrence counts (5, 2, 452) and term switching.

## V0.5.7 — Lazy Routes, Reports and Diagram Libraries (UI-C11)

The Home page remains eager. All functional pages and the four Glossary sub-views are `React.lazy` chunks. After generation, the ZIP reports pane is loaded separately; the diagram reports tab and shared SVG viewer are separate dynamic modules. The existing `mermaid` / `@viz-js/viz` dynamic imports remain browser-local and occur only when rendering a diagram. The backend never serves or renders external diagrams.

`npm run build` now runs `tsc`, Vite and a **mandatory Vite-manifest import-graph audit**. The static first-load graph must contain none of Graphviz/Mermaid/JSZip heavy bundles and must total no more than 240 KiB gzip including CSS. Manifest is written to `dist/.vite/manifest.json` (a build diagnostic, not an API). The Vite advisory threshold is 1400 kB **only for the known lazy Graphviz chunk**; this is not a performance waiver for startup. Expected output includes `[PASS] Initial UI: ... gzip bytes`.

Windows verification: `npm install`, `npm run test` (expected **65** Node tests), `npm run typecheck`, `npm run build`, `npm run dev`. In Chrome Network tab with cache disabled, load the Home page and inspect network transfers; visit Connections and Glossary, then open Generation with no ZIP. Heavy engines must not load. Finally inspect ZIP diagrams and verify Mermaid/Graphviz, Focus Mode and SVG download. See `docs/roadmap/SCHEMAFORGE-CONSOLE-UI-V057-LAZY-LOADING.md`.

**Scope:** React/Vite only; no Java, REST, H2, Canonical/DDL, JDBC or Glossary policy changes.
