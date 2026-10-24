# SchemaForge V0.5.9 — Shared JDBC Targets, Selected CRUD/Live Diagrams, Atomic Glossary Approval

Date: 2026-10-24. Baseline: V0.5.8. Java 21 / Spring Boot 3.5 / React 19. The V0.5.9 UI is not a substitute for live seven-DBMS acceptance.

## Explicit user decisions implemented

1. Navigation and page title: `تنظیمات اتصال به دیتابیس`.
2. Database Audit / CRUD / live Diagrams share an explicit DBMS/profile picker, rather than asking for JDBC URL, username or password again.
3. Backend-configured and enabled DBMS profiles are activated by `POST /api/v1/connections/configured/activate` with `{platform}` only. The password stays on the server. The activation uses the same bounded catalog probe as manual setup. No auto-success if a connection fails.
4. Profiles manually activated in Settings are retained in the current browser runtime and can be reused from all three forms. More than one target for a DBMS forces explicit selection, never silently chooses one.
5. Schema is selected separately from JDBC login; a SYSTEM/ADMIN metadata reader may audit tables belonging to other visible schemas. Authorization is still enforced by each DBMS.
6. Selected audit retains the V0.5.8 explicit target, 8 rule families, acknowledgment, and returned target identity.
7. CRUD UI uses only `/api/v1/generate/{oracle|sqlserver}/crud/selected` and a valid matching token. Legacy nonselected REST endpoints remain for compatibility, but the UI never uses them.
8. Live diagram adds `GET /api/v1/diagram/mermaid/selected?schema=...&table=...`, for the selected token, with read-only metadata and 150-table schema limit. Existing file-based Mermaid/Graphviz path is preserved and does not require JDBC.
9. Active session tokens are transient, not saved in browser storage or H2. Expired tokens require reactivation. Provisioning endpoints remain loopback-only pending centralized authentication.

## H2 policy: `01_Atomic_Terms` -> APPROVED

- Source of truth is the packaged `glossary/01_Atomic_Terms.xlsx` (946 Excel rows, 804 unique identities: 800 `ATOMIC`, four `PHRASE`). The uploaded full workbook has the same Atomic sheet.
- New H2 baseline rows now start as `APPROVED` instead of `CANDIDATE`.
- On the **first startup after deployment**, an idempotent, transactional, versioned migration updates *existing* live `ECOBANK_ATOMIC_XLSX` entries to `APPROVED`, and records one row per changed term in `glossary_baseline_approval_log` and a marker in `glossary_baseline_migration`.
- Exact kind/key matching is required; old `ECOBANK_XLSX` terms matched by the importer are retagged first. No broad `UPDATE ... WHERE status='CANDIDATE'` is used.
- Manual-source records, discovered terms, deleted records and second-sheet phrases are **never overridden**. If these exceptions overlap the baseline, approved count can be less than 804 and migration counters disclose skipped identities.
- The marker prevents subsequent service restarts from undoing new steward decisions. Future manual review and automatic NEW-candidate discovery retain their existing rules.
- The migration executes only when Glossary is enabled and `SCHEMAFORGE_GLOSSARY_SEED_ON_STARTUP=true` (both are defaults). The actual operator H2 resides at `${SCHEMAFORGE_GLOSSARY_DATABASE_PATH}` or `./data/schemaforge-glossary` by default.
- The DELTA/FULL ZIPs intentionally exclude any `*.mv.db` and `*.lock.db` files. It is not possible to edit the user's running Windows H2 remotely; back it up with the Backend stopped, deploy, then restart the new Backend.

## Operator acceptance

Before applying the delta: stop Spring Boot. Back up `data\schemaforge-glossary.mv.db`, or the path configured by the environment variable, with no H2 process attached.

```cmd
cd /d D:\Projects\schema-forge-v4
copy /Y "data\schemaforge-glossary.mv.db" "data\schemaforge-glossary.mv.db.pre-v059.bak"
tar -xf "%USERPROFILE%\Downloads\schemaforge-v4-2026-10-24-shared-targets-glossary-approved-V059-DELTA-FLAT.zip" -C "D:\Projects\schema-forge-v4"
call scripts\accept-console-v059.cmd
mvnw.cmd spring-boot:run
```

On startup look for `Atomic Terms approved baseline: rows=946 unique=804 inserted=... migrated=... firstApplication=true`; on later starts `firstApplication=false`, `migrated=0`.
Stop Backend and run `scripts\verify-glossary-v059.cmd` to query the **actual** H2 file; avoid H2 exclusive-file contention. Review `KIND`, `STATUS`, `COUNT` and migration marker. If H2 path is custom, set `SCHEMAFORGE_GLOSSARY_DATABASE_PATH` before verification too.

In UI: select DBMS; verify actual JDBC target, database/user, discovered schemas. Test an existing table through Audit, CRUD (Oracle/SQL Server only) and live Diagram. Test switching profiles of the same DBMS and that invalid/expired tokens fail rather than falling back. Test Diagram from canonical ZIP with no connection. Verify unchanged Canonical/DDL regression suite and Glossary manual decision persistence.

## Test status at delivery

- Java: syntax-parser checked; real Maven/JUnit suite must run on Windows.
- Node selected-target tests plus available UI offline tests: run in authoring environment.
- Full Vite build and all UI tests require npm dependencies on Windows.
- Live DBMS behavior requires connection to authorized test DBMS; no arbitrary production DDL executed by this release.
