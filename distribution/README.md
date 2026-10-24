# SchemaForge V4 Runtime Distribution Staging

This directory defines the source-controlled layout for the SchemaForge V4 4.0.1 runtime distribution.

The final deliverable is assembled in Phase 19.3 as `schemaforge-v4-4.0.1-distribution.zip`.
Production Java sources are not included in the runtime distribution.

Layout:

- `bin/` - validated GA executable JAR (inserted during final assembly)
- `config/` - safe external runtime configuration (all database metadata access disabled by default)
- `scripts/` - Windows runtime and integrity helpers
- `docs/` - deployment/operations documents completed in Phase 19.2
- `samples/` - runtime usage samples completed in Phase 19.2
- `checksums/` - immutable GA binary checksum

The runtime start script deliberately uses `spring.config.location` so the distribution runs from `config/application.yml` rather than the development defaults embedded in the executable JAR. The shipped configuration contains no database passwords and keeps every live metadata repository disabled until explicitly enabled with environment variables.

Glossary is an independent **H2-backed service enabled by default** in both external
`config/application.yml` and `config/application-example.yml`. It does not enable
Oracle/PostgreSQL/other live metadata connections. The default file is
`./data/schemaforge-glossary.mv.db`; configure a writable, backed-up path with
`SCHEMAFORGE_GLOSSARY_DATABASE_PATH`. Startup may seed missing baseline candidates
when `SCHEMAFORGE_GLOSSARY_SEED_ON_STARTUP=true` (default). Newly discovered terms
are **not inserted** under the default `DRY_RUN` registration mode. Explicitly set
`SCHEMAFORGE_GLOSSARY_ENABLED=false` to run without the service or its reports.

### Release-confidence external corpus gate v3

The test source now includes a v3 external corpus harness for Standard Word ZIP, Legacy Word ZIP, Legacy RTL Word ZIP, and EA XML/XMI directories. Observation mode (`schemaforge.e2e.failOnErrors=false`) records all issues without failing Maven; strict mode aggregates failures after full corpus processing. EA outputs use collision-safe deterministic names and CSV summaries include input/output plus exception class/message evidence.

### Word release-confidence external corpus gate v7

The test harness now accepts the real Word corpus layout: Standard ZIP, recursive Legacy Word directory, and Legacy RTL ZIP. Legacy directory Word files are classified by the production legacy extraction rules before generation; non-table Word documents are reported as `SKIPPED_NOT_TABLE_SPEC` instead of being forced through table generation. Accepted table documents retain their recursive relative paths and are exercised through the existing strict Legacy ZIP batch pipeline. Production runtime behavior is unchanged. Apache POI ZIP-bomb inflate-ratio rejections are now surfaced by the release-confidence harness as `SKIPPED_SECURITY_POLICY`; the security threshold is never weakened or overridden.
## v8 targeted Legacy replay

For a completed v7 corpus run, use `LegacyWordProblemReplayIT` with `schemaforge.e2e.legacyReplaySummary` pointing to the v7 `legacy-directory-document-summary.csv`. The targeted gate reopens only prior `CLASSIFICATION_FAILED` documents and does not rerun Standard Word, Legacy RTL, or Legacy batch generation.
