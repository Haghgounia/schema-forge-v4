# PostgreSQL PG-P9.1 - Catalog Semantic Normalization

PG-P9 deployed and reread the persistent 1,915-table PostgreSQL cohort successfully, but the first convergence pass reported 1,212 column residuals and zero object residuals.

Observed residuals:

- 1,065 `ALTER_NULLABILITY`: every case is a column participating in the table primary key. PostgreSQL catalogs primary-key columns as NOT NULL even when an older canonical snapshot preserves a nullable source flag.
- 147 `ALTER_DEFAULT`: PostgreSQL catalog representation only:
  - 94 `NULL::character varying` vs `NULL`
  - 36 `NULL::numeric` vs `NULL`
  - 6 `0` vs `- 0`
  - 6 `'-1'::integer` vs `-1`
  - 2 `'1'::numeric` vs `1.`
  - 2 `0` vs `00`
  - 1 `'999999999999'::bigint` vs `999999999999`

PG-P9.1 changes comparison semantics only. It does not change DDL generation, canonical snapshots, type mapping, or the deployed PostgreSQL database.

Normalization rules:

1. For PostgreSQL comparison, a desired primary-key column is effectively NOT NULL.
2. PostgreSQL typed NULL defaults (`NULL::type`) are equivalent to canonical `NULL`.
3. Numeric default literals are compared by numeric value when the desired column is numeric, including catalog casts and harmless sign/format variants.
4. Quoted text defaults are not globally collapsed into numbers.

The PG-P8 deployment must not be rerun for this change. Re-run PG-P9 read-only convergence against the existing persistent schema.
