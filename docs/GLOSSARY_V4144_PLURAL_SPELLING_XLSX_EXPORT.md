# SchemaForge V4.14.4 — Glossary plural policy, spelling advisory and XLSX export

Baseline: user-provided source ZIP `schema-forge-v4-2026-10-20-1022.zip` (SchemaForge 4.0.1). No alteration to the source H2 database or embedded Atomic Terms seed. The source ZIP included `data/schemaforge-glossary.mv.db`; **both delivery archives intentionally exclude that persistent database file**, so extracting the new code cannot overwrite it.

## 1. Decision policy for a newly extracted term

1. Normalize the identifier and resolve approved multiword phrases as before.
2. Exact `ATOMIC`/`PHRASE` lookup: report APPROVED/`PENDING_APPROVAL`/`REJECTED` according to the existing lifecycle.
3. If an atomic word is absent, **look up a conservative singular candidate in all non-deleted glossary statuses**. Recognized forms include `PROFILES -> PROFILE`, `CITIES -> CITY`, `CLASSES -> CLASS`, and `HOUSES -> HOUSE`. Recognition requires a singular *already present in the glossary*: suffix deletion alone cannot validate English grammar. No independent `NEW` or CANDIDATE plural entry is created. Existing plural records are not automatically deleted or merged.
4. TABLE: plural is permissible. For a candidate/rejected singular, the singular still requires review; approved singulars require no glossary review.
5. COLUMN: a recognized plural emits `PLURAL_COLUMN` in the Glossary report and a safe, non-executable `GLOSSARY_HINT: PLURAL_COLUMN=...` inline SQL comment. Existing canonical naming validation continues to signal suspicious column plural components independently. No column is renamed.
6. A `NEW` atomic token can be checked via the read-only advisory endpoint. Fuzzy matching uses optimal-string-alignment distance (one edit for short tokens, up to two for longer tokens, including adjacent transposition); only already-known non-REJECTED atomic terms are offered. An advisor cannot prove whether a new token is actually misspelled, and no correction is automatically committed.
7. Manual steward decision: validate against the source Word/EA, approve a new business term, reject a typo with a reason describing the intended correction, or explicitly edit/merge according to governance. `POST /review/import-report` still requires authorization and is a **write**; no automatic import is introduced in this release.

Examples: `CUTOMER` may suggest `CUSTOMER` if that term already exists; `COMPELETE` suggests `COMPLETE` only when that term is present. A genuinely new brand, business term or acronym might resemble another word and must **not** be automatically rejected. A `NEW` with no near match is *not proof* of correct spelling. Optional LanguageTool verification remains a separate external spelling-control path.

## 2. API: all current Glossary entries as XLSX

```
GET http://localhost:9090/api/v1/glossary/export.xlsx
```

Available only while `SCHEMAFORGE_GLOSSARY_ENABLED=true` in the running server. This is **read-only** and uses no admin key, consistent with existing public `GET /api/v1/glossary/export.csv`.

HTTP 200, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename="schemaforge-glossary.xlsx"`, `Cache-Control: no-store`.

Workbook: `Overview` summary and `Glossary` data, columns:

`ID | Kind | Key | English | Persian | Module | Status | Source | Created At | Updated At`

Scope exactly matches existing `export.csv`: all **non-deleted and effective** `ATOMIC`/`PHRASE` entries, including every `CANDIDATE`, `APPROVED`, and `REJECTED` status, regardless of pagination. Soft-deleted rows and historic inactive `ECOBANK_XLSX` phrases are intentionally excluded. For a forensic *database backup*, back up the H2 database separately; this XLSX is a review/export snapshot, not an H2 backup and not the `01_Atomic_Terms` import template. No XLSX formulas are generated from user-controlled cell content.

## 3. API: conservative spelling review

```
GET http://localhost:9090/api/v1/glossary/spelling-suggestions?term=CUTOMER&objectType=COLUMN&limit=5
```

`objectType`: `TABLE` or `COLUMN` (default `COLUMN`), `limit`: 1..10 (default 5). Accepts exactly one atomic token (<=64 characters). The JSON contains `term`, `classification`, `canonicalSingular` and `canonicalStatus` where applicable, a ranked array of `suggestions` (`id`, `key`, `persian`, `status`, `distance`), and `automaticChanges: false`.

`classification` possibilities include `KNOWN`, `KNOWN_PLURAL`, `PLURAL_COLUMN_VIOLATION`, `PENDING_APPROVAL`, `REJECTED`, `POSSIBLE_TYPO`, `NEW`. The advisor may return no suggestions. This endpoint does **not** call external services, write H2, insert candidates, or change canonical data/DDL.

## 4. Generated reports and older import CSV files

`reports/glossary/unknown-terms.csv` retains its eight original columns; `review_status` additionally supports `PLURAL_COLUMN`. The CSV parser explicitly skips this status in candidate Import. `reports/glossary/summary.json` adds `pluralColumnViolations`, `uniquePluralColumnTerms` and `pluralColumnTerms`. The standalone six-sheet Word audit retains all six sheets; `Plural Review Hints` now shows `PLURAL_COLUMN` violations and matching singulars rather than treating them as new terms. Its `summary.json` adds `pluralColumnViolations`.

Legacy CSV reports generated before this change may contain `NEW` for `PROFILES`. The import service now checks already-known singulars before inserting or recording such rows, and reports a new counter `skippedKnownPluralOccurrences` rather than inserting a duplicate plural candidate. The existing review import counters retain their meanings.

## 5. Runtime configuration and safe acceptance

Use a **separate, unused H2 path** for new tests. Do not overwrite an active `.mv.db` file or attempt to change the H2 driver version without backup.

```cmd
cd /d D:\Projects\schema-forge-v4
set "SCHEMAFORGE_GLOSSARY_ENABLED=true"
set "SCHEMAFORGE_GLOSSARY_DATABASE_PATH=D:\Projects\schema-forge-v4\data\schemaforge-glossary-test"
set "SCHEMAFORGE_GLOSSARY_SEED_ON_STARTUP=true"
mvnw.cmd spring-boot:run
```

In another CMD:

```cmd
curl.exe -f -o "D:\Projects\schema-forge-v4\glossary-export.xlsx" "http://localhost:9090/api/v1/glossary/export.xlsx"
curl.exe -i "http://localhost:9090/api/v1/glossary/spelling-suggestions?term=CUTOMER&objectType=COLUMN"
curl.exe -i "http://localhost:9090/api/v1/glossary/spelling-suggestions?term=PROFILES&objectType=TABLE"
```

Expect a valid two-sheet XLSX with all active seed terms, and advisory-only JSON responses. Seed terms begin in CANDIDATE, so `KNOWN_PLURAL` is only returned if the singular was explicitly APPROVED; otherwise `PENDING_APPROVAL` with `canonicalSingular` is correct. If the server returns 404, first check that the environment variable was set **before the process launched**, and verify the running process/version on port 9090. These GET requests never edit H2.

Suggested Windows tests:

```cmd
mvnw.cmd clean -Dtest=GlossaryPluralNamingPolicyTest,GlossaryXlsxExportAndSpellingTest,GlossaryReviewWorkflowTest,GlossaryScannerTest,GlossaryDdlHintsTest,GlossaryStandardWordZipAuditTest,GlossaryRestControllerTest test
mvnw.cmd clean test
```

## 6. Explicit open items

- Persisting all newly discovered terms automatically on every Word/EA request is **not** enabled. The current write remains explicit and administrator-authorized via `POST /review/import-report`, and spelling suggestions must be reviewed before decisions.
- Inflection is conservative regular English plural detection, **not** a full linguistic dictionary; ambiguous words, irregular plurals, product names and acronyms need manual governance.
- Live Spring Boot + H2 integration, Maven regression and real external Word ZIP audit must be executed on the user's Windows machine; these are not asserted from a local static source review.
