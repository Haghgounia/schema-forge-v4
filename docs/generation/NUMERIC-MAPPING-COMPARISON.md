# Strategy-aware numeric comparison

SchemaForge uses the active numeric mapping strategy when comparing document datatypes with live database metadata and when building Excel comparison workbooks.

## SAFE

Only canonical exact signatures are equivalent. Examples:

- `NUMERIC(8)` equals `NUMERIC(8,0)`.
- `NUMERIC(8,0)` does not equal `INTEGER`.

## OPTIMIZED

The exact source numeric type is also equivalent to the lossless native integer selected by the dialect profile.

| Exact numeric precision | PostgreSQL | Db2 for z/OS |
|---|---|---|
| 1..4, scale 0 | `SMALLINT` | `SMALLINT` |
| 5..9, scale 0 | `INTEGER` | `INTEGER` |
| 10..18, scale 0 | `BIGINT` | `BIGINT` |

The comparison is deliberately strict:

- `NUMERIC(5,2)` is not equivalent to `INTEGER`.
- `NUMERIC(19,0)` is not equivalent to `BIGINT`.
- `NUMERIC(2,0)` is not equivalent to `INTEGER`; the expected optimized type is `SMALLINT`.
- Character and other unrelated types remain mismatches.

This removes false `METADATA_DATATYPE_MISMATCH` and inline `W:TYPE` findings that are caused only by the configured optimization strategy, while retaining genuine schema differences.
