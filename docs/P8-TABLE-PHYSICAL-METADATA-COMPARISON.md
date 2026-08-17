# P8-A - Table Physical Metadata Comparison

## Purpose

P8-A closes the table-level comparison gap between physical design intent and the physical state of an existing database.

The two inputs remain independent:

```text
Specification / profile -> documentTable -> expected physical options
Existing database        -> databaseTable -> actual physical metadata
                                      |
                                      v
                         TABLE_PHYSICAL_COMPARE
```

Actual database metadata is comparison evidence only. P8-A does not promote database state into design intent and does not feed actual metadata back into generated DDL.

## Scope

P8-A adds table-level physical metadata acquisition to the existing JDBC metadata repositories for:

- Oracle
- PostgreSQL
- Microsoft SQL Server
- Db2 for z/OS

It also adds a vendor-aware physical comparator and the `TABLE_PHYSICAL_COMPARE` worksheet to the existing comparison workbook.

P8-A does not change:

- Word, legacy Word, JSON, or EA parsing
- canonical parser/cache provenance
- DDL dialects or physical renderers
- canonical snapshots
- REST/API signatures
- `Index.buildOptions`
- LOB, partition, recovery-policy, or storage-provisioning models

## Comparison statuses

| Status | Meaning |
|---|---|
| `MATCH` | Expected and actual comparable values are equivalent. |
| `MISMATCH` | Both values are available and differ. |
| `NOT_SPECIFIED` | The database has an actual value, but the design/profile does not specify one. |
| `NOT_AVAILABLE` | The design specifies a value, but the current catalog mapping cannot provide a comparable table-level actual value. |
| `REVIEW` | The database state is mixed, version-dependent, or cannot be represented safely as one table-level value. |

A property absent from both expected and actual inputs is omitted from the sheet.

## Oracle acquisition

`JdbcOracleMetadataRepository` reads persistent table state from `ALL_TABLES`, including table space, PCTFREE, PCTUSED, INITRANS, logging, compression, and parallel degree where the catalog exposes a comparable table-level value.

P8-A deliberately does not infer `SEGMENT CREATION` from current segment existence. Current segment state is not reliable evidence of the original DDL choice.

For catalog compression variants that are not represented by the current Oracle physical renderer, the actual value is retained as `REVIEW` rather than normalized to a different design option.

## PostgreSQL acquisition

`JdbcPostgreSqlMetadataRepository` reads the effective table space and `pg_class.reloptions`.

Only table physical options already modeled by SchemaForge are acquired:

- `fillfactor`
- `parallel_workers`
- `toast_tuple_target`

Operational settings such as autovacuum reloptions are intentionally excluded from P8-A.

## SQL Server acquisition

`JdbcSqlServerMetadataRepository` keeps the existing table data-space/filegroup value and reads base-table/clustered-index partition compression state from `sys.partitions`.

If all partitions have the same supported compression state, the value is comparable at table level. Mixed partition compression is represented as `REVIEW` because the current canonical model has no partition-scoped physical options.

XML compression is queried only when the server catalog exposes `xml_compression_desc`, preserving compatibility with SQL Server versions that do not have that column.

## Db2 for z/OS acquisition

`JdbcDb2ZosMetadataRepository` uses the table's `DBNAME`/`TSNAME` to read `SYSIBM.SYSTABLESPACE` and maps only current table-space state that can be represented by existing P1 physical keys.

Examples include buffer pool, DSSIZE, SEGSIZE, FREEPAGE, PCTFREE, PCTFREE FOR UPDATE, compression, group-buffer-pool caching, close/erase/logging rules, locking, MAXROWS, member-cluster state, insert algorithm, TRACKMOD, and storage group where directly represented.

P8-A deliberately does not reconstruct `PRIQTY` or `SECQTY` from current allocation quantities. Catalog allocation state and rounding do not prove the exact original DDL values.

## Excel output

The existing database-specific comparison workbook now includes:

```text
TABLE_PHYSICAL_COMPARE
```

Columns are:

```text
OBJECT | PROPERTY | EXPECTED | ACTUAL | STATUS | NOTE
```

This worksheet is additive. Existing historical comparison sheets and API output naming remain unchanged.

## Validation strategy

P8-A has focused tests for:

- vendor catalog-to-physical-option mappings;
- conservative exclusion/review rules;
- physical comparison statuses;
- workbook sheet/status output.

The previously user-verified baseline is 376 tests with 0 failures, 0 errors, and 3 existing skipped live-database tests. P8-A adds nine tests, so the expected full-suite count is 385 before any later test additions.
