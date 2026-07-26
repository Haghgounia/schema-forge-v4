# SchemaForge v4 - Offline Word to Oracle DDL

SchemaForge processes each Word table specification without connecting to a database.

```text
one input.docx -> one complete input.sql + one input.json
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
java -jar target/schema-forge-v3-3.0.0-SNAPSHOT.jar input.docx
```

Create outputs in a selected directory:

```bash
java -jar target/schema-forge-v3-3.0.0-SNAPSHOT.jar input.docx output-directory
```

No JDBC connection or database metadata lookup is performed during parsing, validation, or DDL generation.

## Project documentation

Detailed project documentation is maintained under [`doc/`](doc/):

- `doc/generation/ORACLE-OFFLINE-DDL-COMPLETION.md`
- `doc/roadmap/GAP-MATRIX.md`
- `doc/roadmap/CHANGELOG.md`

## V4 DBMS-neutral DDL refactoring

`DdlGenerator` now contains only orchestration and canonical-model traversal. DBMS-specific rendering such as Oracle `USING INDEX`, `ENABLE`, `NOCACHE`, `NOCYCLE`, `NOORDER`, and `PROMPT` is implemented by `OracleDialect`. PostgreSQL uses the same generator through `PostgreSqlDialect` without Oracle syntax leakage.

## DBMS selection (V4)

The offline entry point supports Oracle and PostgreSQL while preserving Oracle as the default:

```text
java -jar schema-forge.jar input.docx
java -jar schema-forge.jar input.docx postgresql
java -jar schema-forge.jar input.docx output-directory postgresql
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
```

### Foreign-key reference flags
- `TABLE/Y`: physical reference.
- `TABLE/N`: logical reference.
- `SCHEMA.TABLE/Y` and `SCHEMA.TABLE/N`: qualified references with an explicit schema.
- Spaces around the schema separator are tolerated because Word may expose `TIM. CALENDARS/N`.
- The final `S` in plural table names such as `LANGUAGES`, `COUNTRIES`, and `CALENDARS` is part of the identifier, not a flag.
- Oracle and PostgreSQL DDL generate the `FOREIGN KEY` statement for both `/Y` and `/N`.
- Singular table names are preserved unchanged and receive `W:TABLE-PLURAL` only.
