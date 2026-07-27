# SchemaForge v4 - Multi-dialect schema generation

SchemaForge parses Word and Enterprise Architect specifications into one canonical model and generates DDL for Oracle, PostgreSQL, and Db2 for z/OS. DDL generation remains offline; optional Oracle, PostgreSQL, and Db2 for z/OS metadata connections are used only for validation and comparison workbooks.

```text
one input.docx -> one JSON model + one SQL file per registered dialect
```

The SQL file contains all objects related to that Word specification in one file:

- sequence (when the Word identity marker requires it)
- table and columns
- primary key
- check constraints
- unique constraints and supporting indexes
- foreign keys
- indexes
- table and column comments
- grants
- parser recovery warnings and generation footer

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

No JDBC connection or database metadata lookup is performed during parsing, validation, or DDL generation.

## Project documentation

Detailed project documentation is maintained under [`docs/`](docs/), including dialect, architecture, testing, and release material. The project-level release history remains in `CHANGELOG.md`.

## V4 DBMS-neutral DDL refactoring

`DdlGenerator` now contains only orchestration and canonical-model traversal. DBMS-specific rendering such as Oracle `USING INDEX`, `ENABLE`, `NOCACHE`, `NOCYCLE`, `NOORDER`, and `PROMPT` is implemented by `OracleDialect`. PostgreSQL uses the same generator through `PostgreSqlDialect` without Oracle syntax leakage.

## DBMS selection (V4)

The offline entry point supports Oracle, PostgreSQL, and Db2 for z/OS while preserving Oracle as the default:

```text
java -jar schema-forge.jar input.docx
java -jar schema-forge.jar input.docx postgresql
java -jar schema-forge.jar input.docx db2zos
java -jar schema-forge.jar input.docx output-directory db2zos
```

## Timestamped output files

Application-generated JSON and SQL files use a shared Gregorian date/time suffix:

```text
<input-base-name>_yyyyMMdd_HHmmss_SSS.json
<input-base-name>_yyyyMMdd_HHmmss_SSS.sql
```

## DBMS-aware generated file names

Generated artifacts use a shared Gregorian timestamp. SQL file names also identify the selected dialect:

```text
<input>_yyyyMMdd_HHmmss_SSS.json
<input>_yyyyMMdd_HHmmss_SSS.oracle.sql
<input>_yyyyMMdd_HHmmss_SSS.postgresql.sql
<input>_yyyyMMdd_HHmmss_SSS.db2zos.sql
```

### Foreign-key reference flags
- `TABLE/Y`: physical reference.
- `TABLE/N`: logical reference.
- `SCHEMA.TABLE/Y` and `SCHEMA.TABLE/N`: qualified references with an explicit schema.
- Spaces around the schema separator are tolerated because Word may expose `TIM. CALENDARS/N`.
- The final `S` in plural table names such as `LANGUAGES`, `COUNTRIES`, and `CALENDARS` is part of the identifier, not a flag.
- Oracle, PostgreSQL, and Db2 for z/OS DDL generate the `FOREIGN KEY` statement for both `/Y` and `/N`.
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

## Document-to-database Excel comparison

When metadata is enabled and the exact document table already exists in a target database, the REST response ZIP also contains a comparison workbook:

```text
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.oracle.xlsx
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.postgresql.xlsx
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.db2zos.xlsx
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

Object rows use `ADD`, `DROP`, `MODIFY` and `SAME`. A new single-column or composite index in the document is therefore shown explicitly as `ADD`, even when the indexed columns already exist in the database. All report cells have thin borders. The writer works with canonical `Table` models and the generic `Dialect` contract. Oracle, PostgreSQL, and Db2 for z/OS have JDBC metadata adapters. Db2 configuration is disabled by default and requires the IBM JCC driver at runtime.


## Db2 for z/OS core dialect

Select the dialect with `db2zos` (aliases: `db2-zos`, `db2`, `zos`). The current core phase generates tables, columns, sequences, identity/generated columns, primary/unique/check/foreign-key constraints, indexes, comments, and grants. `TABLESPACE` is rendered as Db2 `IN <table-space>` or `IN <database>.<table-space>`. Db2 primary and unique constraints now receive explicit unique enforcing indexes so explicitly managed table spaces do not leave incomplete table definitions. Db2 metadata comparison is available when configured. Offline preflight, a read-only connection probe, and an explicitly invoked disposable live integration test are documented in `docs/testing/DB2-ZOS-LIVE-VALIDATION.md`. See also `docs/dialects/DB2-ZOS-DIALECT.md` and `docs/dialects/DB2-ZOS-METADATA.md`.

Enable live Db2 metadata comparison only after adding the organization-approved IBM JCC driver to the runtime classpath:

```text
SCHEMAFORGE_METADATA_DB2ZOS_ENABLED=true
SCHEMAFORGE_METADATA_DB2ZOS_URL=jdbc:db2://db2-host:446/LOCATION
SCHEMAFORGE_METADATA_DB2ZOS_USERNAME=SCHEMAFORGE
SCHEMAFORGE_METADATA_DB2ZOS_PASSWORD=change-me
```

## Enterprise Architect XML/XMI input

The REST endpoint `POST /api/v1/generate/ea-xml` accepts Enterprise Architect XML/XMI 1.x exports. EA tables and columns are converted to the same canonical model used by Word input. Because one EA export may contain many tables, the response ZIP contains one Oracle, PostgreSQL, and Db2 for z/OS SQL file per table, comparison workbooks for dialects with available metadata, a consolidated `model.json`, a `manifest.json`, and dialect-specific `run_all.sql` files.

```text
oracle/<SCHEMA>.<TABLE>.oracle.sql
postgresql/<schema>.<table>.postgresql.sql
db2zos/<SCHEMA>.<TABLE>.db2zos.sql
comparison/oracle/<SCHEMA>.<TABLE>.oracle.xlsx
comparison/postgresql/<schema>.<table>.postgresql.xlsx
comparison/db2zos/<SCHEMA>.<TABLE>.db2zos.xlsx
oracle/run_all.sql
postgresql/run_all.sql
db2zos/run_all.sql
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
