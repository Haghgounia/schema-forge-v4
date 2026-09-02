# SchemaForge V4 Runtime Distribution Staging

This directory defines the source-controlled layout for the SchemaForge V4 4.0.0 runtime distribution.

The final deliverable is assembled in Phase 19.3 as `schemaforge-v4-4.0.0-distribution.zip`.
Production Java sources are not included in the runtime distribution.

Layout:

- `bin/` - validated GA executable JAR (inserted during final assembly)
- `config/` - safe external runtime configuration (all database metadata access disabled by default)
- `scripts/` - Windows runtime and integrity helpers
- `docs/` - deployment/operations documents completed in Phase 19.2
- `samples/` - runtime usage samples completed in Phase 19.2
- `checksums/` - immutable GA binary checksum

The runtime start script deliberately uses `spring.config.location` so the distribution runs from `config/application.yml` rather than the development defaults embedded in the executable JAR. The shipped configuration contains no database passwords and keeps every live metadata repository disabled until explicitly enabled with environment variables.
