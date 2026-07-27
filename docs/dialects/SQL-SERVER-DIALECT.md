# Microsoft SQL Server core dialect

## Scope

The `sqlserver` platform is registered in `DatabasePlatform` with aliases `sql-server`, `mssql`, and `sqlsrv`. It uses the shared canonical model and `DdlGenerator` and currently supports:

- tables and columns
- `SAFE` and `OPTIMIZED` exact-numeric mapping
- sequences and Oracle `NEXTVAL` expression conversion
- identity and computed columns
- primary keys, unique constraints, check constraints, and foreign keys
- standalone, included-column, and filtered indexes
- table and column descriptions through `MS_Description` extended properties
- configured grants
- Word/ZIP REST artifacts and EA per-table artifacts
- SQLCMD-compatible EA `run_all.sql` files

## Artifact names

Word and Word-ZIP REST generation adds:

```text
<input>_<timestamp>.sqlserver.sql
```

EA XML/XMI generation adds:

```text
sqlserver/<SCHEMA>.<TABLE>.sqlserver.sql
sqlserver/run_all.sql
```

The run-all file uses SQLCMD include commands:

```sql
:r SCHEMA.TABLE.sqlserver.sql
```

## Numeric mapping

The default strategy remains `SAFE`:

| Source | SQL Server |
|---|---|
| `NUMBER(p)` | `DECIMAL(p,0)` |
| `NUMBER(p,s)` | `DECIMAL(p,s)` |
| unbounded `NUMBER` | `DECIMAL(38,0)` |

With `OPTIMIZED` enabled:

| Source | SQL Server |
|---|---|
| `NUMBER(1..4,0)` | `SMALLINT` |
| `NUMBER(5..9,0)` | `INT` |
| `NUMBER(10..18,0)` | `BIGINT` |
| `NUMBER(19..38,0)` | `DECIMAL(p,0)` |
| `NUMBER(p,s>0)` | `DECIMAL(p,s)` |

Precision above 38 is rejected instead of being truncated.

## Other datatype mappings

Representative mappings include:

| Source | SQL Server |
|---|---|
| `VARCHAR2(n)` | `VARCHAR(n)` or `VARCHAR(MAX)` |
| `NVARCHAR2(n)` | `NVARCHAR(n)` or `NVARCHAR(MAX)` |
| `CLOB` | `VARCHAR(MAX)` |
| `NCLOB` | `NVARCHAR(MAX)` |
| `BLOB` | `VARBINARY(MAX)` |
| `RAW(n)` | `VARBINARY(n)` or `VARBINARY(MAX)` |
| Oracle `DATE` | `DATETIME2(0)` |
| Oracle `TIMESTAMP(p)` | `DATETIME2(p)` |
| `TIMESTAMP WITH TIME ZONE` | `DATETIMEOFFSET(p)` |
| `XMLTYPE` | `XML` |
| `JSON` | `NVARCHAR(MAX)` |
| `BOOLEAN` | `BIT` |

Oracle `TIMESTAMP` is never mapped to SQL Server `timestamp`, because that SQL Server type is a row-version binary value. The explicit canonical aliases `ROWVERSION` and `SQLSERVER_TIMESTAMP` map to `ROWVERSION`.

## Expressions and generated values

The first core phase converts these common expressions:

```text
SCHEMA.SEQ.NEXTVAL -> NEXT VALUE FOR SCHEMA.SEQ
NVL(a,b)           -> COALESCE(a,b)
SYSDATE            -> SYSDATETIME()
SYSTIMESTAMP       -> SYSDATETIMEOFFSET()
SYS_GUID()         -> NEWID()
```

A logical identity column without a sequence default is rendered as:

```sql
IDENTITY(1,1)
```

A computed column omits the canonical datatype and explicit nullability:

```sql
EFFECTIVE_STATUS AS (COALESCE(STATUS, 0))
```

## Physical placement and indexes

Canonical `TABLESPACE`, `INDEX_TABLESPACE`, and `PK_TABLESPACE` values are treated as SQL Server filegroup names and rendered with `ON <filegroup>`.

SQL Server index names are scoped to their table and are therefore not schema-qualified. Included columns, filter predicates, and filegroup placement are emitted in SQL Server order:

```sql
CREATE INDEX IX_CUSTOMERS_STATUS
  ON CRM.CUSTOMERS(STATUS DESC)
  INCLUDE (CUSTOMER_CODE)
  WHERE STATUS = 1
  ON INDEX_FG;
```

## Comments

Table and column descriptions use `sys.sp_addextendedproperty` with the standard `MS_Description` property. Literal apostrophes are escaped and object names use the same normalized casing as generated DDL identifiers.

## Metadata and validation

The conditional SQL Server metadata adapter reads user-table definitions from documented `sys.*` catalog views and enables REST/EA comparison workbooks. It covers columns, defaults, identity/computed columns, descriptions, primary and unique constraints, foreign keys, check constraints, included/filtered rowstore indexes, and the base table data space/filegroup.

The SQL Server completion pack also provides:

- `SqlServerOfflineDdlValidator` for deterministic static checks
- `SqlServerConnectionProbeService` for read-only driver, server, database, schema, and catalog verification
- `SqlServerValidationRunner` for staged generate, probe, and confirmation-gated execute validation
- `SqlServerLiveIT` for disposable-schema execution, metadata round-trip, and Excel `SAME` verification
- strategy-aware numeric comparison for `DECIMAL` versus `SMALLINT`, `INT`, and `BIGINT`

See `SQL-SERVER-METADATA.md` and `../testing/SQL-SERVER-VALIDATION.md`.

## Current boundaries

- Metadata is read only from the database selected in the JDBC URL; cross-database object discovery is not attempted.
- Rowstore clustered/nonclustered indexes are imported. XML, spatial, hash, and columnstore indexes are outside the current canonical index model.
- Schema creation, partition schemes, compression, temporal tables, memory-optimized tables, and vendor-specific index options are not inferred from the current canonical model.
- Computed-column persistence is visible to the repository query but the canonical model currently preserves only the expression.
- Existing generic grants are emitted, but principal existence and permissions must be validated in the target environment.

## Configuration

```text
schemaforge.numeric-mapping.strategy=SAFE|OPTIMIZED
SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY=SAFE|OPTIMIZED
SCHEMAFORGE_METADATA_SQLSERVER_ENABLED=true|false
SCHEMAFORGE_METADATA_SQLSERVER_URL=jdbc:sqlserver://host:1433;databaseName=APPDB;encrypt=true
SCHEMAFORGE_METADATA_SQLSERVER_USERNAME=...
SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD=...
```

The default JDBC driver class is `com.microsoft.sqlserver.jdbc.SQLServerDriver`.
