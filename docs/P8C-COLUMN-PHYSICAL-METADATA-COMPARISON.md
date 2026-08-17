# P8-C - Column Physical Metadata Comparison

P8-C extends the P8 expected-vs-actual physical comparison to column-scoped persistent physical state.

## Scope

P8-C is comparison-only. It does not change parser behavior, snapshots, DDL generation, dialect rendering, or design intent.

The workbook adds `COLUMN_PHYSICAL_COMPARE` with columns:

`COLUMN | PROPERTY | EXPECTED | ACTUAL | STATUS | NOTE`

Statuses remain `MATCH`, `MISMATCH`, `NOT_SPECIFIED`, `NOT_AVAILABLE`, and `REVIEW`.

## PostgreSQL acquisition

The current canonical column physical model has persistent column-level storage options only for PostgreSQL, so P8-C acquisition is intentionally PostgreSQL-only.

`pg_attribute.attstorage` supplies the current column storage policy. The catalog codes are mapped to `PLAIN`, `EXTERNAL`, `MAIN`, or `EXTENDED`.

`pg_attribute.attcompression` supplies the current compression method. `p` maps to `PGLZ`, `l` maps to `LZ4`, and the catalog default marker maps to `DEFAULT`.

`pg_type.typstorage` is also read as comparison evidence for `STORAGE DEFAULT`. PostgreSQL stores the effective column storage policy in `attstorage`; therefore, a design value of `DEFAULT` is considered a match when the effective `attstorage` value equals the type's `typstorage` default. The Excel row still displays the effective actual mode.

Compression is not reported as an active physical value when current storage is `PLAIN` or `EXTERNAL`, because PostgreSQL ignores `attcompression` when `attstorage` does not allow compression.

Unknown future catalog codes are never normalized to a guessed value. They are surfaced as `REVIEW`.

Official references:
- PostgreSQL 18 `pg_attribute`: https://www.postgresql.org/docs/18/catalog-pg-attribute.html
- PostgreSQL 18 `pg_type`: https://www.postgresql.org/docs/18/catalog-pg-type.html
- PostgreSQL 18 `CREATE TABLE`: https://www.postgresql.org/docs/18/sql-createtable.html

## Other vendors

Oracle, SQL Server, and Db2 for z/OS do not currently have a generic column-level persistent physical option represented by the frozen SchemaForge V4 column physical model. P8-C therefore does not invent vendor mappings for those platforms. Their `COLUMN_PHYSICAL_COMPARE` sheet remains structurally present but contains no vendor-specific physical rows unless future dedicated modeling is introduced.

## Explicit exclusions

P8-C does not add LOB storage, FILESTREAM/TEXTIMAGE placement, partition storage, recovery policy, or operational build options. Those require dedicated modeling and remain outside this phase.
