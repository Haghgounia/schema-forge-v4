# Db2 for z/OS metadata comparison

SchemaForge can read live Db2 for z/OS catalog metadata and use the existing validation and Excel comparison pipeline for the `db2zos` dialect.

## Activation

The adapter is disabled by default. The IBM Data Server Driver for JDBC and SQLJ must be available on the application runtime classpath.

```text
SCHEMAFORGE_METADATA_DB2ZOS_ENABLED=true
SCHEMAFORGE_METADATA_DB2ZOS_URL=jdbc:db2://db2-host:446/LOCATION
SCHEMAFORGE_METADATA_DB2ZOS_USERNAME=SCHEMAFORGE
SCHEMAFORGE_METADATA_DB2ZOS_PASSWORD=change-me
SCHEMAFORGE_METADATA_DB2ZOS_DRIVER=com.ibm.db2.jcc.DB2Driver
```

`LOCATION` is the Db2 location name exposed by DDF. Keep the adapter disabled until the JCC driver and connection properties are available.

Equivalent YAML:

```yaml
schemaforge:
  metadata:
    db2zos:
      enabled: true
      url: jdbc:db2://db2-host:446/LOCATION
      username: SCHEMAFORGE
      password: change-me
      driver-class-name: com.ibm.db2.jcc.DB2Driver
```

## Catalog objects read

The repository is read-only and uses the public `SYSIBM` catalog:

| Metadata | Catalog source |
|---|---|
| tables, remarks, database and table space | `SYSIBM.SYSTABLES` |
| columns, types, defaults, identity and remarks | `SYSIBM.SYSCOLUMNS` |
| primary and unique constraints | `SYSIBM.SYSTABCONST` + `SYSIBM.SYSKEYS` |
| foreign keys | `SYSIBM.SYSRELS` + `SYSIBM.SYSFOREIGNKEYS` |
| parent-key columns | `SYSIBM.SYSINDEXES` + `SYSIBM.SYSKEYS` |
| check constraints | `SYSIBM.SYSCHECKS` |
| indexes and include columns | `SYSIBM.SYSINDEXES` + `SYSIBM.SYSKEYS` |

Catalog reads end with `WITH UR`; the repository does not update catalog data and does not cache table results.

## Generated REST artifacts

When the exact document table exists and Db2 metadata is enabled, Word/ZIP REST generation adds:

```text
<SCHEMA>.<TABLE>_compare_<timestamp>.db2zos.xlsx
```

EA XML generation adds one workbook per existing table:

```text
comparison/db2zos/<SCHEMA>.<TABLE>.db2zos.xlsx
```

The workbook uses the same sheets as Oracle and PostgreSQL:

```text
PRIMARY_KEY_COMPARE
FOREIGN_KEYS_COMPARE
INDEXES_COMPARE
UNIQUE_INDEXES_COMPARE
```

Numeric comparison honors the configured `SAFE` or `OPTIMIZED` strategy.

## Current boundaries

- Normal REST generation reads metadata and creates comparison workbooks but does not execute generated DDL. Explicit live validation utilities are documented separately in `../testing/DB2-ZOS-LIVE-VALIDATION.md`.
- Expression-based index expressions are not reconstructed from the catalog in this phase. A pure expression index without ordinary key columns is skipped.
- A blank `SYSKEYS.ORDERING` value with a column name is treated as an `INCLUDE` column. Random (`R`) index ordering is represented as ascending because the canonical model currently supports only ascending and descending directions.
- `UNIQUE WHERE NOT NULL` and other Db2-only unique-index semantics are represented as a canonical unique index without the vendor-specific predicate.
- Generated-column expressions are not reconstructed. Identity columns are detected from `SYSCOLUMNS.DEFAULT` values `I` and `J`.
- `schemaExists` is inferred from visible objects in `SYSIBM.SYSTABLES`, because the comparison requirement is object-oriented.
- Catalog visibility depends on the privileges of the configured metadata account.
- `VARBIN` is represented as `VARBINARY`; installations that use the special Unicode-in-EBCDIC representation should verify those columns against their local catalog conventions.

## Minimum verification

After configuration, generate a document whose table already exists on Db2. The ZIP should contain both:

```text
<input>_<timestamp>.db2zos.sql
<SCHEMA>.<TABLE>_compare_<timestamp>.db2zos.xlsx
```

If the SQL file exists but the workbook does not, inspect application logs for table/schema lookup messages and verify catalog privileges and identifier casing.
