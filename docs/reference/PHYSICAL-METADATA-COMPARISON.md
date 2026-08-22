# Physical Metadata Comparison

## 1. Purpose

P8 compares expected physical design intent with the current physical state of an existing database.

It does not reverse-engineer the database into the design model.

```text
Specification / profile                  Existing database
        |                                       |
        v                                       v
 expected physical                         JDBC catalog
        |                                       |
        v                                       v
 documentTable                           databaseTable
        |                                       |
        +----------------+  +-------------------+
                         v  v
               PhysicalMetadataComparator
                         |
                         v
                    Excel report
```

## 2. Frozen P8 scope

### P8-A

`TABLE_PHYSICAL_COMPARE` for:

- Oracle;
- PostgreSQL;
- SQL Server;
- Db2 for z/OS.

### P8-B

`INDEX_PHYSICAL_COMPARE` for:

- ordinary indexes;
- primary-key backing indexes;
- unique-key backing indexes;
- all four databases currently supported by the frozen physical-comparison contract: Oracle, PostgreSQL, SQL Server, and Db2 for z/OS. MySQL remains outside this physical scope.

### P8-C

`COLUMN_PHYSICAL_COMPARE` for PostgreSQL:

- `STORAGE`;
- `COMPRESSION`.

### P8-D

Final regression/documentation freeze. No source behavior was added in P8-D.

## 3. Comparison statuses

| Status | Meaning |
|---|---|
| `MATCH` | expected and actual comparable values are equivalent |
| `MISMATCH` | both sides are available and differ |
| `NOT_SPECIFIED` | actual database state exists but the design/profile does not specify the property |
| `NOT_AVAILABLE` | expected design exists but current metadata mapping cannot provide a comparable actual value |
| `REVIEW` | actual state is mixed, unknown, version-dependent, or cannot be represented safely by the current object-level model |

A property absent from both sides is omitted.

## 4. Vendor acquisition sources

The current repositories use vendor catalog metadata along these boundaries:

- Oracle: table/index catalog views such as `ALL_TABLES` / `ALL_INDEXES`;
- PostgreSQL: `pg_class`, `pg_attribute`, `pg_type`, index/access-method catalogs;
- SQL Server: `sys.indexes`, `sys.data_spaces`, `sys.stats`, `sys.partitions`;
- Db2 for z/OS: `SYSIBM.SYSTABLES`, `SYSIBM.SYSTABLESPACE`, `SYSIBM.SYSINDEXES`.

The repository maps only properties already represented safely by the frozen canonical physical model/comparator.

## 5. Conservative reverse-engineering rules

The comparison layer does not infer historical DDL when current state cannot prove it.

Examples:

- Oracle `SEGMENT_CREATED` is not converted to an original `SEGMENT CREATION` choice;
- PostgreSQL autovacuum reloptions are not treated as generic physical design;
- PostgreSQL `STORAGE DEFAULT` is compared using the effective column storage and the datatype default;
- compression is not reported as active for PostgreSQL storage modes that do not permit it;
- SQL Server mixed partition compression becomes `REVIEW`, not a fabricated single value;
- SQL Server FILLFACTOR 0 and 100 are treated as equivalent by the current comparator rule;
- Db2 current allocation quantities are not reverse-mapped to original PRIQTY/SECQTY DDL choices;
- Db2 `COPY`, `CLUSTER`, and null-key semantics are not flattened into generic physical state;
- operational index build options are never inferred from current database state.

## 6. Design/actual isolation guarantee

The current comparison architecture guarantees:

```text
actual database metadata -> comparison only
actual database metadata -X-> design mutation
actual database metadata -X-> DDL repair/generation
```

This separation is one of the frozen P8 invariants.
