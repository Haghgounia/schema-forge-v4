# PostgreSQL Word Regression Fix

The previous regression suite generated SQL files only with `OracleDialect`, so the packaged
`target/test-output` directory contained no `.postgresql.sql` files even though PostgreSQL unit
tests passed.

`PostgreSqlWordSpecificationRegressionTest` now runs the complete Word-to-SQL application
pipeline for every sample document using `DatabasePlatform.POSTGRESQL`.

Generated files are written to:

```text
target/test-output/postgresql/*.postgresql.sql
```

The test verifies that every file:

- is created and non-empty;
- has the `.postgresql.sql` suffix;
- contains `CREATE TABLE`;
- does not contain Oracle-only `PROMPT`, `ENABLE`, or `NOORDER` syntax.
