## 2026-07-28 - ZIP batch regression fixture correction

- Corrected `SchemaForgeApiZipBatchTest` so its invalid DOCX contains a valid metadata table but intentionally omits the column specification table.
- Aligns the fixture with the asserted parser failure: `Column specification table was not found`.
- No production behavior or REST contract changed in this correction.


## Unreleased

### Fixed - REST ZIP batch fault isolation

- Prevented one malformed or non-specification `.docx` from aborting the entire `/api/v1/generate/zip` request with HTTP 400.
- Added per-document staging so failed documents cannot leave partial SQL/JSON/Excel artifacts in the response archive.
- Added `batch-generation-summary.csv` and `batch-generation-errors.log` to every ZIP batch response.
- Ignored Word lock files (`~$*.docx`), hidden dot files, AppleDouble files, and `__MACOSX` metadata entries.
- Added regression coverage for mixed valid/invalid ZIP input and all-invalid diagnostic archives.

### Added - Microsoft SQL Server core dialect

- Registered `SQLSERVER` with command name `sqlserver` and aliases `sql-server`, `mssql`, and `sqlsrv`.
- Added `SqlServerDialect`, `SqlServerTypeMapper`, `SqlServerIdentifierRenderer`, and `SqlServerExpressionMapper`.
- Added `SAFE` exact numeric mapping and `OPTIMIZED` lossless `SMALLINT`/`INT`/`BIGINT` mapping through the shared numeric strategy.
- Added SQL Server sequences, `IDENTITY(1,1)`, computed columns, primary/unique/check/foreign-key constraints, included and filtered indexes, filegroup placement, extended-property comments, and grants.
- Added Word/ZIP REST artifacts, EA per-table SQL artifacts, and SQLCMD-compatible `run_all.sql` files with the `.sqlserver` suffix.
- Extended strategy-aware metadata type equivalence and SQL script parsing for SQL Server.
- Added regression coverage for platform selection, capabilities, datatype mapping, identifiers, expressions, complete DDL, REST archives, EA archives, numeric equivalence, and statement parsing.
- Completed SQL Server live metadata and execution validation in the validation completion pack described below.

### Changed - logical foreign keys and PostgreSQL UTF-8 hardening

- `/Y` foreign-key references remain executable physical constraints.
- `/N` references are now emitted as `[LOGICAL FOREIGN KEY]` hints and are no longer executed as database constraints.
- Added physical/logical foreign-key counts to the generated object summary.
- PostgreSQL scripts now start with `\encoding UTF8` before `\set ON_ERROR_STOP on` to protect Persian comments when executed by `psql`.
- Confirmed SQL Server identifier output remains deterministic `UPPER_SNAKE_CASE`.

### Fixed
- Corrected `Db2ZosOfflineDdlValidatorTest` to expect the four executable statements actually generated for one table with a primary key and one unique key: `CREATE TABLE`, primary-key enforcing index, unique constraint, and unique-key enforcing index.
- The production DB2 z/OS DDL generator and offline validator were already correct; only the test statement-count assertion was wrong.

## 2026-07-27 - Strategy-aware numeric metadata comparison

- Added shared PostgreSQL and Db2 for z/OS native-integer capacity profiles.
- Added `NumericTypeEquivalenceService` for SAFE/OPTIMIZED-aware comparison.
- In OPTIMIZED mode, exact numeric metadata is equivalent to the lossless native integer selected by the active dialect.
- Removed false `METADATA_DATATYPE_MISMATCH` and `W:TYPE` findings caused only by numeric optimization.
- Kept fractional numerics, precision above BIGINT capacity, unrelated datatypes, and SAFE-mode differences as real mismatches.
- Applied the same equivalence policy to metadata validation, rename candidate matching, and Excel column comparison.
- Added regression coverage for PostgreSQL, Db2 for z/OS, SAFE behavior, fractional values, capacity boundaries, and workbook output.

- Updated REST regression tests to use the schema extracted from `MCB.BIM.TBL.PROVINCES.V1.2.docx` (`DPS`) when matching comparison workbooks, tablespaces, and grants.
- Added table-level inline validation hints directly on the `CREATE TABLE` line for schema-not-found, same-name table in other schemas, spelling, and singular table-name findings.
# SchemaForge v4.1

- Added English class-level JavaDoc across production and test sources.
- Deprecated the legacy Phase1 command-line entry point and its obsolete tests.
- Performed non-functional Java source whitespace cleanup.
- Added `docs/V4.1-DOCUMENTATION-CLEANUP.md`.
- Deferred broad refactoring until additional database dialect requirements are implemented.


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

## 2026-07-26 - Comparison Excel row order and row fills

- Rows in comparison workbooks now follow the document column order first.
- Database-only rows are appended after all document rows.
- Applied the established Excel row background styles:
  - header: `GREY_40_PERCENT`
  - added/document-only column: `BRIGHT_GREEN`
  - dropped/database-only column: `RED`
  - modified or rename candidate row: `LIGHT_ORANGE`
  - position-only row: `GREY_25_PERCENT`
  - unchanged row: `LIGHT_CORNFLOWER_BLUE`
- Kept one comparison workbook per database dialect (`*.oracle.xlsx`, `*.postgresql.xlsx`).

## 2026-07-26 - Excel comparison historical fill correction

- Restored the historical v3 row-fill behavior.
- Unchanged rows now have no background fill.
- `GREY_40_PERCENT` remains for the header.
- `BRIGHT_GREEN` remains for document-only columns.
- `RED` remains for database-only columns.
- `LIGHT_ORANGE` remains for modified or rename-candidate rows.
- `GREY_25_PERCENT` remains for position-only differences.
- `LIGHT_CORNFLOWER_BLUE` is no longer applied to unchanged rows because it was not used by the real v3 comparison workbooks.

## 2026-07-26 - Excel database-object comparison completion

- Added thin borders to all cells in the column and database-object comparison sheets.
- Preserved the historical v3 row colors: green for ADD, red for DROP, orange for MODIFY, grey for position-only changes, and no fill for unchanged rows.
- Added separate comparison sheets for primary keys, foreign keys, non-unique indexes, and unique constraints/indexes.
- Composite and single-column indexes are compared by ordered key columns, sort direction, index type, include columns and predicate.
- Primary-key, foreign-key and unique-object changes now include names and complete canonical definitions rather than column membership only.
- Kept document object order first and appended database-only objects afterwards.
- Added a database-neutral writer entry point that receives the generic dialect contract; database-specific metadata adapters remain outside the Excel writer.

## 2026-07-26 - Legacy Word index-token parsing

- Reads legacy `I1`, `I2`, ... index group tokens when they are stored in the `Primary/Foreign Key` column.
- Keeps the dedicated `Index` column as the first-priority source when both layouts are present.
- Groups repeated tokens as composite indexes in document row order.
- Propagates parsed indexes through the canonical model to JSON, Oracle/PostgreSQL DDL, and Excel comparison.
- Adds `WordLegacyIndexParsingTest` for the `I1` composite-index scenario.

## 2026-07-26 - Enterprise Architect XML/XMI phase

- Replaced the minimal EA class importer with an EA XMI 1.x table-model importer.
- Added configurable `schemaforge.ea.default-schema` fallback.
- Imported ordered columns, datatype details, nullability and Persian descriptions.
- Imported PK, FK associations, referential actions and simple/composite indexes.
- Reused the existing canonical JSON, Oracle/PostgreSQL DDL and Excel comparison pipeline.

## 2026-07-26 - Enterprise Architect per-table REST output

- EA XML/XMI REST generation now writes one Oracle and one PostgreSQL SQL file per table.
- Added per-dialect folders and per-table comparison workbook folders.
- Added consolidated `model.json` and `manifest.json`.
- Added Oracle and PostgreSQL `run_all.sql` files ordered by internal foreign-key dependencies.
- Added cycle reporting in the run-all header without changing Word or ZIP input behavior.
- Added regression coverage for per-table file names, manifest contents and dependency ordering.

## Numeric mapping foundation
- Added configurable `SAFE` and `OPTIMIZED` numeric mapping strategies.
- Added a shared lossless numeric optimization service.
- PostgreSQL optimized mapping: NUMBER(1..4,0) -> SMALLINT, NUMBER(5..9,0) -> INTEGER, NUMBER(10..18,0) -> BIGINT.
- Decimal values, unbounded NUMBER, and precision above 18 remain NUMERIC.
- Default remains SAFE for backward compatibility.

## 2026-07-27 - LanguageTool test stability

- Stabilized the local LanguageTool HTTP stub used by `LanguageToolSpellCheckServiceTest`.
- Increased only the local test connect/request timeouts to tolerate slow Windows CI hosts.
- Executed the test HTTP handler directly on the server dispatcher and closed each exchange deterministically.
- Disabled fail-open for successful-response tests so transport failures are reported directly instead of appearing as spelling results.
- Production spell-check behavior and numeric mapping behavior are unchanged.

## 2026-07-27 - Db2 for z/OS numeric mapping foundation

- Added `Db2ZosTypeMapper` as the first v4.2 Db2 for z/OS component.
- SAFE mapping preserves exact numbers as `DECIMAL(p,s)`.
- OPTIMIZED mapping uses `SMALLINT`, `INTEGER` and `BIGINT` at lossless precision boundaries.
- Added explicit rejection for unbounded NUMBER and precision above the Db2 z/OS DECIMAL limit of 31.
- This foundation is now followed by the registered core dialect integration below.

## 2026-07-27 - Db2 for z/OS core dialect integration

- Registered `DB2_ZOS` with command name `db2zos` and aliases `db2-zos`, `db2`, and `zos`.
- Added `Db2ZosDialect`, identifier rendering, expression conversion, and common Oracle-to-Db2 datatype mapping.
- Connected Db2 output to CLI, REST Word/ZIP generation, and EA per-table generation.
- Added Db2 SQL and comparison-workbook artifact naming using the `.db2zos` suffix.
- Added Db2 table placement through `IN TABLESPACE` and `IN DATABASE.TABLESPACE`.
- Added Db2-aware foreign-key action handling and rejection of unsupported update/delete rules.
- Added regression coverage for type mapping, identifiers, expressions, capabilities, complete DDL, REST archives, and EA archives.
- Kept the existing dual Oracle/PostgreSQL JDBC validation runner explicitly dual; Db2 execution validation is deferred.
- Db2 catalog metadata access remains unavailable in production and resolves to the empty metadata repository.

## 2026-07-27 - Db2 for z/OS live metadata comparison

- Added the conditional `Db2ZosMetadataRepository` JDBC adapter.
- Added live catalog reads for tables, columns, primary/unique/check/foreign-key constraints and indexes.
- Connected `DB2_ZOS` to `MetadataRepositoryResolver`; disabled configurations still resolve to the empty repository.
- Added Db2 metadata datasource properties and environment-variable configuration.
- Enabled existing REST and EA comparison-workbook generation for Db2 when the exact table exists.
- Preserved strategy-aware `SAFE`/`OPTIMIZED` numeric equivalence in Db2 validation and Excel comparison.
- Added mapper and repository-resolution regression tests.
- Documented JCC configuration, catalog sources and first-phase limitations.

## 2026-07-27 - Db2 for z/OS validation completion pack

- Added explicit unique enforcing indexes for every Db2 primary key and unique constraint.
- Added deterministic `Db2ZosOfflineDdlValidator` checks for foreign-dialect syntax, unsupported `ON UPDATE`, decimal precision/scale, balanced delimiters, unexpected statement types and missing enforcing indexes.
- Added the read-only `Db2ZosConnectionProbeService` for JCC, server, schema, SQLID and catalog-access verification.
- Added `Db2ZosValidationRunner` with separate `generate`, `probe` and confirmation-gated `execute` modes.
- Added the explicitly invoked `Db2ZosLiveIT`, excluded from normal test discovery, which creates, verifies and removes disposable Db2 objects.
- Added an inactive `db2zos-live` Maven profile that accepts a local organization-approved JCC JAR without bundling or redistributing it.
- Added complete staged live-validation instructions in `docs/testing/DB2-ZOS-LIVE-VALIDATION.md`.


## 2026-07-27 - Microsoft SQL Server metadata and validation phase

- Added conditional `SqlServerMetadataRepository` and `JdbcSqlServerMetadataRepository` implementations.
- Added live reads from SQL Server `sys.*` catalog views for tables, columns, defaults, identity/computed columns, descriptions, PK/UK/FK/check constraints, rowstore indexes, include columns, filters and filegroups.
- Connected SQL Server to `MetadataRepositoryResolver`, REST comparison workbooks and EA per-table comparison output.
- Added SQL Server metadata datasource configuration and the Microsoft JDBC driver runtime dependency.
- Preserved SQL Server-native `date`, `rowversion`, max-length types and temporal scale zero in the canonical metadata model.
- Added `SqlServerOfflineDdlValidator` for datatype limits, delimiter checks, statement-family checks and foreign-dialect leakage.
- Added `SqlServerConnectionProbeService` for read-only server/database/schema and catalog-access verification.
- Added SQL Server repository, resolver, offline-validator and probe regression tests.
- Added SQL Server metadata and validation documentation.

## 2026-07-27 - Microsoft SQL Server live validation completion pack

- Added `SqlServerValidationRunner` with separate `generate`, `probe`, and confirmation-gated `execute` modes.
- Added deterministic CSV reports for SQL Server offline preflight and live JDBC execution results.
- Added the explicitly invoked `SqlServerLiveIT`, excluded from normal test discovery, for disposable schema creation, generated-DDL execution, catalog verification, metadata round-trip, Excel `SAME` verification, and cleanup.
- Covered table, column, primary key, foreign key, normal index with included columns, sequence, table descriptions, and column descriptions in the live verification model.
- Added the inactive `sqlserver-live` Maven profile using Failsafe; normal unit and regression builds remain database-independent.
- Replaced the deferred live-test notes with staged generate, probe, execute, and integration-test instructions in `docs/testing/SQL-SERVER-VALIDATION.md`.

