# Db2 for z/OS core dialect

## Scope

The `db2zos` platform is registered in `DatabasePlatform` and uses the shared canonical model and `DdlGenerator`. The current phase supports:

- tables and columns
- exact and optionally optimized numeric mapping
- sequences and Oracle `NEXTVAL`/`CURRVAL` expression conversion
- identity and generated columns
- primary keys, unique constraints, check constraints, and foreign keys
- ordinary and quoted identifier rendering
- standalone indexes
- table and column comments
- configured grants
- Word/ZIP REST artifacts and EA per-table artifacts

## Names and physical placement

The command-line name and artifact suffix are `db2zos`. Unquoted ordinary identifiers are emitted in uppercase. Reserved or otherwise unsafe identifiers are delimited with double quotes.

A canonical `TABLESPACE` value is rendered as one of:

```sql
IN APPTS
IN APPDB.APPTS
```

Schema names are not assumed to be Db2 database names. Therefore SchemaForge does not derive `DATABASE.TABLESPACE` from the table schema. Index-space physical attributes are deliberately not inferred from the generic `INDEX_TABLESPACE` option.

## Referential actions

Db2 for z/OS output supports `ON DELETE RESTRICT`, `CASCADE`, and `SET NULL`. `ON DELETE SET DEFAULT` is rejected. Db2 has no foreign-key `ON UPDATE` clause; an explicit canonical `ON UPDATE RESTRICT` is omitted as the compatible behavior, while cascading or value-changing update actions are rejected.

## Current limitations

- No production Db2 catalog metadata repository yet.
- No Db2 JDBC execution-validation runner yet.
- `run_all.sql` for EA records dependency order as comments because execution/include commands depend on the selected Db2 client.
- Db2-specific index-space, buffer-pool, STOGROUP, partitioning, CCSID, and universal-table-space options are deferred.
- For explicitly provisioned table spaces, DBA review might be required for primary/unique supporting indexes.

## Configuration

```text
schemaforge.numeric-mapping.strategy=SAFE|OPTIMIZED
SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY=SAFE|OPTIMIZED
```

See `DB2-ZOS-NUMERIC-MAPPING.md` for exact precision boundaries.
