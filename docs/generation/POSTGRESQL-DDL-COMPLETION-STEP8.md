# SchemaForge V4 - PostgreSQL DDL Completion Step 8

## Scope

This step improves PostgreSQL DDL rendering without changing the established package structure or canonical domain model.

## Added components

- `PostgreSqlTypeMapper`
- `PostgreSqlExpressionMapper`
- `PostgreSqlTypeMapperTest`
- `PostgreSqlExpressionMapperTest`

## Type mappings

- `VARCHAR2`, `NVARCHAR2` -> `VARCHAR`
- `NUMBER`, `DECIMAL` -> `NUMERIC`
- `CLOB`, `NCLOB`, `LONG` -> `TEXT`
- `BLOB`, `RAW`, `LONG RAW` -> `BYTEA`
- Oracle `DATE` -> PostgreSQL `TIMESTAMP`
- `XMLTYPE` -> `XML`
- `JSON` -> `JSONB`
- `BINARY_DOUBLE` -> `DOUBLE PRECISION`
- `BINARY_FLOAT` -> `REAL`

Length, precision and scale are preserved where PostgreSQL supports them.

## Expression mappings

- `SYSDATE` -> `CURRENT_TIMESTAMP`
- `SYSTIMESTAMP` -> `CURRENT_TIMESTAMP`
- `CURRENT_DATE()` -> `CURRENT_DATE`
- `NVL(a, b)` -> `COALESCE(a, b)`
- `SEQ_NAME.NEXTVAL` -> `nextval('seq_name')`
- `SCHEMA.SEQ_NAME.NEXTVAL` -> `nextval('schema.seq_name')`

The expression mapper is used for both default values and generated-column expressions.

## Verification

The main PostgreSQL generation path and related domain/dialect/generation classes compile with Java 21. A smoke test verified generated SQL for sequence defaults, date defaults and generated expressions.

The Maven wrapper could not run in the execution environment because Maven 3.9.16 could not be downloaded. Run `mvn test` in the project environment for the complete regression suite.
