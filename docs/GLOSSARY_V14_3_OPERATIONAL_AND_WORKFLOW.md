# SchemaForge V4.14.3 — Glossary operational acceptance and steward review workflow

## Scope and baseline

This is an additive update to the **HF9 / H2 2.4.240** regression baseline.
Only `01_Atomic_Terms` is the authoritative Excel worksheet. The second
`02_Canonical_Phrases` worksheet remains out of scope. The embedded Atomic
baseline still imports 946 source rows into 804 unique entries (800 `ATOMIC`,
4 `PHRASE`) as `CANDIDATE`. None are automatically approved.

This release adds **a unified candidate review queue**, including the 804 initial
Atomic-sheet candidates, plus a report-driven discovery workflow, without changing existing
Word / Legacy Word / EA rendering, DDL generation, artifact ledger/manifest,
or the glossary term REST and startup contracts.

## Operational acceptance (Windows)

### Offline — safe for existing user data

```cmd
cd /d D:\Projects\schema-forge-v4
call distribution\bin\accept-glossary.cmd
mvnw.cmd clean test
```

The acceptance test creates a **separate temporary on-disk H2** database and
verifies the packaged Excel baseline, 800/4 classification, restart persistence,
re-import idempotency, and preservation of manual translations and approvals.
The review tests generate **real glossary report CSV and summary** inside a
manifest-tracked artifact package, then verify review intake, idempotence,
classification, status decisions, immutable history, and reopen persistence.
The additional `GlossaryRealSourceAcceptanceTest` exercises production Word/Legacy generation and actual EA/XMI parsing
from bundled **real Word, Legacy Word and EA XML** fixtures, validates
manifest-tracked glossary artifacts, and imports their generated reports into
the isolated review queue.
They never delete or modify `data/schemaforge-glossary.mv.db`.

### Live service — read-only

1. Back up the production H2 `.mv.db` while the service is stopped (consistent
   offline copy); **never copy an in-use database as an online backup**.
2. Set `SCHEMAFORGE_GLOSSARY_ENABLED=true` in the same process environment that
   launches SchemaForge and start/restart SchemaForge on port 9090.
3. `http://localhost:9090/swagger-ui/index.html` should contain the
   `Glossary Governance` tag.
4. Run:

```cmd
powershell.exe -NoProfile -ExecutionPolicy Bypass -File distribution\bin\check-glossary-live.ps1
```

This script makes **GET-only** requests to `/v3/api-docs` and three glossary
listing/search endpoints. By default it asserts 804 visible initial entries,
4 initial phrases, and at least one matching ACCOUNT. If intentional curation
has soft-deleted terms, adjust `-MinimumTerms 0`; manually verify the deleted
terms. This is not a destructive cleanup or an automatic data migration.

To run the isolated acceptance suite and then the live check in one go:

```cmd
call distribution\bin\accept-glossary.cmd --live
```

### H2 version and workbook rules

- `com.h2database:h2:2.4.240` is required; do not revert to H2 `2.3.232`.
- Existing `data/schemaforge-glossary.mv.db` is preserved; no reset is required.
- A failed live check is **not** proof of a production data defect; verify the
  process environment, port, and intentional soft deletions before altering data.

## New steward REST API

The following endpoints appear under the existing `Glossary Governance` Swagger
tag **only when glossary is enabled**. All review endpoints require
`X-Glossary-Admin-Key` matching `SCHEMAFORGE_GLOSSARY_ADMIN_KEY` (must be set
in the running process).

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/v1/glossary/review/import-report` | Multipart `file`: **generated** `reports/glossary/unknown-terms.csv`. Validated before any DB write; imports only `NEW` atomic occurrences. |
| GET | `/api/v1/glossary/review/queue?page=0&size=100` | All pending Atomic baseline/manual/discovered candidates, occurrence counts and source. |
| GET | `/api/v1/glossary/review/queue?includeResolved=true` | Include approved, rejected and removed evidence. |
| POST | `/api/v1/glossary/review/decision` | Explicit approve/reject, with reason and optional Persian/module labels. |
| GET | `/api/v1/glossary/review/history?term=XEXAMPLE` | Audited discovery and steward decisions; most recent first. |

Sample decision JSON:

```json
{
  "term": "XEXAMPLE",
  "kind": "ATOMIC",
  "to": "APPROVED",
  "persian": "نمونه",
  "module": "Customer",
  "reason": "Terminology reviewed with the business owner"
}
```

For the four multiword entries present in `01_Atomic_Terms`, use
`"kind":"PHRASE"` (or omit `kind` to infer it from the underscore in the key).
Use `"to":"REJECTED"` for a rejected term and always supply an explicit
nonblank reason. The service **never auto-approves** discoveries. Each decision
requires an active `CANDIDATE` (initial Atomic baseline, manually created, or discovered); stale/duplicate decisions fail with
HTTP 409. Attempting to review an unknown term fails with 404.

### Intake semantics

- Bounded strict UTF-8 CSV; 5 MB maximum and 20,000 data rows maximum.
- Exact eight-column SchemaForge glossary report header and column order;
  status must be one of `NEW`, `PENDING_APPROVAL`, or `REJECTED`.
- `NEW` is accepted **only as one canonical atomic identifier token**;
  every token must match `identifier` and `token_position`. This prevents
  untrusted CSV contents from silently creating arbitrary approved phrases.
- `PENDING_APPROVAL` and `REJECTED` findings are not re-entered as candidates.
- A SHA-256 digest of the CSV and row index provides idempotent evidence. Repeat
  upload of the same file does not inflate the queue or overwrite decisions.
- If a term already exists, **including soft-deleted terms**, its original
  status, translation and deletion are never changed by discovery intake.
- Every newly discovered unique term is inserted into the existing
  `glossary_term` table with source `DISCOVERY_REPORT` and status `CANDIDATE`.
  Existing `APPROVED` terms remain approved and `REJECTED` terms stay rejected.
- The H2 file also holds `glossary_review_occurrence` and
  `glossary_review_event` tables. Events are inserted transactionally with
  review decisions. No administrator credential is stored.
- Manifest fail-closed integrity and `reports/glossary/unknown-terms.csv`
  output are **unchanged**; intake is a separate, explicit administrator action.

### Baseline candidate stewardship

`GET /api/v1/glossary/review/queue` lists the initial 804 Atomic-sheet
`CANDIDATE` records (unless manually approved, rejected, or soft-deleted)
as well as subsequently discovered candidates. Baseline entries have
`occurrences=0` and show their `ECOBANK_ATOMIC_XLSX` provenance. Any individual
candidate, including the four Atomic-sheet PHRASE entries, may be approved/rejected through `/review/decision` with a reason;
no automatic bulk approval takes place. Existing `/bulk-status` remains
available as a separate legacy administrative operation.

### Audit scope and known limits

History records **this steward workflow**: first discovered candidates and
explicit approve/reject decisions for baseline, manual and discovered terms,
with timestamps and reasons.
Pre-existing direct `/api/v1/glossary/terms`, `/bulk-status` and XLSX imports
are still supported but **are not fully represented in this new review-event
history**. Full cross-API audit and role-based per-user attribution require
separate governance hardening; one static administrator key has no human user
identity. Avoid running `/bulk-status` without an approved external review
process. This release does not add automatic scheduled scanning of every
artifact, nor a direct database conformance Glossary integration.

## Acceptance gates

1. Isolated real H2 bootstrap and restart checks must pass.
2. Report-driven steward workflow end-to-end checks must pass, including
   security, corrupt CSV rejection, preserved curated data, and audit history.
3. Spring context must register both Glossary controllers when enabled.
4. Live Swagger and REST read-only smoke check must pass in the deployment.
5. Complete `mvnw.cmd clean test` must remain green. DBMS live tests skipped
   by design must be interpreted separately.
