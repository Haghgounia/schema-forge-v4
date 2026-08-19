# MySQL logical DDL P1

## Status

MySQL is a registered SchemaForge DDL platform in P1. The platform is available through
`DatabasePlatform.MYSQL`, `DialectFactory`, the offline CLI, Word/Legacy REST generation,
ZIP batch generation, and EA per-table generation.

P1 is deliberately **logical DDL only**. It does not add a MySQL JDBC metadata repository,
physical-design inference, live execution runner, metadata CRUD generation, or a MySQL-specific
offline SQL validator.

## No-guess rules

- Canonical `schema` is rendered as a MySQL database and bootstrapped with
  `CREATE DATABASE IF NOT EXISTS`.
- The parser-generated backing sequence used to preserve a logical Word `IDENTITY` is suppressed
  only when that sequence is referenced exclusively by identity columns. The identity intent is
  rendered with `AUTO_INCREMENT`.
- A genuine standalone sequence, or a sequence used by a non-identity default, remains visible and
  is rejected because P1 does not invent an equivalent MySQL construct.
- `AUTO_INCREMENT` is emitted only for an integer target type and only when the identity column is
  the first/only column of a primary key, unique key, or explicit index.
- Exact `NUMBER`/`NUMERIC`/`DECIMAL`/`DEC(p,0)` identity columns are converted to signed `BIGINT`
  only for `p <= 18`, where the full signed source range is preserved. Wider exact identities are
  blocked instead of truncated or reinterpreted as unsigned.
- Cross-DBMS `TABLESPACE` evidence is not translated into MySQL physical placement in P1.
- Unsupported target semantics such as standalone sequences and `ON ... SET DEFAULT` are rejected.

## Logical coverage

P1 renders:

- database/schema bootstrap;
- tables and columns;
- `AUTO_INCREMENT` identity;
- stored generated columns;
- primary, unique, check, and foreign-key constraints;
- ordinary, descending, unique, and functional indexes supported by the common generator;
- inline table and column comments;
- evidence-safe datatype and expression mappings.

MySQL grants are not emitted in P1 because the current project grant model is table-centric and the
platform-specific principal/provisioning contract has not yet been designed.

## Deferred phases

The following are intentionally deferred:

1. MySQL-specific offline DDL validation (P2 candidate).
2. Corpus-wide datatype/expression gap audit and mapper expansion.
3. JDBC metadata repository and Excel Actual-vs-Design comparison.
4. Confirmation-gated live execution against a disposable MySQL schema/database.
5. MySQL physical/storage profile (engine, row format, charset/collation, tablespace/partition and
   index/storage options) based only on explicit source/profile evidence.
6. MySQL metadata-driven CRUD, if approved as a project requirement.

## Regression entry points

Focused logical tests:

```text
MySqlTypeMapperTest
MySqlIdentifierRendererTest
MySqlDialectFoundationTest
MySqlDdlGeneratorTest
ApplicationDialectSelectionTest
OutputFileNamerTest
```

The normal full regression remains authoritative after P1 because MySQL is now visible to tests and
API paths that iterate over all registered `DatabasePlatform` values.
