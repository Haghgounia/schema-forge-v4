# SchemaForge V4.14.5 P1/P2 — Automatic Glossary Candidate Registration (2026-10-21)

## Status / boundary

Implementation candidate ready for Windows Maven/integration acceptance, **NOT YET production-accepted**.
Baseline is EA Offline SpellCheck V1 with EA Performance Profiling V1 and Glossary V4.14.4-HF1. This change adds no target-DBMS modification and no H2 DDL migration. The customer's existing `data/schemaforge-glossary.mv.db` is excluded from delivery archives. All Windows tests must use a **separate isolated H2 database path** first.

The central Glossary writer already runs **once per generation** from the EA, Standard Word, Legacy Word, and Batch/ZIP paths. It runs after DDL generation, before the artifact manifest; the new registrar is attached there, avoiding per-DBMS term lookups or writes. The existing generated `unknown-terms.csv` and `summary.json` remain the only two glossary report files, and their manifest types/locations are unchanged. The JSON summary conditionally gains a `candidateRegistration` object. Generation behavior when Glossary is disabled is unchanged.

## Configuration

`src/main/resources/application.yml`:

```yaml
schemaforge:
  glossary:
    enabled: ${SCHEMAFORGE_GLOSSARY_ENABLED:false}
    database-path: ${SCHEMAFORGE_GLOSSARY_DATABASE_PATH:./data/schemaforge-glossary}
    candidate-registration:
      mode: ${SCHEMAFORGE_GLOSSARY_CANDIDATE_REGISTRATION_MODE:DRY_RUN}
```

- `OFF`: skip candidate registration and classification; standard legacy Glossary CSV/JSON remain intact.
- **`DRY_RUN` (default)**: classify unique NEW words and add results to the `candidateRegistration` JSON object. Read-only; **no candidate insert, audit event, or evidence-queue insert**. Safe with the real Glossary enabled.
- `AUTO_CANDIDATE`: same classification, then insert **only eligible canonical ATOMIC terms** with `CANDIDATE` status. **Explicit opt-in only**, and only when the existing Glossary itself is enabled. Never approves/rejects, updates metadata, fixes spelling, alters identifiers, or contacts LanguageTool.
- Invalid mode fails startup with an explanation. Normal `SCHEMAFORGE_SPELL_CHECK_ENABLED=false` does not bypass the independent offline lexical evidence requirement for automated registration.

## Conservative classification policy

Given one request-wide snapshot of the six approved/candidate/rejected sets, use the existing `GlossaryScanner` longest-phrase and known-plural rules. Gather each unique occurrence with reviewStatus `NEW` exactly once for classification. For each word:

1. Existing `APPROVED`, `CANDIDATE`, `REJECTED` and recognized plural-with-known-singular are **not NEW** and cannot create another record.
2. Non-ASCII / mixed digits / short tokens (fewer than 4) / words longer than 32 characters require manual review. No guessing about bank acronyms or codes.
3. Even if a singular is not in the Glossary, a clear English plural such as `SCHOOLS` with recognized singular `SCHOOL` is held for singular review; **no new plural atomic entry** is created.
4. An offline Hunspell-dictionary-derived **positive lookup** for the exact word makes it *eligible*, not APPROVED. This is stricter than relying on an empty warning list: the current spelling detector intentionally omits suggestions for distant unknown words.
5. Unknown words with suggestions from the offline engine, or a close typo match to an existing glossary key, are `suspectedTypos` and **never automatically inserted**.
6. Other unknown words, including specialist banking jargon absent from the dictionary, are `manualReviewTerms`. They remain in the existing CSV (where a Steward can use the existing authenticated review-import endpoint). Manual review prevents silently adding a typo that the lexical checker could not detect.

Only the governed Glossary controls the `APPROVED` state. English dictionary membership does not authorize database object naming, override plural-column violations, or generate any Persian translation.

## H2 safety, review history and idempotence

- System-only `GlossaryReviewService.registerAutoCandidates()` runs the eligible inserts, evidence records and immutable events in **one database transaction**, with bounded JDBC batches (256 items). A failure rolls back **all** of those writes.
- Before writing it checks every existing ATOMIC key, **including soft-deleted ones**, to avoid resurrection, changes to REJECTED/APPROVED entries, or duplicated CANDIDATE terms. The existing H2 unique `(kind,term_key)` constraint remains the final defense against concurrent writers.
- Existing `glossary_review_occurrence` records link the `unknown-terms.csv` SHA-256, ordinal row, source document, table name, column name and identifier; exact report reruns do not re-record evidence. Existing `glossary_review_event` records use event type `AUTO_CANDIDATE`, a non-secret generation identifier in the reason, and the same report SHA-256. Inserted source is `AUTO_DISCOVERY`; Persian/Module stay blank.
- `GlossaryReviewService.queue`, `history`, and `decision` continue working for the candidates without adding an admin-bypassing HTTP endpoint. Only the existing Steward decision API can transition CANDIDATE to APPROVED/REJECTED.
- Reports and generated DDL use the **pre-registration** Glossary snapshot for that request, so registering candidates cannot change the current run's Canonical Model or SQL. On subsequent runs, a `MISSING` diagnostic comment might disappear when the word has become CANDIDATE; executable DDL semantics remain unchanged. AUTO may commit candidate rows before later manifest/ZIP packaging succeeds; reruns are idempotent.
- No H2 `.mv.db`, `.lock.db`, `.trace.db`, build `target/` or customer data file is included in the source archives.

## Reports (added to existing `reports/glossary/summary.json`)

```json
{
  "candidateRegistration": {
    "mode": "DRY_RUN",
    "uniqueNewTerms": 3,
    "eligibleCandidates": 1,
    "possibleTypos": 1,
    "requiresManualReview": 1,
    "insertedCandidates": 0,
    "alreadyKnown": 0,
    "evidenceOccurrencesRecorded": 0,
    "eligibleTerms": ["SCHOOL"],
    "suspectedTypos": ["CUTOMER"],
    "manualReviewTerms": ["ZXQWV"]
  }
}
```

`unknown-terms.csv` keeps its existing header and all NEW/PENDING_APPROVAL/REJECTED/PLURAL_COLUMN statuses. In AUTO mode it is intentionally a **before-write evidence snapshot**, not a live database snapshot.

## Tests and acceptance on Windows

First, overlay the Delta onto the already accepted *EA Offline SpellCheck V1* installation (or use Full). The first run must be `DRY_RUN` against isolated H2:

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=GlossaryAutomaticCandidateRegistrationTest,GlossaryH2AndReportTest,GlossaryReviewWorkflowTest,GlossaryScannerTest,GlossaryPluralNamingPolicyTest,OfflineHunspellSpellCheckServiceTest ^
  test
```

Then in a **fresh** CMD to avoid inherited settings:

```cmd
cd /d D:\Projects\schema-forge-v4
set "SCHEMAFORGE_GLOSSARY_ENABLED=true"
set "SCHEMAFORGE_GLOSSARY_DATABASE_PATH=D:\Projects\schema-forge-v4\data\schemaforge-glossary-v4145-acceptance"
set "SCHEMAFORGE_GLOSSARY_CANDIDATE_REGISTRATION_MODE=DRY_RUN"
set "SCHEMAFORGE_SPELL_CHECK_ENABLED=true"
set "SCHEMAFORGE_SPELL_CHECK_PROVIDER=OFFLINE"
set "SCHEMAFORGE_EA_PERF_ENABLED=true"
if not exist logs\glossary mkdir logs\glossary
mvnw.cmd spring-boot:run > logs\glossary\v4145-dry-run.log 2>&1
```

Upload `Deposit_Account_Operations_6_RC_2026-09-22.xml` with `platform=all` in Swagger. Inspect `reports/glossary/summary.json` inside the generated ZIP: `candidateRegistration.mode=DRY_RUN`, `insertedCandidates=0`, and populated eligible/suspected/manual lists where applicable. Verify baseline Glossary total remains unchanged by DRY_RUN. Retain the ZIP and logs.

Only after DRY_RUN verification, stop the server and **explicitly opt in** to AUTO with the **same isolated acceptance H2**:

```cmd
set "SCHEMAFORGE_GLOSSARY_CANDIDATE_REGISTRATION_MODE=AUTO_CANDIDATE"
if not exist logs\glossary mkdir logs\glossary
mvnw.cmd spring-boot:run > logs\glossary\v4145-auto.log 2>&1
```

Process the **same** input once and inspect `candidateRegistration.insertedCandidates`, the Glossary review queue/history, and `GET /api/v1/glossary/export.xlsx`. Process the same input a second time: the inserted count must be 0, existing reviewed/deleted rows must remain unchanged, and executable DDL (ignoring comments/filenames/headers) must match DRY_RUN. Confirm no `CUTOMER`, `TRANSACTOIN`, or new plural candidates have been automatically inserted.

After the targeted gate succeeds, run the full regression separately: `mvnw.cmd clean test`. **No claim of a successful Windows Maven build or API integration is made by this implementation delivery**. Acceptance is subject to these results.

## Out of scope / next phases

- P3: dedicated filtering and steward triage UX for system-proposed and manually reviewed specialized vocabulary; explicit override and reason for words not in the English dictionary. Existing queue/history/decision APIs already work for inserted CANDIDATE records.
- P4: richer shared terminology interface and refreshable H2 lexicon across Word/EA/Batches; current integration is through existing shared report generation.
- P5: full regression, concurrency stress and corpus precision/recall metrics; any additional performance work must not introduce HTTP calls on the spelling path.
- EA Metadata latency in Oracle and PostgreSQL remains its own performance roadmap item.
