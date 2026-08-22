# SchemaForge v4 - Multi-dialect schema generation

SchemaForge parses Word and Enterprise Architect specifications into one canonical model and generates DDL for Oracle, PostgreSQL, Db2 for z/OS, Microsoft SQL Server, and MySQL. DDL generation remains offline; optional Oracle, PostgreSQL, Db2 for z/OS, Microsoft SQL Server, and MySQL metadata connections are used for validation/comparison and live-vs-design migration planning. MySQL has logical DDL, live execution regression coverage, and a JDBC metadata table/column adapter.

```text
one input.docx -> one JSON model + one SQL file per registered dialect
```

The SQL file contains all objects related to that Word specification in one file:

- sequence (when the Word identity marker requires it)
- schema bootstrap/provisioning block
- table and columns
- primary key
- check constraints
- unique constraints and supporting indexes
- foreign keys
- indexes
- table and column comments
- grants
- parser recovery warnings and generation footer

## ALTER / Flyway-compatible migration foundation

For an existing database table, SchemaForge compares live metadata with the desired canonical table and can build a versioned Flyway-compatible `ALTER TABLE` migration. **The ordinary CREATE script is still generated unconditionally**; the migration is an additional artifact under `<platform>/migrations/` and never replaces CREATE DDL. M1 covers column add/drop/type/nullability/default changes for all five DBMS platforms, writes SAFE/REVIEW/DESTRUCTIVE findings directly into the SQL, blocks destructive SQL by default, and never guesses a column rename. M1.2 treats legacy MySQL identity `SEQ_*.NEXTVAL` defaults using effective `AUTO_INCREMENT` semantics. M2 adds table-owned PK/FK/UK/CHECK/INDEX comparison and DROP-before-column / ADD-after-column ordering, and expands MySQL live metadata to those objects. M2-R4/R5 normalize MySQL `information_schema` CHECK rendering (backticks, automatic UTF charset introducers, escaped literal delimiters, and insignificant punctuation whitespace), and M2-R8 normalizes PostgreSQL `pg_get_constraintdef(..., true)` CHECK presentation for ordinary identifier case plus redundant parentheses around atomic boolean predicates. Boolean grouping that can affect precedence and string/quoted-identifier semantics are preserved. Incoming foreign keys owned by other tables and physical-option migration remain explicit later work. See [`docs/ALTER-MIGRATION-M1.md`](docs/ALTER-MIGRATION-M1.md) and [`docs/ALTER-MIGRATION-M2.md`](docs/ALTER-MIGRATION-M2.md).

## Legacy Oracle default and precision safety gate

Legacy Word defaults are normalized before they enter the canonical `Column.defaultValue`. A cell such as `0 1- دائم 2- موقت` is reduced to executable `0`; an unsafe value that cannot be reduced conservatively is omitted from the DDL and reported in recovery metadata rather than emitted as invalid SQL.

Oracle exact-numeric precision above 38 is now a blocking datatype-compatibility error; it is not silently clamped to 38. Oracle NUMBER scale above the current bound and TIMESTAMP precision above the current bound remain explicit review findings where the existing dialect performs a bounded rendering. Every production Oracle write path runs `OracleDdlSanityChecker` immediately before `Files.writeString`; a script containing leaked explanatory text or invalid Oracle syntax is not published.

The recursive Word/bulk path records a blocking mapping as a diagnostic item and continues with the remaining documents rather than publishing a guessed or syntactically unsafe Oracle file.


## Final single-file DBA DDL contract

For every source specification whose target-database datatype mapping is renderable, the DBMS SQL artifact is self-contained for DBA review. The production `DdlGenerator` emits the following information in one file, in execution-aware order:

1. validation/datatype findings at the top of the script when findings exist;
2. DBMS preamble and source/schema metadata;
3. schema bootstrap/provisioning statement or DBA template;
4. sequences required by the table model;
5. `CREATE TABLE`, columns, primary key, active placement, and inline physical-review block;
6. DBMS-required enforcing indexes, check constraints, unique constraints, and standalone indexes;
7. physical foreign keys plus FK supporting-index recommendations when coverage is missing;
8. table/column descriptions (`COMMENT` or SQL Server extended properties);
9. grants as the final executable statements;
10. SchemaForge object summary and generation footer.

Physical recommendations remain non-executable comments. Source/validation issues remain visible in the SQL file and are never silently converted into active tuning. A fatal datatype mapping does not produce a guessed SQL file; the bulk generation report records the document as `GENERATION_BLOCKED_BY_MAPPING`. This is the final Phase-1 DBA delivery contract; no additional companion file is required to understand a successfully generated table script.

## Canonical JSON snapshot cache

For large Legacy Word corpora, Word parsing can be materialized once as versioned DBMS-neutral `*.schema.json` snapshots. Subsequent dialect work, including MySQL logical DDL, can read JSON instead of reopening Word documents. Cache reuse is guarded by source SHA-256 plus snapshot/model/parser versions, so a dialect-only change does not trigger a multi-hour Word reparse. See [`docs/integration/CANONICAL-JSON-SNAPSHOT-CACHE.md`](docs/integration/CANONICAL-JSON-SNAPSHOT-CACHE.md).

## Mermaid diagram export

The canonical model can also be exported as Mermaid/Graphviz diagrams without invoking a SQL dialect. Existing ER and dependency views remain unchanged. Conceptual ERD Phase 1 adds a field-free `CONCEPTUAL_ERD` view with relationship cardinality/optionality derived only from FK nullability and exact PK/UK evidence. See [`docs/diagram/CONCEPTUAL-ERD-PHASE1.md`](docs/diagram/CONCEPTUAL-ERD-PHASE1.md), [`docs/diagram/MERMAID-EXPORT-PHASE1.md`](docs/diagram/MERMAID-EXPORT-PHASE1.md), and [`docs/diagram/MERMAID-CANONICAL-JSON-PILOT.md`](docs/diagram/MERMAID-CANONICAL-JSON-PILOT.md).

## Build

```bash
mvn clean package
```

## Run

Create outputs next to the Word file:

```bash
java -jar target/schema-forge-v4-4.0.0-SNAPSHOT.jar input.docx
```

Create outputs in a selected directory:

```bash
java -jar target/schema-forge-v4-4.0.0-SNAPSHOT.jar input.docx output-directory
```

The offline CLI does not require a JDBC connection. REST metadata validation and comparison are activated only for explicitly enabled database adapters.

## Current project documentation

The authoritative current documentation starts at [`docs/reference/README.md`](docs/reference/README.md). It consolidates architecture, canonical domain model, inputs/outputs, the multi-database support matrix, Physical DDL, P8 physical metadata comparison, Excel workbook behavior, the no-guess policy, known limitations, developer guidance, testing, and the current release baseline.

Older phase/release documents remain under [`docs/`](docs/) as implementation history and validation evidence. Some historical documents contain earlier test counts; current status is defined by the 399-test baseline in [`docs/reference/CURRENT-RELEASE-BASELINE.md`](docs/reference/CURRENT-RELEASE-BASELINE.md). The project-level history remains in `CHANGELOG.md`.

## V4 DBMS-neutral DDL refactoring

`DdlGenerator` now contains only orchestration and canonical-model traversal. DBMS-specific rendering such as Oracle `USING INDEX`, Db2 enforcing indexes, and SQL Server computed columns, filegroups, filtered indexes, and extended properties is delegated to the selected dialect. PostgreSQL, Db2 for z/OS, and SQL Server use the same generator without Oracle syntax leakage.

## DBMS selection (V4)

The offline entry point supports Oracle, PostgreSQL, Db2 for z/OS, Microsoft SQL Server, and MySQL while preserving Oracle as the default:

```text
java -jar schema-forge.jar input.docx
java -jar schema-forge.jar input.docx postgresql
java -jar schema-forge.jar input.docx db2zos
java -jar schema-forge.jar input.docx sqlserver
java -jar schema-forge.jar input.docx mysql
java -jar schema-forge.jar input.docx output-directory sqlserver
```

## Timestamped output files

All generated SQL scripts use the central `OutputFileNamer.scriptFileName(...)` policy and share one Gregorian timestamp per generation request:

```text
<logical-name>_yyyyMMdd_HHmmss_SSS.<database>.sql
<schema>.<table>_yyyyMMdd_HHmmss_SSS.oracle.crud-package.sql
<schema>.<table>_yyyyMMdd_HHmmss_SSS.sqlserver.crud-procedures.sql
<source>_yyyyMMdd_HHmmss_SSS.<database>.run-all.sql
```

JSON and report artifacts retain their existing timestamped naming rules.

## DBMS-aware generated file names

Generated artifacts use a shared Gregorian timestamp. SQL file names also identify the selected dialect:

```text
<input>_yyyyMMdd_HHmmss_SSS.json
<input>_yyyyMMdd_HHmmss_SSS.oracle.sql
<input>_yyyyMMdd_HHmmss_SSS.postgresql.sql
<input>_yyyyMMdd_HHmmss_SSS.db2zos.sql
<input>_yyyyMMdd_HHmmss_SSS.sqlserver.sql
<input>_yyyyMMdd_HHmmss_SSS.mysql.sql
```

### Foreign-key reference flags
- `TABLE/Y`: physical reference.
- `TABLE/N`: logical reference.
- `SCHEMA.TABLE/Y` and `SCHEMA.TABLE/N`: qualified references with an explicit schema.
- Spaces around the schema separator are tolerated because Word may expose `TIM. CALENDARS/N`.
- The final `S` in plural table names such as `LANGUAGES`, `COUNTRIES`, and `CALENDARS` is part of the identifier, not a flag.
- `/Y` references generate executable `FOREIGN KEY` constraints.
- `/N` references remain in the canonical model and comparison reports but are emitted only as `[LOGICAL FOREIGN KEY]` SQL hints; no executable constraint is generated.
- Singular table names are preserved unchanged and receive `W:TABLE-PLURAL` only.

## Oracle storage defaults

When a Word/EA specification does not provide explicit physical options, the Oracle dialect applies the project storage convention automatically:

```text
Table tablespace : TS_<SCHEMA>
Index tablespace : ITS_<SCHEMA>
```

For example, table `DPS.DEPOSITS` is terminated as:

```sql
) TABLESPACE TS_DPS;
```

and its primary-key, unique-key and standalone indexes use `TABLESPACE ITS_DPS`. Explicit `TABLESPACE`, `INDEX_TABLESPACE` or `PK_TABLESPACE` options in the canonical model take precedence over these defaults. PostgreSQL is unchanged and receives no implicit tablespace.

PostgreSQL scripts begin with `\encoding UTF8` followed by `\set ON_ERROR_STOP on`. The encoding command protects Persian table and column comments when scripts are executed through `psql`; the database itself should also use UTF-8.

## Schema bootstrap behavior

SchemaForge places the schema bootstrap fragment before sequences and tables. Only schemas that own generated tables or sequences are included, and duplicate schema names are emitted once.

| Dialect | Generated behavior |
|---|---|
| PostgreSQL | Executable and idempotent `CREATE SCHEMA IF NOT EXISTS <schema> AUTHORIZATION CURRENT_USER;` |
| Microsoft SQL Server | Executable and idempotent `IF SCHEMA_ID(...) IS NULL EXEC(N'CREATE SCHEMA ... AUTHORIZATION [dbo]');` |
| MySQL | Executable and idempotent `CREATE DATABASE IF NOT EXISTS <schema>;` because MySQL treats `SCHEMA` as a synonym for `DATABASE`. |
| Oracle | Non-executable `CREATE USER` provisioning template for DBA review |
| Db2 for z/OS | Non-executable DSNHSP `CREATE SCHEMA AUTHORIZATION` template |

Oracle does not create a schema with the ANSI `CREATE SCHEMA` statement; the schema is created with its database user. SchemaForge therefore does not generate an executable user with a default password. The generated template uses `TS_<SCHEMA>`, `ITS_<SCHEMA>` and `TEMP` placeholders that must match the approved Oracle storage policy.

Db2 for z/OS schema definitions are processed by DSNHSP and are not mixed into the ordinary executable DDL file. The execution authorization ID must have the required schema and database privileges before running the generated table DDL.

## Standard database role grants

Standard table privileges are configured centrally and are applied to every generated table:

```yaml
schemaforge:
  standards:
    grants:
      - grantee: U_DEVELOPER
        privileges: [SELECT, INSERT, UPDATE, DELETE]
      - grantee: U_DESIGNER
        privileges: [SELECT, INSERT, UPDATE, DELETE]
```

`grantee` identifies a database **role/principal**, not an application user id. The generated statements are placed at the end of the executable SQL body:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON DPS.DEPOSITS TO U_DEVELOPER;
GRANT SELECT, INSERT, UPDATE, DELETE ON DPS.DEPOSITS TO U_DESIGNER;
```

The same configuration is used by REST Word/ZIP/EA XML generation and the offline generation pipeline. An empty `grants` list disables standard grants. Explicit table-level `GRANTS` options are retained and merged without duplicate statements.

## Oracle metadata-based CRUD packages

SchemaForge can generate an Oracle CRUD package directly from a live Oracle table; Word and EA input are not involved.

```http
POST /api/v1/generate/oracle/crud
Content-Type: application/json
```

```json
{
  "schema": "BIM",
  "table": "PROVINCES"
}
```

The response is `<SCHEMA>.<TABLE>.oracle.crud-package.sql` and contains `PKG_<TABLE>` with `CREATE_ROW`, `UPDATE_ROW`, `DELETE_ROW`, `GET_BY_ID`, and `SEARCH`. Types are anchored with `%TYPE`, generated keys use `RETURNING INTO`, audit timestamps use `SYSTIMESTAMP`, and transaction control remains with the caller. Oracle metadata must be enabled. See [`docs/dialects/ORACLE-CRUD-METADATA.md`](docs/dialects/ORACLE-CRUD-METADATA.md).

## SQL Server metadata-based CRUD procedures

SchemaForge can generate five SQL Server stored procedures directly from a live table in the database catalog:

```http
POST /api/v1/generate/sqlserver/crud
Content-Type: application/json
```

```json
{
  "schema": "BIM",
  "table": "PROVINCES"
}
```

The response is `<SCHEMA>.<TABLE>.sqlserver.crud-procedures.sql` and contains `<TABLE>_CREATE`, `<TABLE>_UPDATE`, `<TABLE>_DELETE`, `<TABLE>_GET_BY_ID`, and `<TABLE>_SEARCH`. Generated keys use `OUTPUT INSERTED`, search uses bounded `OFFSET/FETCH`, errors use `THROW`, and transaction control remains with the caller. SQL Server metadata must be enabled. See [`docs/dialects/SQLSERVER-CRUD-METADATA.md`](docs/dialects/SQLSERVER-CRUD-METADATA.md).

## Document-to-database Excel comparison

When metadata is enabled and the exact document table already exists in a target database, the REST response ZIP also contains a comparison workbook:

```text
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.oracle.xlsx
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.postgresql.xlsx
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.db2zos.xlsx
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.sqlserver.xlsx
```

The workbook preserves the established SchemaForge v3 22-column table sheet and adds database-object comparison sheets for keys and indexes. Metadata is read during the REST request and is not cached. If the table does not exist in a target database, the workbook for that database is omitted while SQL and JSON generation continue.

### Excel comparison sheets

Each database-specific comparison workbook contains the historical 22-column table sheet plus these database-neutral object sheets:

```text
PRIMARY_KEY_COMPARE
FOREIGN_KEYS_COMPARE
INDEXES_COMPARE
UNIQUE_INDEXES_COMPARE
```

Object rows use `ADD`, `DROP`, `MODIFY` and `SAME`. A new single-column or composite index in the document is therefore shown explicitly as `ADD`, even when the indexed columns already exist in the database. All report cells have thin borders. The writer works with canonical `Table` models and the generic `Dialect` contract. Oracle, PostgreSQL, Db2 for z/OS, and Microsoft SQL Server have JDBC metadata adapters. Db2 configuration is disabled by default and requires the IBM JCC driver at runtime. SQL Server metadata is also disabled by default and uses the Microsoft JDBC driver from Maven Central.

P8-A also adds `TABLE_PHYSICAL_COMPARE`. The document/profile side supplies expected physical design values and the JDBC `databaseTable` supplies actual table physical metadata; database state is never promoted back into design intent or generated DDL. Rows are classified as `MATCH`, `MISMATCH`, `NOT_SPECIFIED`, `NOT_AVAILABLE`, or `REVIEW`. See [`docs/P8-TABLE-PHYSICAL-METADATA-COMPARISON.md`](docs/P8-TABLE-PHYSICAL-METADATA-COMPARISON.md).

P8-B adds `INDEX_PHYSICAL_COMPARE` for ordinary indexes and the backing indexes of primary/unique constraints. It compares only persistent physical metadata acquired from vendor catalogs; index build-operation directives are not inferred. See [`docs/P8B-INDEX-PHYSICAL-METADATA-COMPARISON.md`](docs/P8B-INDEX-PHYSICAL-METADATA-COMPARISON.md).

P8-C adds `COLUMN_PHYSICAL_COMPARE`. Current column-level physical metadata acquisition is intentionally PostgreSQL-only (`STORAGE` / `COMPRESSION` from `pg_attribute`, with `pg_type.typstorage` used to compare `STORAGE DEFAULT`). Oracle, SQL Server, and Db2 are not given invented generic column mappings. See [`docs/P8C-COLUMN-PHYSICAL-METADATA-COMPARISON.md`](docs/P8C-COLUMN-PHYSICAL-METADATA-COMPARISON.md).

P8-D freezes the expected-vs-actual physical comparison baseline after the user-verified 399-test regression. P8-D adds no production or test behavior; it records the final comparison contract and the rule that database actual state remains comparison evidence only. See [`docs/P8D-PHYSICAL-COMPARISON-BASELINE-FREEZE.md`](docs/P8D-PHYSICAL-COMPARISON-BASELINE-FREEZE.md).


## Db2 for z/OS core dialect

Select the dialect with `db2zos` (aliases: `db2-zos`, `db2`, `zos`). The current core phase generates tables, columns, sequences, identity/generated columns, primary/unique/check/foreign-key constraints, indexes, comments, and grants. `TABLESPACE` is rendered as Db2 `IN <table-space>` or `IN <database>.<table-space>`. Db2 primary and unique constraints now receive explicit unique enforcing indexes so explicitly managed table spaces do not leave incomplete table definitions. Db2 metadata comparison is available when configured. Offline preflight, a read-only connection probe, and an explicitly invoked disposable live integration test are documented in `docs/testing/DB2-ZOS-LIVE-VALIDATION.md`. See also `docs/dialects/DB2-ZOS-DIALECT.md` and `docs/dialects/DB2-ZOS-METADATA.md`.

Enable live Db2 metadata comparison only after adding the organization-approved IBM JCC driver to the runtime classpath:

```text
SCHEMAFORGE_METADATA_DB2ZOS_ENABLED=true
SCHEMAFORGE_METADATA_DB2ZOS_URL=jdbc:db2://db2-host:446/LOCATION
SCHEMAFORGE_METADATA_DB2ZOS_USERNAME=SCHEMAFORGE
SCHEMAFORGE_METADATA_DB2ZOS_PASSWORD=change-me
```

## MySQL logical DDL P1

Select the dialect with `mysql`. MySQL is registered in the common platform/factory path and generates schema/database bootstrap, tables, evidence-safe `AUTO_INCREMENT` identity, stored generated columns, PK/UK/check/FK constraints, indexes, and inline table/column comments. Parser-generated identity backing sequences are suppressed only when they are identity-only; genuine standalone sequence semantics remain blocking. Cross-DBMS physical placement is not translated. A MySQL 8.4 live execution regression harness is available for generated SQL. MySQL table/column metadata comparison is active for migration M1; physical tuning, full constraint/index metadata parity, and metadata CRUD remain deferred. See [`docs/dialects/MYSQL-DIALECT-P1.md`](docs/dialects/MYSQL-DIALECT-P1.md), [`docs/MYSQL-P3-LIVE-EXECUTION.md`](docs/MYSQL-P3-LIVE-EXECUTION.md), and the P2/P3 audit notes under `docs/`.

## Microsoft SQL Server core dialect

Select the dialect with `sqlserver` (aliases: `sql-server`, `mssql`, `sqlsrv`). The dialect generates sequences, tables, identity/computed columns, primary/unique/check/foreign-key constraints, included and filtered indexes, `MS_Description` extended properties, grants, REST artifacts, and EA per-table artifacts. `SAFE` numeric mapping preserves exact values as `DECIMAL`; `OPTIMIZED` uses lossless `SMALLINT`, `INT`, and `BIGINT` boundaries. Conditional live metadata, comparison workbooks, offline DDL validation, a read-only connection/catalog probe, a confirmation-gated execution runner, and an explicit disposable-schema integration test are available. See `docs/dialects/SQL-SERVER-DIALECT.md`, `docs/dialects/SQL-SERVER-METADATA.md`, and `docs/testing/SQL-SERVER-VALIDATION.md`.

SQL Server identifiers are rendered in `UPPER_SNAKE_CASE` for consistency with the canonical banking data model and the Oracle/Db2 outputs. SQL Server usually resolves unquoted identifiers case-insensitively according to database collation, but SchemaForge keeps one deterministic uppercase representation and uses brackets only for reserved or non-ordinary names.

Enable SQL Server metadata comparison with:

```text
SCHEMAFORGE_METADATA_SQLSERVER_ENABLED=true
SCHEMAFORGE_METADATA_SQLSERVER_URL=jdbc:sqlserver://localhost:1433;databaseName=APPDB;encrypt=true;trustServerCertificate=true
SCHEMAFORGE_METADATA_SQLSERVER_USERNAME=sa
SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD=change-me
```

## Enterprise Architect XML/XMI input

The REST endpoint `POST /api/v1/generate/ea-xml` accepts Enterprise Architect XML/XMI 1.x exports. The multipart request accepts `file` and an optional `schema` parameter; an explicit API schema overrides schema/owner values embedded in EA and the configured fallback. EA primary-key columns imported through this endpoint are normalized as identity, `NOT NULL` columns. EA tables and columns are converted to the same canonical model used by Word input. Because one EA export may contain many tables, the response ZIP contains one Oracle, PostgreSQL, Db2 for z/OS, SQL Server, and MySQL SQL file per table, comparison workbooks for dialects with available metadata, a consolidated `model.json`, a `manifest.json`, and dialect-specific run-all files. MySQL generates DDL/run-all artifacts and has a separate live execution regression harness, but it still has no JDBC metadata comparison adapter.

```text
oracle/<SCHEMA>.<TABLE>.oracle.sql
postgresql/<schema>.<table>.postgresql.sql
db2zos/<SCHEMA>.<TABLE>.db2zos.sql
sqlserver/<SCHEMA>.<TABLE>.sqlserver.sql
mysql/<SCHEMA>.<TABLE>.mysql.sql
comparison/oracle/<SCHEMA>.<TABLE>.oracle.xlsx
comparison/postgresql/<schema>.<table>.postgresql.xlsx
comparison/db2zos/<SCHEMA>.<TABLE>.db2zos.xlsx
comparison/sqlserver/<SCHEMA>.<TABLE>.sqlserver.xlsx
oracle/run_all.sql
postgresql/run_all.sql
db2zos/run_all.sql
sqlserver/run_all.sql
mysql/run_all.sql
model.json
manifest.json
```

EA exports may omit the physical schema. Configure the fallback schema centrally:

```yaml
schemaforge:
  ea:
    default-schema: ${SCHEMAFORGE_EA_DEFAULT_SCHEMA:EA_SCHEMA}
```

An explicit schema/owner tagged value in the XML overrides this fallback.

## Legacy Word table specifications

Legacy Word documents that do not contain a schema use a separate REST entry point while retaining the standard SchemaForge output pipeline:

```text
POST /api/v1/generate/legacy-word?schema=DPS
```

Upload one `.doc` or `.docx` file as multipart field `file`. The schema parameter is required. See `docs/integration/LEGACY-WORD-PARSER.md` for recursive Oracle-only and Oracle/PostgreSQL/SQL Server directory generation.

### Mermaid diagram export

The supported Spring Boot runtime exposes Mermaid generation at:

`POST /api/v1/diagram/mermaid/canonical-json`

Upload one `*.schema.json` canonical snapshot or a ZIP containing a unique one-version-per-table canonical set. The response is a UTF-8 `.mmd` file. The `type` parameter accepts the existing `er` / `dependency` values and the new `conceptual-erd` view. See `docs/diagram/MERMAID-PRODUCTION-INTEGRATION.md` and `docs/diagram/CONCEPTUAL-ERD-PHASE1.md`.

## Physical DDL phase 1

SchemaForge can now enrich Oracle, PostgreSQL, Microsoft SQL Server, and Db2 for z/OS DDL with inline, non-executable physical-option blocks for DBA review. Existing active placement remains unchanged; new tuning/storage guidance is placed inside `/* ... */` at the DBMS-correct position in the statement. Phase 1 deliberately has no `REVIEW/APPLY` service mode and does not provision tablespaces, filegroups, stogroups, LOB storage, or partitions. Db2 for z/OS also renders `FOR MIXED DATA` for `CHAR`/`VARCHAR` and analyzes FK supporting-index coverage without creating indexes automatically. See `docs/PHYSICAL-PHASE1.md`.

### Physical Phase 1 corpus audit

For persisted canonical JSON, run the physical-only corpus audit independently of datatype-compatibility analysis:

```bat
mvnw.cmd ^
  -Dtest=PhysicalPhase1CorpusAuditIT ^
  -Dschemaforge.physical.audit.inputDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson\all" ^
  -Dschemaforge.physical.audit.outputDir="D:\SchemaForge-Physical-Audit\Json" ^
  -Dschemaforge.physical.audit.platforms=oracle,postgresql,sqlserver,db2zos ^
  -Dschemaforge.physical.audit.failOnViolations=false ^
  test
```

The runner writes `physical-phase1-audit-summary_*.txt/.csv`, `physical-phase1-audit-detail_*.csv`, and `physical-phase1-audit-findings_*.csv`. Full DDL generation is used only as an additional physical syntax/placement inspection when available; non-physical datatype mapping failures are reported as informational `DDL_UNAVAILABLE` entries and do not fail the physical audit.

### Physical Phase-1 audit for the new-format Word corpus

Materialize new-format `.docx` files with the standard parser only, then run the same physical-only audit over those snapshots:

```bat
mvnw.cmd -Dtest=WordDirectoryToCanonicalJsonIT ^
  -Dschemaforge.snapshot.word.inputDir="D:\Sample-Docs\Word\all" ^
  -Dschemaforge.snapshot.outputDir="D:\SchemaForge-Physical-Audit\Word-Canonical" ^
  -Dschemaforge.snapshot.parserMode=standard ^
  -Dschemaforge.snapshot.forceRefresh=true ^
  -Dschemaforge.snapshot.failOnErrors=false ^
  test

mvnw.cmd -Dtest=PhysicalPhase1CorpusAuditIT ^
  -Dschemaforge.physical.audit.inputDir="D:\SchemaForge-Physical-Audit\Word-Canonical" ^
  -Dschemaforge.physical.audit.outputDir="D:\SchemaForge-Physical-Audit\Word" ^
  -Dschemaforge.physical.audit.platforms=oracle,postgresql,sqlserver,db2zos ^
  -Dschemaforge.physical.audit.failOnViolations=false ^
  test
```

`manifest.json` is intentionally excluded by the physical corpus audit; only canonical schema snapshots are audited.

### Physical source-value rule
Physical metadata from Word/JSON is evidence, not truth. Valid source values may be retained in the activation-ready physical comment block. Invalid or inapplicable source values are not clamped or silently normalized; the generated SQL contains a `[SOURCE PHYSICAL ISSUE]` review line and a DBA placeholder where needed.

The current per-DBMS coverage and explicit exclusions are summarized in `docs/physical-phase1-coverage-matrix.md`. Context-dependent source values that are syntactically usable but cannot be proven correct from the canonical model are marked `[SOURCE PHYSICAL REVIEW]` instead of being silently accepted as truth. Db2 table blocks are storage-only; non-storage table semantics are excluded. PostgreSQL `toast_tuple_target` and Db2 `PIECESIZE` are source/profile-only reviewable options. Column, standalone-index, PK backing-index and UK backing-index physical values are object-scoped, with historical table-scoped index options retained only as a compatibility fallback. Operational CREATE INDEX directives use the separate `Index.buildOptions` channel.

### Db2/zOS index build options P7

`Index.buildOptions` now supports explicit Db2/zOS `DEFINE YES|NO` and `DEFER YES|NO`. No default is invented when either option is absent. `DEFINE NO` is emitted only when the index also carries explicit `DB2_INDEX_STOGROUP` or `INDEX_STOGROUP` physical evidence. `DEFER YES` is accompanied by an index-build review warning because a populated table can leave the index rebuild-pending. See `docs/PHYSICAL-FINAL-GAP-AUDIT.md` for the remaining vendor-specific scope decisions.

### Physical baselines

Physical DDL rendering/model work P0-P7 remains frozen on the 2026-08-17 P7 baseline. The complete Windows Maven regression was user-verified at `376` tests with `0` failures, `0` errors and `3` existing skipped live-database integration tests. See `docs/PHYSICAL-BASELINE-FREEZE.md` and `docs/PHYSICAL-FINAL-GAP-AUDIT.md`.

Physical Metadata Comparison P8-A/P8-B/P8-C is frozen by P8-D on the user-verified `399`-test baseline with `0` failures, `0` errors and `3` skipped tests. P8 adds expected-vs-actual Excel comparison only: actual database catalog state does not become design intent and is not fed into generated DDL. See `docs/P8D-PHYSICAL-COMPARISON-BASELINE-FREEZE.md`.

Remaining Oracle LOB, PostgreSQL table-access-method/partition, SQL Server TEXTIMAGE/FILESTREAM/partition, and Db2 recovery/organization/partition semantics require dedicated modeling rather than more generic physical options.

### Tables without primary keys

Create-table REST workflows support tables that have no explicit primary key. DDL and diagram/report artifacts are still generated. Metadata-based Oracle/SQL Server CRUD artifacts are not generated for such tables; the metadata CRUD summary records `SKIPPED_NO_PRIMARY_KEY`. SchemaForge does not infer a primary key from column names such as `*_ID`.

### MySQL P2-R7 strong table reconciliation

P2-R7 adds a conservative, in-memory reconciliation pass for the P2-R6 `STRONG_SAME_SCHEMA_*` candidates.
It requires independent datatype-family corroboration from non-blocked shared DB2 columns and rejects any
observed family conflict. It never mutates canonical JSON and never applies ambiguous or cross-schema matches.
See `docs/MYSQL-P2-R7-STRONG-TABLE-RECONCILIATION.md`.

### MySQL P2-R8 cross-schema reconciliation

P2-R8 evaluates only P2-R6 `REVIEW_EXACT_NAME_OTHER_SCHEMA` candidates. The other DB2 schema is used as
evidence only; the canonical schema/table identity is not rewritten. Acceptance requires one exact-name
candidate, strong bidirectional column coverage, independent datatype-family corroboration, and zero family
conflicts. Only supported exact-numeric metadata can fill missing numeric precision. Ambiguous candidates and
conflicts remain blocked. See `docs/MYSQL-P2-R8-CROSS-SCHEMA-RECONCILIATION.md`.
### MySQL P2-R9 remaining blocker / column reconciliation audit

P2-R9 reconstructs the exact post-P2-R8 blocked snapshot set and isolates residual
`METADATA_COLUMN_NOT_FOUND` cases. It searches only the exact DB2 schema/table and reports
unused MySQL-mappable exact-numeric column candidates using conservative normalized-name,
prefix, and edit-distance evidence tiers. P2-R9 is audit-only: it does not mutate canonical JSON
and does not apply any candidate automatically. See `docs/MYSQL-P2-R9-REMAINING-COLUMN-RECONCILIATION-AUDIT.md`.

### MySQL P2-R10 historical column-name corroboration audit

P2-R10 evaluates only P2-R9 `REVIEW_*` typo/prefix column candidates and requires the exact DB2 candidate
column name to be corroborated by another canonical snapshot of the same schema/table. Similarity alone,
historical coexistence, rename ambiguity, and datatype-family conflicts remain blocked. P2-R10 is audit-only.
See `docs/MYSQL-P2-R10-HISTORICAL-COLUMN-CORROBORATION-AUDIT.md`.



MySQL P2 final recovery/freeze details: `docs/MYSQL-P2-FINAL-RECOVERY-FREEZE.md`.
### ALTER/Migration M2 real MySQL pilot

`MySqlMigrationM2LivePilotIT` is an opt-in destructive integration test for the Flyway-compatible M2 path. It uses a dedicated `SCHEMAFORGE_*` database, verifies that CREATE generation remains unconditional for existing tables, executes a confirmed column + PK/FK/UK/CHECK/INDEX migration, and requires an empty post-migration live diff. See `docs/ALTER-MIGRATION-M2-LIVE-PILOT.md`.



### ALTER / Migration M2-R5 MySQL CHECK metadata note
MySQL 8.4 can expose CHECK string literals in `information_schema` with both charset introducers and backslash-escaped quote delimiters (for example `_utf8mb4\'A\'`). SchemaForge normalizes that catalog-only representation during live-vs-document CHECK comparison; generated CREATE and ALTER SQL are not rewritten by this normalization.

### ALTER / Migration M2 Oracle live pilot

`OracleMigrationM2LivePilotIT` is the Oracle counterpart of the validated MySQL M2 pilot. It uses only the fixed
`SF_M2_PARENT`/`SF_M2_CHILD` tables in an explicitly supplied test schema user, verifies unconditional CREATE
output, executes the confirmed Flyway-compatible migration, preserves seed data, and requires an empty residual
diff. Oracle PK/UK backing indexes are not modeled a second time as standalone indexes. See
`docs/ALTER-MIGRATION-M2-ORACLE-LIVE-PILOT.md`.

### ALTER/Migration M2 PostgreSQL live pilot

`PostgreSqlMigrationM2LivePilotIT` validates the M2 CREATE+ALTER path against a real PostgreSQL test database. It uses only `SF_M2_PARENT` and `SF_M2_CHILD` in the explicitly configured test schema, requires explicit destructive confirmation, verifies preserved data and a zero residual metadata diff, and removes the pilot tables at the end.

### ALTER/Migration M2-R7.1 PostgreSQL pilot assertion note
The PostgreSQL live pilot compares the generated CREATE marker case-insensitively. This is a test-only correction; production CREATE and ALTER behavior is unchanged.

### ALTER/Migration M2 SQL Server live pilot

`SqlServerMigrationM2LivePilotIT` validates the same M2 existing-table workflow against a real Microsoft SQL Server test database. It creates only `SF_M2_PARENT` and `SF_M2_CHILD` in the explicitly configured non-system schema, keeps normal CREATE DDL generation independent from table existence, executes the confirmed Flyway-compatible ALTER migration, verifies seed-data preservation, re-reads `sys.*` metadata, and requires a zero residual diff before cleanup. Constraint-owned PK/UNIQUE indexes are excluded from standalone index comparison, and default-constraint discovery/drop is kept inside one `sys.sp_executesql` batch so local-variable scope survives statement parsing.

### ALTER/Migration M2-R11 SQL Server dynamic default-drop fix

SQL Server default-constraint removal composes the runtime `ALTER TABLE ... DROP CONSTRAINT` statement into an `nvarchar(max)` variable and then executes that variable via `sys.sp_executesql`. This avoids the SQL Server grammar error caused by invoking `QUOTENAME(...)` directly in an `EXEC(...)` string expression while retaining the single outer batch required by JDBC/Flyway statement splitting.

### ALTER/Migration M2-R10 SQL Server dependent-object refresh

SQL Server can reject `ALTER COLUMN` even when a dependent index or constraint is logically unchanged. M2 therefore treats such objects as operational dependencies rather than semantic drift: table-owned unchanged PK/UK/FK/CHECK/INDEX dependencies are temporarily dropped before datatype/nullability changes and recreated afterward. SAFE output keeps both the dependency DROP/ADD and the guarded `ALTER COLUMN` commented until `confirmDestructive=true`. Incoming foreign keys owned by other tables still require deployment-wide DBA planning.

### ALTER/Migration M2-R12 SQL Server CHECK comparison
SQL Server catalog CHECK text is normalized for catalog-only formatting (ordinary `[IDENTIFIER]` brackets, scalar numeric parentheses such as `(0)`, redundant atomic predicate parentheses, and operator whitespace). Boolean grouping and string literals remain semantically significant.
