# Local H2 Glossary Data Directory

When `SCHEMAFORGE_GLOSSARY_ENABLED=true`, the application creates the persistent database
at `data/schemaforge-glossary.mv.db` (relative to the project working directory).
The original Atomic Terms sheet is packaged at `src/main/resources/glossary/01_Atomic_Terms.xlsx`
and imports automatically as **CANDIDATE** at every startup (idempotent).

`*.mv.db` / `*.trace.db` are intentionally excluded from Git. Never commit live user changes
or bundle a running database in a ZIP; stop SchemaForge before copying/backing up the database.
For an alternative offline bootstrap, see `distribution/glossary/bootstrap-atomic-h2.sql`.
