
## 2026-07-25 - Final script metadata validation
- Added schema existence validation for Oracle and PostgreSQL.
- Added table discovery across schemas.
- Added foreign-key referenced-schema resolution and missing/ambiguous table hints.
- Added singular column-name component validation with S-ending exceptions.
- Added SQL compact markers and JSON validation issues for all new checks.

## 2026-07-26 - Final FK grammar and table-name validation
- Parsed FK references in the forms `TABLE/Y`, `TABLE/N`, `SCHEMA.TABLE/Y`, and `SCHEMA.TABLE/N`.
- Preserved physical/logical (`Y/N`) and explicitly qualified schema information in JSON.
- Generated FOREIGN KEY DDL for both physical and logical references.
- Added `TABLE_NAME_NOT_PLURAL` / `W:TABLE-PLURAL` hints without renaming identifiers.

### Fixed
- Fixed FK parsing for plural referenced tables such as `LANGUAGES/Y`, `COUNTRIES/Y`, and `TIM. CALENDARS/N`; the trailing `S` is retained as part of the table name.
- Restored backward-compatible ForeignKey constructor semantics for deferrable and initially-deferred constraints.
- Updated Word FK parsing to use the full constructor including physical/logical and schema-explicit flags.
- Prevented PostgreSQL deferrability clauses from being lost after adding FK classification flags.

## REST regression hardening
- Restored timestamped names for JSON, Oracle SQL, PostgreSQL SQL, and downloaded ZIP archives.
- Updated the V1.2 regression document to the continuation-table version containing three foreign keys.
- Added an API service regression test covering shared timestamps, continuation columns, and all three foreign keys.

## 2026-07-26 - Phase closure: internal parser recovery hints

- Internal datatype normalization messages are no longer exposed as `RECOVERY_WARNING` in SQL or JSON validation findings.
- Only actionable parser findings remain user-visible: duplicate columns, missing datatypes, and missing Persian descriptions.
- `recovery.warningCount` and `recovery.warnings` now contain actionable recovery findings only.

## 2026-07-26 - Oracle default tablespace completion

- Oracle tables generated from Word, ZIP, EA XML, CLI and REST now receive `TABLESPACE TS_<SCHEMA>` when no explicit table tablespace is supplied.
- Oracle primary-key, unique-key and standalone indexes now receive `TABLESPACE ITS_<SCHEMA>` when no explicit index tablespace is supplied.
- Explicit physical options continue to override the schema-derived defaults.
- PostgreSQL behavior is unchanged.

## 2026-07-26 - Configured role grants

- Added `schemaforge.standards.grants` configuration for standard table privileges.
- Added the configured grants to every generated table for Oracle and PostgreSQL.
- Clarified that `U_DEVELOPER` and `U_DESIGNER` are database roles/principals, not application user ids.
- Moved all `GRANT` statements to the end of the executable SQL body.
- Corrected grant rendering to standard SQL order: `GRANT <privileges> ON <table> TO <role>`.
- Explicit table-level `GRANTS` physical options are preserved and merged without duplicates.

## 2026-07-26 - Document-to-database Excel comparison

- Restored the SchemaForge v3 one-sheet, 22-column comparison workbook layout.
- Added live Oracle and PostgreSQL table inspection without application-level caching.
- REST Word, ZIP and EA XML requests now include one comparison workbook per target database when the exact table already exists.
- Added canonical datatype, nullability, default, comment, key, index and check-constraint comparison.
- Preserved the historical `COLUMN_USAGE` and `DIFF` columns and timestamped workbook naming.
- Added automated workbook and REST ZIP regression tests.

## 2026-07-26 - Comparison Excel correction

- Canonicalized `CHECK ... IN (...)` values before comparison so order-only differences such as `IN (0,1)` and `IN (1,0)` are equal.
- Assigned sequential ordinal positions to generated audit columns.
- Avoided false Oracle identity differences when the document logical identity and database sequence default are semantically equivalent; remaining differences use `IDENTITY_MODE`.
- Qualified consolidated JSON metadata-validation paths and messages with the database dialect.
- Improved database check-constraint display to `NAME: expression`.
- Row color changes intentionally deferred.

## 2026-07-26 - Comparison CHECK canonicalization fix

- Fixed the Excel comparison of `CHECK ... IN (...)` constraints after whitespace normalization.
- `IN (0, 1)`, `IN (1, 0)` and spacing variants are now treated as semantically equivalent.
- Added a regression test to prevent false `CHECK CONSTRAINT` differences caused only by list order.
