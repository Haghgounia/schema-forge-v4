> **Current HF6 search:** `GlossaryTerm` records are materialized before all text/kind/status predicates; the paged items and total are computed from the same mapped values. See `GLOSSARY_HF6_MAPPED_TERM_SEARCH.md`.

> **Current search behavior (HF5):** Glossary count and paged items are computed from a single JDBC snapshot and sorted/sliced in Java. This replaces the HF4 SQL OFFSET/FETCH approach. See `GLOSSARY_HF5_CONSISTENT_SEARCH.md`.

# SchemaForge V4.14.2 - Glossary / Naming Governance (Atomic Terms only)

## Preloaded H2 glossary (V4.14.2)

The project ZIP contains `src/main/resources/glossary/01_Atomic_Terms.xlsx`, a compact,
**same 947 rows and 3 column values as the original `01_Atomic_Terms` sheet**. It contains
946 source data rows, 804 distinct normalized entries (800 `ATOMIC` and 4 multiword
`PHRASE` terms); the `02_Canonical_Phrases` worksheet is not bundled or consumed.
Its source workbook SHA-256, compact XLSX SHA-256, and SQL seed SHA-256 are recorded
in `distribution/glossary/bootstrap-manifest.json`.

When `SCHEMAFORGE_GLOSSARY_ENABLED=true`, the app initializes the H2 schema in its
project-local `data/schemaforge-glossary.mv.db` file and seeds all 804 distinct
entries from the bundled Atomic-only Excel **automatically at application startup**.
Both REST import and automatic bootstrap invoke the same parser/deduplication logic.
The default initial state is `CANDIDATE`: a term becomes accepted for strict naming
checks only after a curator deliberately changes it to `APPROVED`. Bootstrap is
idempotent and does **not** overwrite translated labels, manual records, soft deletes,
or approval/rejection decisions when repeated.

Default H2 base path is relative to the process working directory; launch the program
from the project root or set `SCHEMAFORGE_GLOSSARY_DATABASE_PATH` to an absolute path.
To turn seeding off without disabling the glossary, use
`SCHEMAFORGE_GLOSSARY_SEED_ON_STARTUP=false`.

For an immediate, offline first-run initialization **without starting the API server**,
`distribution/bin/bootstrap-glossary-h2.cmd` invokes H2's `RunScript` using the
idempotent UTF-8 script `distribution/glossary/bootstrap-atomic-h2.sql`. It requires an
H2 JDBC jar already in the user's local Maven repository; `H2_JAR` can point to a
jar elsewhere.

**V4.14.2 HF1 compatibility:** The H2 2.3.232 `org.h2.tools.RunScript` CLI
does not support `-charset`; it reads SQL script files as UTF-8 by default.
The launcher therefore passes only `-url`, `-user`, `-password`, and `-script`.
Do not add the unsupported `-charset UTF-8` option. Confirm the post-import
H2 Shell counts: 800 `ATOMIC`, 4 `PHRASE` on a clean database. Run the script with SchemaForge stopped, and back up an existing database
before overwriting/recreating a deployed copy. The offline seed and app startup seed
both create the same schema and reference data.

Note on delivered file types: `*.mv.db` is an H2 **binary file**, not an SQL script.
A fully populated `.mv.db` can only be generated with an actual H2 runtime;
therefore the packaged source files enable deterministic creation upon execution.
Never rename a `.sql`/`.xlsx` to `.mv.db`.


## Scope

The glossary is a **separate H2-backed metadata subsystem**, not a DBMS target and not a replacement for existing naming, spelling, validation, migration, or DDL rules. It validates **table names and column names** in the prepared canonical schema, once per model, before packaging. It does not mutate source models or generate DDL for the glossary store.

The integration is opt-in; default behavior and existing ZIP outputs remain unchanged when `SCHEMAFORGE_GLOSSARY_ENABLED=false`. Enabled applications seed the embedded Atomic baseline at startup by default. When enabled, every successful Word, Legacy Word, ZIP batch (including legacy batch), and EA generation package includes two platform-neutral artifacts (before manifest creation):

- `reports/glossary/unknown-terms.csv`
- `reports/glossary/summary.json`

Both are declared as `GLOSSARY_REPORT` in the Artifact Ledger / `manifest.json` and covered by manifest integrity. Reports do not block DDL generation or change canonical validation status. Empty reports have CSV headers and a `CLEAN` JSON summary.

## Reference workbook

Source: `EcoBank_Reference_Terminology_v0.1_2026-10-06_v67_Atomic_Candidates.xlsx`

SHA-256: `d010e199f699a0ae647e166388dc1149f001c3e982c5dda9cf2c555e01581574`

Only the **`01_Atomic_Terms` worksheet is authoritative** for the current automated XLSX import and naming-reference baseline. The `02_Canonical_Phrases` worksheet is explicitly out of scope, even when it is physically present in the same workbook.

| Reference worksheet | Data rows | Unique normalized entries | V4.14.2 behavior |
|---|---:|---:|---|
| `01_Atomic_Terms` | 946 | 804 | **Imported and evaluated**: 800 one-token `ATOMIC` records, 4 multiword `PHRASE` records |
| `02_Canonical_Phrases` | 52,128 | N/A | **Not read for import, not a vocabulary source for validation** |

Four multiword concepts from `01_Atomic_Terms` itself remain recognized as reviewed multi-token terms: `GENERAL LEDGER`, `SUBSIDIARY LEDGER`, `ADVANCE PAYMENT`, and `BALANCE SHEET`. `PHRASE` is an internal lexical shape, **not permission to import the second worksheet**. Newly created manual glossary entries continue to require explicit curator approval.

The importer requires only `01_Atomic_Terms` to exist, validates its headers, and ignores all other worksheets. On an empty glossary DB with startup seeding disabled, `POST /import` returns `sourceRows=946`, `uniqueEntries=804`, `inserted=804`. After automatic startup seeding, the same REST import reports `inserted=0` and `alreadyPresent=804`. Re-running is idempotent, retaining curated metadata and status.

The reference file is **not implicitly approved**. `POST /import` defaults to `CANDIDATE`. Steward approval (individual `PUT` or explicit `/bulk-status`) is required before terms qualify as approved vocabulary.

## Deployment (Windows CMD)

```cmd
cd /d D:\Projects\schema-forge-v4
set "SCHEMAFORGE_GLOSSARY_ENABLED=true"
set "SCHEMAFORGE_GLOSSARY_DATABASE_PATH=D:\Projects\schema-forge-v4\data\schemaforge-glossary"
set "SCHEMAFORGE_GLOSSARY_ADMIN_KEY=REPLACE_WITH_A_LONG_RANDOM_SECRET"
rem First startup creates ./data/schemaforge-glossary.mv.db and loads Atomic Terms.
mvnw.cmd spring-boot:run
```

`SCHEMAFORGE_GLOSSARY_DATABASE_PATH` is a **base filename**, not a JDBC URL or an extension. H2 creates `<base>.mv.db` on first use, and the startup seeder loads `01_Atomic_Terms` automatically. This is an on-disk, persistent H2 database independent of all `schemaforge.metadata.*` data sources. Use a stable, access-controlled local path and back it up with the database stopped or using H2's backup capability. Never place credentials in source control.

If glossary is disabled, neither H2 nor the glossary controllers are initialized and no glossary reports are generated.

## REST endpoints

Base: `http://localhost:9090/api/v1/glossary`

| Method | Path | Function |
|---|---|---|
| GET | `/terms?page=0&size=100&q=ACCOUNT&kind=ATOMIC&status=APPROVED` | Paged listing/search of the **active** glossary; all filters optional; `size` <= 1000 |
| GET | `/terms/{id}` | Detail |
| GET | `/export.csv` | Export entire active glossary as CSV |
| POST | `/terms` | Create an entry; defaults to CANDIDATE |
| PUT | `/terms/{id}` | Full replacement including approval/rejection status |
| DELETE | `/terms/{id}` | Soft delete; excluded from naming checks |
| POST | `/import?status=CANDIDATE` | Multipart XLSX import of **only `01_Atomic_Terms`**, idempotent for existing keys |
| POST | `/bulk-status?kind=ATOMIC&from=CANDIDATE&to=APPROVED` | Explicit steward status change for a reviewed cohort; `kind` optional |

All mutating endpoints require the header `X-Glossary-Admin-Key` matching `SCHEMAFORGE_GLOSSARY_ADMIN_KEY` (constant-time comparison). If the server secret is unset, mutations fail closed with HTTP 503; invalid credentials return HTTP 401. GET endpoints are read-only and do not require this header. Deploy behind an authenticated API gateway in shared or bank environments; this token is a minimal initial control and is not a substitute for per-user RBAC and audit trails.

Examples (Windows `curl.exe`):

```cmd
curl.exe -X POST "http://localhost:9090/api/v1/glossary/import?status=CANDIDATE" ^
  -H "X-Glossary-Admin-Key: %SCHEMAFORGE_GLOSSARY_ADMIN_KEY%" ^
  -F "file=@D:\Data\EcoBank_Reference_Terminology_v0.1_2026-10-06_v67_Atomic_Candidates.xlsx"

curl.exe "http://localhost:9090/api/v1/glossary/terms?q=ACCOUNT&size=100"

curl.exe -X POST "http://localhost:9090/api/v1/glossary/bulk-status?kind=ATOMIC&from=CANDIDATE&to=APPROVED" ^
  -H "X-Glossary-Admin-Key: %SCHEMAFORGE_GLOSSARY_ADMIN_KEY%"
```

For individual record creation/editing, supply a JSON body such as:

```json
{
  "english": "ACCOUNT",
  "persian": "حساب",
  "module": "Core Banking",
  "kind": "ATOMIC",
  "status": "APPROVED"
}
```

Entry `kind` must be `ATOMIC` (one component) or `PHRASE` (one or more components). Entry `status` must be `CANDIDATE`, `APPROVED`, or `REJECTED`. The same normalized key may occur in different kinds. Duplicate `(kind, key)` creation returns HTTP 409. Import never overwrites previously curated/manual entries. Soft deleted entries can be reactivated by POST using the same kind/key.

## Name analysis and reports

- The scanner derives tokens from `SNAKE_CASE`, `PascalCase`, `camelCase`, uppercase abbreviations, or symbols (example: `CustomerAccountID` -> `CUSTOMER`, `ACCOUNT`, `ID`). Numeric-only components are ignored. It **does not invent segment boundaries in dense abbreviations** such as `CTFPRPSLTYP`; that identifier appears as one token for review unless explicitly curated.
- The scanner checks approved atomic tokens. Its existing longest-match mechanism also handles the **four multiword concepts present on `01_Atomic_Terms`** (and explicitly approved manually curated phrases). Imported phrases from `02_Canonical_Phrases` have no authority to suppress unknown-token findings.
- Entries with status `CANDIDATE` are `PENDING_APPROVAL`, not `NEW`. Rejected entries are `REJECTED`. Identifiers absent from any glossary entry are `NEW`. None block DDL.
- `summary.json` records `referenceWorksheet=01_Atomic_Terms` for traceability.
- `unknown-terms.csv` includes source name, TABLE/COLUMN, table, column, original identifier, normalized term, token position and review status **per occurrence**. `summary.json` includes total checked identifiers, review occurrences, unique new/pending/rejected terms, approved term counts and a deterministic SHA-256 fingerprint of the approved vocabulary used in that run.
- If the reference workbook is imported without approval, known baseline terms appear as `PENDING_APPROVAL` rather than being incorrectly reported as newly discovered.
- No observations are silently added to the curated glossary: approval remains a separate governance action.

## Upgrading a V4.14 H2 glossary

The initial V4.14 prototype could import both worksheets, labelling all imported rows `source=ECOBANK_XLSX`. In V4.14.1, **legacy `PHRASE` rows with this ambiguous source are excluded** from active search, CSV export, batch approval and naming validation. Prior `ATOMIC` rows remain active because they originated solely in `01_Atomic_Terms` under the old import semantics. Legacy rows are not destroyed.

Re-import the original Excel through `POST /import` once with the updated V4.14.1 application. Exactly the normalized terms appearing in `01_Atomic_Terms` are re-labelled `source=ECOBANK_ATOMIC_XLSX` when already present, retaining their status, translations and any manual corrections. All other old phrase rows remain persisted but excluded. This avoids data loss and prevents the old 52,128-row second worksheet from affecting checks. For a new DB, precisely 804 distinct reference terms are imported as CANDIDATE by startup seeding.

`GET /terms/{id}` may still retrieve an old out-of-scope row by its known identifier for controlled review; default listing/export intentionally hides these records. Do not discard an existing H2 file unless a backup has been made and its curated entries are no longer needed.

## Verification

```cmd
mvnw.cmd clean -Dtest=GlossaryScannerTest,GlossaryH2AndReportTest,GlossaryRestControllerTest,GlossaryStartupWiringTest,GlossaryAtomicBootstrapTest test
mvnw.cmd clean test
```

The original project baseline uses Java 21, Spring Boot 3.5.0, and the Spring-managed H2 runtime dependency. In environments where Maven/Internet cannot be reached, isolated lexical code can be compiled with Java 21, but Maven, Spring/H2, and full regression acceptance gates must still run on the deployment machine.

## Limitations / next iteration

H2 is appropriate for a local PoC, single-node glossary editor, or small controlled deployment; for clustered/multi-writer production, replace it with a managed shared database plus Flyway/Liquibase migrations, automated backups, SSO/RBAC, steward identities, immutable audit log, approval workflows and glossary version pinning. Existing CLI-only `SchemaGenerationService` (the offline direct JSON+SQL path) is not wired to the REST package reporter in this iteration; the supported paths are the versioned ZIP-generation APIs. The glossary process does not automatically split legacy compressed column codes or learn abbreviations without reviewed entries.

## V4.14.2 HF4 H2 paged search correction

The H2 active Glossary `/terms` pagination now uses documented SQL-standard
`OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` rather than the former bound
`LIMIT ? OFFSET ?` syntax. The 2026-10-19 targeted test run counted 4
Atomic-worksheet PHRASE rows but returned no items on the first page;
HF4 regression tests now cover row retrieval, two-page iteration, filtered
results and literal underscore search. No glossary data migration, reimport,
or schema change is required. See `docs/GLOSSARY_HF4_PAGINATION_REPAIR.md`.
