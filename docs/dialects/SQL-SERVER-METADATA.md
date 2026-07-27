# Microsoft SQL Server metadata comparison

## Scope

`JdbcSqlServerMetadataRepository` is enabled only when `schemaforge.metadata.sqlserver.enabled=true`. It reads the database selected in the JDBC URL and converts SQL Server catalog rows into the same canonical `Table` model used by Word and Enterprise Architect input.

The adapter reads:

- user tables and schemas from `sys.tables` and `sys.schemas`
- columns and system/user types from `sys.columns` and `sys.types`
- defaults from `sys.default_constraints`
- identity and computed columns from `sys.identity_columns` and `sys.computed_columns`
- primary and unique constraints from `sys.key_constraints`
- foreign keys from `sys.foreign_keys` and `sys.foreign_key_columns`
- check constraints from `sys.check_constraints`
- rowstore indexes and included columns from `sys.indexes` and `sys.index_columns`
- table and column descriptions from `sys.extended_properties`
- filegroup/data-space names from `sys.data_spaces`

## Configuration

```yaml
schemaforge:
  metadata:
    sqlserver:
      enabled: ${SCHEMAFORGE_METADATA_SQLSERVER_ENABLED:false}
      url: ${SCHEMAFORGE_METADATA_SQLSERVER_URL:jdbc:sqlserver://localhost:1433;databaseName=master;encrypt=true;trustServerCertificate=true}
      username: ${SCHEMAFORGE_METADATA_SQLSERVER_USERNAME:sa}
      password: ${SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD:}
      driver-class-name: ${SCHEMAFORGE_METADATA_SQLSERVER_DRIVER:com.microsoft.sqlserver.jdbc.SQLServerDriver}
```

The project includes Microsoft JDBC Driver `13.4.0.jre11` as a runtime dependency. Java 21 uses the `jre11` artifact line.

## REST and EA output

When the exact table exists and the configured principal can see its metadata, Word/ZIP output includes:

```text
<SCHEMA>.<TABLE>_compare_<timestamp>.sqlserver.xlsx
```

EA output includes:

```text
comparison/sqlserver/<SCHEMA>.<TABLE>.sqlserver.xlsx
```

The workbook contains the standard table sheet plus:

```text
PRIMARY_KEY_COMPARE
FOREIGN_KEYS_COMPARE
INDEXES_COMPARE
UNIQUE_INDEXES_COMPARE
```

## Datatype handling

Native SQL Server types are preserved in the canonical metadata model. Important special cases are:

- SQL Server `date` is represented as `DATE_SQLSERVER`, so it is not confused with Oracle `DATE`.
- SQL Server `timestamp`/`rowversion` is represented as `SQLSERVER_TIMESTAMP` and renders as `ROWVERSION`.
- `varchar(max)`, `nvarchar(max)`, and `varbinary(max)` use internal max-type aliases that render back to the correct SQL Server spelling.
- `nvarchar`/`nchar` catalog byte lengths are divided by two to recover character length.
- temporal scale zero is preserved explicitly as `TIME(0)`, `DATETIME2(0)`, or `DATETIMEOFFSET(0)`.

## Numeric comparison

`SAFE` requires exact `DECIMAL(p,s)` signatures. `OPTIMIZED` additionally treats these lossless pairs as equivalent:

```text
DECIMAL(1..4,0)   <=> SMALLINT
DECIMAL(5..9,0)   <=> INT
DECIMAL(10..18,0) <=> BIGINT
```

Scale-bearing values and precision above 18 remain exact decimals.

## Boundaries

- Catalog visibility follows SQL Server metadata-visibility permissions.
- The adapter does not search other databases on the same instance.
- Only clustered and nonclustered rowstore indexes are imported into the generic index model.
- Partitioning, compression, columnstore/XML/spatial/hash indexes, temporal-table configuration, and memory-optimized details are not represented yet.
- Computed expressions are imported; `PERSISTED` is not yet represented in the canonical column model.
