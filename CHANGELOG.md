## 2026-08-08 - PostgreSQL CREATE INDEX regression guard

- Added a dedicated fast regression test that proves PostgreSQL index names are never schema-qualified while table names remain schema-qualified.
- Added `POSTGRESQL_SCHEMA_QUALIFIED_INDEX_NAME` to the PostgreSQL static DDL sanity checker so invalid `CREATE INDEX schema.index ...` output can no longer be reported as clean generation.
- Corrected the stale PostgreSQL generator assertion that still expected a schema-qualified unique index name.
- Added a PostgreSQL dialect invariant check to JSON-to-DDL batch generation.
- Added optional `schemaforge.snapshot.ddl.cleanOutput=true` to delete only the selected platform output directories before regeneration and prevent old timestamped SQL files from mixing with a new run.
- Java 21 smoke verification confirms the dialect returns an unqualified index name and the sanity checker rejects schema-qualified PostgreSQL index names.

## 2026-08-08 - JSON-driven PostgreSQL/SQL Server dialect hardening

- Corrected PostgreSQL `CREATE INDEX` rendering so the index name is not schema-qualified while the target table remains schema-qualified.
- Bounded SQL Server exact numeric precision above 38 to `DECIMAL(38,s)` and temporal precision above 7 to `DATETIME2/DATETIMEOFFSET/TIME(7)` without changing the canonical JSON snapshot.
- Added dialect-mapping findings to `CanonicalJsonDirectoryToDdlIT` so bounded SQL Server mappings remain auditable in the generation report.
- Added `SqlServerDirectoryExecutionTest` with recursive JDBC execution, `HISTORICAL`/`FULL` modes, guarded drop-before-create, `GO` batch-separator filtering, SQLSTATE/vendor-code classification, and CSV/text reports.
- Verified the changed dialect code with Java 21 smoke tests: PostgreSQL index names are unqualified, `NUMBER(70)` renders as `DECIMAL(38,0)`, `NUMBER(115,5)` as `DECIMAL(38,5)`, and `TIMESTAMP(26)` as `DATETIME2(7)`.
- Kept the Legacy Word parser and the canonical snapshot contents unchanged.

## 2026-08-08 - Canonical JSON snapshot cache for Legacy Word

- Added a versioned DBMS-neutral canonical snapshot DTO separate from the domain model and from all SQL dialects.
- Added lossless `DatabaseSchema` snapshot mapping for tables, columns, PK/FK/UK/check constraints, indexes, sequences, metadata, descriptions and physical options.
- Added SHA-256 source identity, parser/model/snapshot version cache invalidation and atomic UTF-8 JSON writes.
- Added `WordDirectoryToCanonicalJsonIT` for one-time/incremental Word-to-JSON materialization with `manifest.json` audit status.
- Added `CanonicalJsonDirectoryToDdlIT` so Oracle/PostgreSQL/SQL Server DDL can be regenerated without reopening Word documents.
- Added round-trip regression coverage and `docs/integration/CANONICAL-JSON-SNAPSHOT-CACHE.md`.
- Kept the Legacy Word parser and existing Word-to-DDL path behavior unchanged.

## 2026-08-08 - PostgreSQL directory execution test

- Added `PostgreSqlDirectoryExecutionTest` for recursive JDBC execution of generated PostgreSQL DDL.
- Added PostgreSQL SQLSTATE reporting, historical/full execution modes, safe drop-before-create support, and per-file CSV reports.
- Added PostgreSQL-aware splitting that skips `psql` meta-commands and preserves quoted/dollar-quoted SQL content.
- Smoke-verified the splitter against all 4,766 generated PostgreSQL scripts: 4,766 CREATE TABLE statements detected and 9,532 psql commands skipped.

## 2026-08-07 - Recursive Oracle/PostgreSQL/SQL Server Legacy DDL generation

- Added `WordDirectoryMultiDatabaseGenerationIT` to parse and prepare each Legacy/standard Word document once and render the same canonical model for Oracle, PostgreSQL and Microsoft SQL Server.
- Added per-platform output directories while preserving the input subdirectory structure and the centralized timestamped SQL naming policy.
- Added CSV and text summaries that distinguish parse failures, generation failures and scripts generated with static-validation findings.
- Added `PostgreSqlDdlSanityChecker` for cross-dialect leakage, malformed delimiters, explicit numeric/temporal precision and character-length checks.
- Hardened SQL Server exact-numeric mapping and offline validation so `DECIMAL` precision 0, negative scale, and scale greater than precision are reported before live execution.
- Reused the existing `OracleDdlSanityChecker` and `SqlServerOfflineDdlValidator` so all three generated dialects report pre-execution issues consistently.
- Kept discovery mode non-failing by default (`schemaforge.word.failOnErrors=false`) so a problem in one dialect or document does not prevent the rest of the 4,766-document corpus from being generated.

## 2026-08-06 - Complete class-level JavaDoc coverage

- Completed class-level JavaDoc for all 164 production and 83 test top-level Java types.
- Expanded Oracle and SQL Server CRUD controller documentation to state delegation, response and error-mapping boundaries.
- Documented the Legacy Word parser support types and their immutable intermediate extraction records.
- Added regression-boundary documentation to the remaining 23 undocumented test classes.
- Added `docs/CLASS-DOCUMENTATION-COVERAGE.md` and linked it from the documentation index.
- No runtime behavior, public API, SQL generation rule or package structure changed.
- Maven wrapper verification was attempted, but Maven 3.9.9 could not be downloaded in the execution environment.

## 2026-08-06 - Oracle execution root-cause hardening

- Added deterministic Oracle-safe rendering for exact reserved identifiers such as `ROWID`, `DESC`, `ROWNUM`, `GROUP`, `COMMENT`, `UID`, `ROW`, `USER`, and `LEVEL`; every table, column, PK, FK, index, and comment reference uses the same `SF_` physical-name mapping.
- Strengthened `LegacyDefaultValueNormalizer` with datatype compatibility, `NUMBER(p,s)` capacity, quoted-numeric, string-length, malformed signed-literal, and leaked datatype-declaration checks.
- Added a final Oracle default-expression guard in `OracleDialect` so an invalid default cannot reach executable DDL even when a non-legacy input path bypasses the legacy normalizer.
- Mapped oversized `VARCHAR2`/`CHAR` to `CLOB`, oversized `NVARCHAR2`/`NCHAR` to `NCLOB`, and oversized `RAW` to `BLOB` under the conservative Oracle `MAX_STRING_SIZE=STANDARD` policy.
- Suppressed standalone indexes that duplicate PK/UK column signatures and removed repeated columns inside a single index.
- Expanded `OracleDdlSanityChecker` for reserved names, datatype/default mismatches, numeric default capacity, string default length, malformed defaults, and standard Oracle character-length limits.
- Added `HISTORICAL` and `FULL` execution modes to `OracleSqlDirectoryExecutionTest`. Historical mode skips cross-table foreign keys and grants by default, stops a file after its `CREATE TABLE` fails, and reports cleanup counts separately to prevent cascaded errors from hiding root causes.
- Added regression coverage for the Oracle errors observed in the 4,766-file execution report: `ORA-03050`, `ORA-00932`, `ORA-01438`, `ORA-01722`, `ORA-00936`, `ORA-00910`, `ORA-01401`, `ORA-01408`, and `ORA-00957`.

## 2026-08-06 - Recursive Oracle SQL execution audit test

- Added `OracleSqlDirectoryExecutionTest` for recursively executing generated Oracle SQL files through JDBC.
- Continues after statement-level errors and writes detailed error, per-file and summary reports.
- Added guarded `dropBeforeCreate` mode for validating multiple historical versions in a disposable schema.
- Added SQL*Plus command filtering, quoted-semicolon handling and PL/SQL slash-terminator support.
- Added `docs/ORACLE-SQL-DIRECTORY-EXECUTION-TEST.md` with Windows execution instructions and safety controls.

## 2026-08-05 - EA Party probe compile and deduplication regression fix

- Fixed `EnterpriseArchitectPartyProbeTest` to use the canonical table accessor `qualifiedName().name()` instead of the nonexistent `Table.name()` method.
- Restored logical EA table deduplication by normalized `<SCHEMA>.<TABLE>` while retaining every XMI element ID for association and foreign-key resolution.
- Prevented EA internal `owner` references such as `EAID_*`, `EAPK_*`, and GUID values from being interpreted as physical database schema names.
- Verified the supplied `Party_14050514.xml` probe with Java 21: 46 EA table elements resolve to 41 logical tables and `DPS.PARTY` is emitted once.

## 2026-08-05 - Legacy Oracle default, precision, and pre-write safety gate

- Added `LegacyDefaultValueNormalizer` to the actual canonical-column construction path for legacy DOC/DOCX parsing; explanatory text after numeric defaults is removed before `Column.defaultValue` is created.
- Applied the same normalization to the standard DOCX parser so a legacy-shaped DOCX cannot bypass the safety rule by being accepted by the standard parser first.
- Unsafe or unresolved natural-language defaults are omitted from executable DDL and recorded as `LEGACY_DEFAULT_DROPPED`; recoverable values are recorded as `LEGACY_DEFAULT_NORMALIZED`.
- Bounded Oracle rendering to `NUMBER` precision 38, `NUMBER` scale 127, and `TIMESTAMP` fractional-seconds precision 9.
- Added `OracleDdlSanityChecker` immediately before Oracle SQL file writes in the REST/ZIP path, EA per-table path, offline generation service, and recursive Legacy Word batch runner.
- The safety gate rejects leaked natural-language defaults, trailing default annotations, smart quotes, unknown bare identifiers, unbalanced/default-invalid tokens, and out-of-range Oracle precision before a file reaches the output directory.
- Added focused normalizer, Oracle safety-gate, and end-to-end parser-to-DDL regression tests for the reported `JTMSCUSTOMERS` values.
- Repaired and re-audited the supplied 4,766-file Oracle output set: all 4,766 files still contain `CREATE TABLE`; the reported default signatures, `NUMBER` precision above 38, `TIMESTAMP` precision above 9, and safety-gate findings are all zero after repair.

## 2026-08-03 - Legacy Word authoritative raw-metadata precedence

- Kept the bounded raw DOC metadata result separate from the noisy HWPF aggregate during table/entity resolution.
- When the raw pair matches the table token in the source file name and contains a valid Persian entity title, it is now used as the authoritative table-title source.
- Prevented a later-page header or a field-tail candidate from replacing the correct Persian title after the raw scanner had already recovered it.
- This correction targets the five remaining Legacy Word regressions in metadata confidence, canonical Persian table name, Oracle table comments and the Legacy REST output path.

## 2026-08-03 - Legacy Word metadata-pair selection fix

- Kept `poi-ooxml` and `poi-scratchpad` aligned with Legacy Word Parser Core 0.5.8 at Apache POI 5.5.1.
- Fixed duplicate legacy DOC metadata handling: when HWPF exposes an early blank or truncated entity header and the bounded raw-container scan later exposes the complete header for the same table, the parser now ranks matching pairs instead of accepting the first labelled pair.
- Preferred a valid normalized Persian entity title, then a technical entity value, while preserving deterministic source order for equivalent candidates.
- Restored `MetadataConfidence.TRUSTED`, canonical `Table.persianName`, Oracle `COMMENT ON TABLE`, and the legacy REST output path for the affected regression documents.

## 2026-08-03 - EA Alias table-comment alignment

- Generated `COMMENT ON TABLE` now uses the EA table Alias/Persian name when available.
- Preserved the full EA documentation as separate descriptive metadata and as a non-executable SQL header comment.
- Kept backward compatibility by falling back to the table description when Alias is empty.
- Updated Excel `COMMENT_STATUS` to compare the database comment with the Persian name, using the same fallback rule.
- Added Oracle DDL, EA REST output and Excel comparison regression coverage.

## 2026-08-03 - EA table Persian name separation

- Added `Table.persianName` to the canonical model.
- EA XMI `alias` is now preserved independently from `documentation`/table description.
- Kept the legacy fallback where Alias is the only table text, so existing EA inputs continue to produce table comments.
- Added `persianName` to `model.json`.
- Added a `TABLE_METADATA` sheet to comparison workbooks.
- Added the Persian table name to generated SQL as a non-executable header comment.
- Preserved the value through audit and grant enrichment.

## 2026-08-02 - Central SQL script naming policy

- Added one public `OutputFileNamer.scriptFileName(...)` rule for every generated SQL script.
- Standardized DDL names as `<logical-name>_<yyyyMMdd_HHmmss_SSS>.<database>.sql`.
- Standardized Oracle and SQL Server CRUD names with the same timestamp rule.
- Added timestamped EA run-all names as `<source>_<timestamp>.<database>.run-all.sql`.
- Updated EA REST DDL generation, Word/ZIP REST generation, standalone CRUD services, run-all references, manifests, and regression tests to use the same naming policy.
- Removed direct SQL file-name concatenation from production services.

## 2026-08-02 - REST CRUD path regression test fix

- Updated REST comparison test expectations for `oracle/crud/` and `sqlserver/crud/`.
- Updated the EA per-table CRUD placement fixture with a non-primary-key column so CRUD generation has an updatable column.
- No production behavior changed; this patch aligns regression tests with the new CRUD directory layout.
## 2026-08-02 - REST CRUD placement and Oracle identity sequences

- REST-generated Oracle CRUD packages are stored under `oracle/crud/`.
- REST-generated SQL Server CRUD procedures are stored under `sqlserver/crud/`.
- Metadata CRUD summary CSV entries now contain the relative artifact path inside the ZIP.
- Oracle renders logical identity columns with a named `SEQ_<TABLE>` sequence and `DEFAULT <SCHEMA>.SEQ_<TABLE>.NEXTVAL` instead of native identity syntax.
- Other dialects retain their existing identity behavior, including SQL Server `IDENTITY(1,1)`.
- Added end-to-end regression coverage for CRUD directory placement and Oracle sequence-based identity generation.

## 2026-08-02 - EA API schema override and primary-key identity

- Added optional `schema` parameter to `POST /api/v1/generate/ea-xml`.
- An explicit API schema now overrides EA schema/owner tagged values and the configured fallback schema.
- EA primary-key columns imported through the REST API are normalized as identity and `NOT NULL`; conflicting defaults/generated expressions are removed.
- Added parser and end-to-end REST service regression coverage for schema override and identity generation.

## 2026-08-01 - Oracle character length semantics

- Oracle now renders unspecified `VARCHAR2(n)` lengths as `VARCHAR2(n CHAR)`.
- Oracle now renders unspecified `CHAR(n)` lengths as `CHAR(n CHAR)`.
- Explicit `BYTE` and `CHAR` semantics remain unchanged; `NVARCHAR2` and `NCHAR` are not modified.
- Added regression coverage for default, explicit byte, explicit char, and national character types.

## 2026-08-01 - EA schema, checks, comments, and audit normalization

- Changed the built-in Enterprise Architect fallback schema from `EA_SCHEMA` to `COL`; `application.yml` now uses `${SCHEMAFORGE_EA_DEFAULT_SCHEMA:COL}`.
- Added EA check-constraint extraction from the `code` tagged value and removed the outer `CHECK (...)` wrapper before canonical mapping.
- Added EA table documentation extraction from the `documentation` tagged value.
- Removed embedded HTML formatting from EA table and column descriptions before comment generation.
- Standardized `CREATED_BY`, `CREATED_DATE`, `LAST_MODIFIED_BY`, and `LAST_MODIFIED_DATE` exactly once, in fixed order, at the end of every prepared table.
- Reassigned canonical column positions after audit normalization so SQL and Excel outputs use the same ordering.
- Added regression coverage for EA defaults/checks/comments and audit replacement/order.

## 2026-07-29 - REST metadata CRUD artifacts

- Added Oracle CRUD package and SQL Server CRUD procedure artifacts to Word and ZIP REST archives.
- Added timestamped metadata CRUD summary CSV with explicit generated/skipped/failed status.
- Kept dedicated `/oracle/crud` and `/sqlserver/crud` endpoints unchanged.
## 2026-07-29 - SQL Server metadata-based CRUD procedures

- Added `POST /api/v1/generate/sqlserver/crud` with JSON `schema` and `table` input.
- Added `SqlServerCrudGenerationService` using the live SQL Server metadata repository rather than Word or EA input.
- Added centralized SQL Server CRUD naming and generation for `<TABLE>_CREATE`, `_UPDATE`, `_DELETE`, `_GET_BY_ID`, and `_SEARCH`.
- Added exact metadata-derived parameter types, identity/sequence/GUID generated-key detection, `OUTPUT INSERTED` output parameters, audit-column handling, bounded pagination, `TRY...CATCH`, and `THROW` error contracts.
- Kept transaction ownership with the caller; generated procedures contain no `BEGIN TRANSACTION`, `COMMIT`, or `ROLLBACK`.
- Added configured `GRANT EXECUTE` generation, unit/controller/service coverage, and `SqlServerCrudLiveIT` under the explicit `sqlserver-live` profile.

## 2026-07-29 - REST regression schema expectation correction

- Updated Word REST regression fixtures to use the actual `BIM` schema parsed from `MCB.BIM.TBL.PROVINCES.V1.2.docx` instead of the stale `DPS` expectation.
- Corrected comparison-workbook repository stubs and expected workbook names from `DPS.PROVINCES` to `BIM.PROVINCES`.
- Corrected Oracle tablespace, grant, PostgreSQL, Db2 for z/OS, and SQL Server assertions to the `BIM` schema.
- No production generator behavior changed; this release fixes two stale regression tests exposed by the complete Maven suite.

## 2026-07-29 - SQL Server trusted constraints and comment ordering

- SQL Server CHECK and physical FOREIGN KEY constraints are emitted with `WITH CHECK` and an explicit `CHECK CONSTRAINT` statement.
- Table and column descriptions are emitted before indexes and foreign keys so `MS_Description` metadata is preserved even when a later dependency fails.
- SQL Server generator regression assertions cover trusted-constraint syntax and comment ordering.

## Unreleased - Repository hygiene

- Added a root `.gitignore` for Maven build output, IDE metadata, logs, temporary files, local generated artifacts, environment-specific configuration, and local credentials/key stores.
- Removed generated `target` content and IntelliJ `.idea` metadata from the distributable source tree.
- Preserved Maven Wrapper files under `.mvn/wrapper`.


## 2026-07-28 - ZIP batch regression fixture correction

- Corrected `SchemaForgeApiZipBatchTest` so its invalid DOCX contains a valid metadata table but intentionally omits the column specification table.
- Aligns the fixture with the asserted parser failure: `Column specification table was not found`.
- No production behavior or REST contract changed in this correction.


## Unreleased

### Added - generated schema bootstrap blocks

- Added one schema bootstrap fragment before generated sequences and tables for every schema that owns generated objects.
- PostgreSQL now emits idempotent `CREATE SCHEMA IF NOT EXISTS ... AUTHORIZATION CURRENT_USER`.
- Microsoft SQL Server now emits idempotent `IF SCHEMA_ID(...) IS NULL EXEC(N'CREATE SCHEMA ... AUTHORIZATION [dbo]')` without requiring `GO`, so JDBC execution remains supported.
- Oracle now emits a non-executable `CREATE USER` provisioning template because an Oracle schema is a database user and secure password/tablespace decisions belong to DBA provisioning.
- Db2 for z/OS now emits a DSNHSP `CREATE SCHEMA AUTHORIZATION` template because z/OS schema definitions require the schema processor rather than ordinary interactive DDL execution.
- Added schema counts to the generated object summary and extended SQL Server offline/live validation coverage to include generated schema creation.

### Added - Oracle metadata-based CRUD package generation

- Added `POST /api/v1/generate/oracle/crud` with JSON `schema` and `table` input.
- Added `OracleCrudGenerationService` using the Oracle metadata repository rather than Word or EA input.
- Added `OracleCrudPackageGenerator` producing `PKG_<TABLE>` with `CREATE_ROW`, `UPDATE_ROW`, `DELETE_ROW`, `GET_BY_ID`, and `SEARCH`.
- Added `%TYPE` parameter anchoring, identity/sequence-default key detection, `RETURNING INTO`, audit-column handling, bounded search pagination, and exception-based errors.
- Kept transaction ownership with the caller; generated packages contain no `COMMIT`, `ROLLBACK`, or autonomous transaction.
- Added configured `GRANT EXECUTE` generation and regression coverage for generated keys, composite keys, search filters, service orchestration, and REST download responses.


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

## 2026-08-03 - Legacy Word parser first integration

- Added `POST /api/v1/generate/legacy-word?schema=<SCHEMA>` for legacy `.doc` and `.docx` table specifications that do not declare a schema.
- Integrated Legacy Word Parser Core 0.5.8 under `com.behsazan.schemaforge.specification.parser.legacy`.
- Legacy documents are mapped to the existing canonical `DatabaseSchema`, `Table`, `Column`, `PrimaryKey`, `UniqueKey`, `Index`, `IndexColumn`, and `ForeignKey` classes; the generation pipeline and output layout are shared with current Word documents.
- Added `poi-scratchpad` for binary `.doc` support.
- Added `WordDirectoryOracleGenerationIT`, an explicitly invoked recursive directory test that generates only Oracle DDL scripts for accepted current or legacy Word table documents.

## 2026-08-03 - Legacy DOC authoritative Persian title parsing

- Fixed canonical raw DOC metadata parsing for entity titles that legitimately begin with `تاریخچه تغییرات`.
- Added a dedicated bounded parser for `LegacyDocRawMetadataScanner` output instead of reusing the unbounded HWPF stop rules.
- Preserved short, clean history-title phrases while continuing to reject change-log grids, dated history rows, field metadata, and embedded labels.
- No REST contract, canonical domain model, output archive layout, or DDL generator behavior was changed.
