# Step 12 - DDL Execution Validation

## Added

- JDBC-based DDL execution validation for Oracle and PostgreSQL.
- Platform-aware removal of SQL*Plus and psql client commands before JDBC execution.
- CSV validation report.
- A dual-database validation runner supporting generation-only and explicit execution modes.
- Portable directory-generation test using `docs/samples/word` and `@TempDir`.

## Generate-only mode

```text
DirectoryDualDatabaseValidationRunner <input-directory> <output-directory> generate
```

This mode generates both dialects and writes a CSV report without connecting to a database.

## Execute mode

```text
-Dschemaforge.oracle.url=jdbc:oracle:thin:@//host:1521/service
-Dschemaforge.oracle.user=...
-Dschemaforge.oracle.password=...
-Dschemaforge.postgresql.url=jdbc:postgresql://host:5432/database
-Dschemaforge.postgresql.user=...
-Dschemaforge.postgresql.password=...

DirectoryDualDatabaseValidationRunner <input-directory> <output-directory> execute
```

DDL execution is never implicit. The `execute` mode and all connection settings must be supplied explicitly.

## Report columns

- source_document
- database
- sql_file
- status
- executed_statements
- message

Statuses:

- `GENERATED_ONLY`
- `PASSED`
- `FAILED`

## Tests

- `DirectoryDualDatabaseGenerationRunnerTest`
- `DirectoryDualDatabaseValidationRunnerTest`
- `SqlScriptStatementParserTest`
- `DdlValidationReportWriterTest`
