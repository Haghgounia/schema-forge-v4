# Glossary default-on baseline — V0.5.5

**Decision (2026-10-24):** SchemaForge Glossary shall be **ENABLED BY DEFAULT** for
all future normal REST and shipped Distribution runtimes. Opting out must be explicit:
`SCHEMAFORGE_GLOSSARY_ENABLED=false` / `schemaforge.glossary.enabled=false`.
This is independent of the seven live DBMS metadata connection defaults.

## Effective configuration

| Setting | Default | Meaning |
|---|---|---|
| `SCHEMAFORGE_GLOSSARY_ENABLED` | `true` | Exposes Glossary REST/Review and report-writer beans |
| `SCHEMAFORGE_GLOSSARY_DATABASE_PATH` | `./data/schemaforge-glossary` | Persistent, separate H2 database; do not overwrite user files |
| `SCHEMAFORGE_GLOSSARY_SEED_ON_STARTUP` | `true` (unchanged) | Idempotent import of missing **baseline CANDIDATE** terms; does not rewrite existing review decisions |
| `SCHEMAFORGE_GLOSSARY_CANDIDATE_REGISTRATION_MODE` | `DRY_RUN` (unchanged) | Classifies/report new terms only; never auto-registers newly discovered terms |
| `SCHEMAFORGE_GLOSSARY_ADMIN_KEY` | empty | Read-only REST available; admin mutation requires configured secret |

These defaults are implemented in `src/main/resources/application.yml` and the
**external** `distribution/config/application.yml` and `application-example.yml`.
`@ConditionalOnProperty(matchIfMissing=true, havingValue="true")` on all three
Glossary components protects the default even when external config omits the flag.
Explicit `false` disables those beans and removes Glossary reports from subsequently
generated output ZIPs. It does not delete or modify H2 files.

## Runtime and operational safety

1. Back up `data/schemaforge-glossary.mv.db` **with H2 fully stopped** before adopting a
   new release. Reuse the same configured H2 path for existing review decisions;
   do not silently switch to an empty database.
2. New installations may create a new H2 database and seed missing Atomic Terms as
   **CANDIDATE** (never `APPROVED`). If bootstrap writes are undesirable, set
   `SCHEMAFORGE_GLOSSARY_SEED_ON_STARTUP=false` explicitly.
3. `DRY_RUN` blocks new **automatic** candidate registration; it does not disable
   baseline bootstrap. An explicit authorized Steward import may still write H2.
4. If the H2 file is locked, missing write permission or missing baseline resource,
   startup may fail rather than silently providing a partially enabled Glossary.
5. Activation adds read-only Glossary scanning, optional SQL comments, and
   `reports/glossary/*` into **new** generated ZIPs. Previously generated ZIPs remain
   unchanged. If exact historic artifact equality is being tested, opt out in that
   specific test/run, never globally in production by accident.

## Acceptance gates

| Gate | Required verification | Status |
|---|---|---|
| GDO-1 | Embedded and both distribution YAMLs default enabled | Static contract check: PASS |
| GDO-2 | `GlossaryConfiguration`, `GlossaryController`, `GlossaryReviewController` enabled even when property absent | Source contract check: PASS; Maven startup: OPEN |
| GDO-3 | `SCHEMAFORGE_GLOSSARY_ENABLED=false` leaves no Glossary data source/REST/report-writer | Source contract check: PASS; Maven integration: OPEN |
| GDO-4 | Candidate registration still defaults to `DRY_RUN`; baseline startup seed remains idempotent | Static check: PASS; H2 smoke: OPEN |
| GDO-5 | Default runtime startup isolates H2 in `target`; old EA/Word comparison ITs opt out explicitly | Test coverage authored; Windows Maven: OPEN |
| GDO-6 | Run Spring Boot without Glossary env variable, `GET /api/v1/glossary/terms?page=0&size=1` -> HTTP 200 | Windows integration: OPEN |
| GDO-7 | Generate small Word/EA ZIP; `reports/glossary/summary.json` and `unknown-terms.csv` appear | Windows E2E: OPEN |
| GDO-8 | All baseline Maven tests and artifact outputs without Glossary regressions | Windows full test: OPEN |

This is a product-wide default policy; it does **not** claim Glossary P1–P5
Governance acceptance is complete. No DDL parser, runtime metadata resolver,
REST payload schema, React component, or stored H2 file is altered in this patch.
