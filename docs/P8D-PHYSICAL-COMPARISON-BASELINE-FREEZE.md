# P8-D - Physical Metadata Comparison Baseline Freeze

## Purpose

P8-D freezes the SchemaForge V4 physical metadata comparison workstream after the user-verified P8-C regression.

This step adds no new production behavior. It records the final expected-vs-actual physical comparison contract, coverage, exclusions, and verified regression baseline for subsequent development.

## Final comparison architecture

```text
Specification / profile
        |
        v
  documentTable
  (expected physical)
        |
        +--------------------+
                             |
Existing database            v
        |             PhysicalMetadataComparator
        v                    |
 JDBC metadata               v
        |             Excel comparison workbook
        v
  databaseTable
  (actual physical)
```

The two sides remain independent:

- `documentTable` represents design/specification intent.
- `databaseTable` represents the current database state acquired from vendor catalogs.
- actual database state is never promoted into design intent;
- actual database state is never used to generate or repair DDL;
- persistent physical options are compared independently from operational `Index.buildOptions`.

## Frozen P8 coverage

### P8-A - Table physical metadata

Supported for all four database platforms:

- Oracle
- PostgreSQL
- Microsoft SQL Server
- Db2 for z/OS

Workbook sheet:

```text
TABLE_PHYSICAL_COMPARE
```

### P8-B - Index / PK / UK physical metadata

Supported for all four database platforms for:

- ordinary indexes;
- primary-key backing indexes;
- unique-key backing indexes.

Workbook sheet:

```text
INDEX_PHYSICAL_COMPARE
```

The sheet uses `SCOPE = INDEX | PRIMARY_KEY | UNIQUE_KEY`.

### P8-C - Column physical metadata

Current frozen scope is PostgreSQL only because the frozen canonical column physical model currently has explicit persistent column-level physical semantics only for PostgreSQL `STORAGE` and `COMPRESSION`.

Workbook sheet:

```text
COLUMN_PHYSICAL_COMPARE
```

Oracle, SQL Server, and Db2 for z/OS do not receive invented generic column-level physical mappings.

## Comparison statuses

The physical comparison contract is frozen to:

| Status | Meaning |
|---|---|
| `MATCH` | expected and actual comparable values are equivalent |
| `MISMATCH` | both comparable values are available and differ |
| `NOT_SPECIFIED` | actual database state is available but design/profile does not specify the property |
| `NOT_AVAILABLE` | design specifies a property but the current catalog mapping cannot provide a comparable actual value |
| `REVIEW` | current database state is mixed, unknown, version-dependent, or cannot be safely represented by the current object-level model |

A property absent from both sides is omitted.

## Frozen conservative rules

The comparison layer must not reverse-engineer historical DDL from current state when the catalog cannot prove it.

Examples retained by the freeze:

- Oracle `SEGMENT_CREATED` is not reverse-mapped to `SEGMENT CREATION IMMEDIATE/DEFERRED`.
- PostgreSQL autovacuum reloptions are not treated as generic physical design options.
- PostgreSQL `STORAGE DEFAULT` is compared using the effective column storage and the type default rather than by string equality alone.
- PostgreSQL compression is not reported as active when the effective storage mode does not allow compression.
- SQL Server mixed partition compression becomes `REVIEW`; no partition value is selected as the object-level truth.
- SQL Server `FILLFACTOR 0` and `100` compare as equivalent according to the implemented vendor-aware comparison rule.
- Db2 current allocation quantities are not reverse-mapped to original `PRIQTY` / `SECQTY` DDL values.
- Db2 `COPY`, `CLUSTER`, null-key semantics, and similar recovery/organization semantics are not flattened into generic physical comparison.
- `ONLINE`, `CONCURRENTLY`, `RESUMABLE`, `MAXDOP`, `SORT_IN_TEMPDB`, Db2 `DEFINE/DEFER`, and other operational index build directives are not inferred from existing database objects.

## Excel workbook contract

The historical logical/object comparison sheets remain unchanged. P8 adds only these physical comparison sheets:

```text
TABLE_PHYSICAL_COMPARE
INDEX_PHYSICAL_COMPARE
COLUMN_PHYSICAL_COMPARE
```

Physical rows are comparison evidence. They do not mutate the canonical design side.

## User-verified regression evidence

P8-A verification:

```text
Tests run: 385
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Finished: 2026-08-17T06:38:30-07:00
```

P8-B verification:

```text
Tests run: 394
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Finished: 2026-08-17T07:13:32-07:00
```

P8-C / final P8 verification:

```text
Tests run: 399
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Finished: 2026-08-17T07:57:40-07:00
```

The three skipped tests are the existing environment-dependent live database integration tests; P8-D does not change them.

## P8-D freeze guarantees

P8-D is documentation/baseline finalization only:

- no production Java changes;
- no test-code changes;
- no parser or parser-version changes;
- no snapshot/model-version changes;
- no datatype mapping changes;
- no DDL dialect or physical renderer changes;
- no REST/API changes;
- no legacy Word cache rebuild requirement.

The `src` tree in the P8-D package must be byte-for-byte identical to the user-verified P8-C source tree.

## Explicit deferred scope

The following remain outside the frozen P8 comparison model until dedicated source/domain models are justified:

- Oracle LOB / SecureFiles / BasicFiles storage and LOB-segment options;
- partition/subpartition-specific physical state;
- SQL Server `TEXTIMAGE_ON`, `FILESTREAM_ON`, partition scheme, and partition-specific placement/compression when it cannot be collapsed safely;
- PostgreSQL table access method as an explicit design concept;
- Db2 recovery and data-organization semantics such as `COPY` and `CLUSTER`;
- executable tablespace/filegroup/stogroup provisioning;
- reverse-engineering historical CREATE/REBUILD operation options.

## Baseline rule

Subsequent SchemaForge V4 work should start from the P8-D frozen package. P0-P7 remains the frozen DDL-rendering baseline; P8-D is the frozen physical expected-vs-actual comparison baseline built on top of it.
