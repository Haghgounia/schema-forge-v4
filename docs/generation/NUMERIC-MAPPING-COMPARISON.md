# Strategy-aware numeric comparison

SchemaForge uses the active numeric mapping strategy when comparing document datatypes with live database metadata and when building Excel comparison workbooks.

## SAFE

Only canonical exact signatures are equivalent. Examples:

- `NUMERIC(8)` equals `NUMERIC(8,0)`.
- `NUMERIC(8,0)` does not equal `INTEGER`.

## OPTIMIZED

The exact source numeric type is also equivalent to the lossless native integer selected by the dialect profile.

| Exact numeric precision | PostgreSQL | Db2 for z/OS | SQL Server | MySQL |
|---|---|---|---|---|
| 1..4, scale 0 | `SMALLINT` | `SMALLINT` | `SMALLINT` | `SMALLINT` |
| 5..9, scale 0 | `INTEGER` | `INTEGER` | `INT` | `INT` |
| 10..18, scale 0 | `BIGINT` | `BIGINT` | `BIGINT` | `BIGINT` |

The comparison is deliberately strict:

- `NUMERIC(5,2)` is not equivalent to `INTEGER`.
- `NUMERIC(19,0)` is not equivalent to `BIGINT`.
- `NUMERIC(2,0)` is not equivalent to `INTEGER`; the expected optimized type is `SMALLINT`.
- Character and other unrelated types remain mismatches.

This removes false `METADATA_DATATYPE_MISMATCH` and inline `W:TYPE` findings that are caused only by the configured optimization strategy, while retaining genuine schema differences.


## Configuration

`SAFE` is the default. Spring/REST generation reads:

```yaml
schemaforge:
  numeric-mapping:
    strategy: SAFE # or OPTIMIZED
```

The equivalent environment variable is `SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY`.
CLI/integration paths that use `DialectFactory.create(platform)` also support the system property
`-Dschemaforge.numeric-mapping.strategy=OPTIMIZED`. The selected policy is recorded in Standard Manifest V1 at
`extensions.generationOptions.numericMapping.strategy`. Oracle target DDL remains Oracle-native `NUMBER`;
`OPTIMIZED` narrowing is applied only where the target has a lossless native integer type.
