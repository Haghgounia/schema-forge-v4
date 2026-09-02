# SchemaForge V4 4.0.0 - Frozen REST API Guide

OpenAPI JSON:

```text
GET /v3/api-docs
```

Swagger UI:

```text
/swagger-ui.html
```

The following HTTP surface is frozen by `ApiContractFreezeTest` for V4.

## Schema generation

Base path:

```text
/api/v1/generate
```

### Word

```text
POST /api/v1/generate/word
Consumes: multipart/form-data
Produces: application/zip
```

Parameters:

- multipart part `file` - required
- `includeAuditFields` - optional Boolean
- `auditProfile` - optional, default `AUTO`

### Legacy Word

```text
POST /api/v1/generate/legacy-word
Consumes: multipart/form-data
Produces: application/zip
```

Parameters:

- multipart part `file` - required
- `schema` - required
- `includeAuditFields` - optional Boolean
- `auditProfile` - optional, default `AUTO`

### Batch ZIP

```text
POST /api/v1/generate/zip
Consumes: multipart/form-data
Produces: application/zip
```

Parameters:

- multipart part `file` - required
- `includeAuditFields` - optional Boolean
- `auditProfile` - optional, default `AUTO`

### Enterprise Architect XML/XMI

```text
POST /api/v1/generate/ea-xml
Consumes: multipart/form-data
Produces: application/zip
```

Parameters:

- multipart part `file` - required
- `schema` - optional override
- `platform` - optional list/CSV selection
- `includeAuditFields` - optional Boolean
- `auditProfile` - optional, default `AUTO`

Supported platform names are:

```text
oracle
postgresql
db2zos
db2luw
sqlserver
mysql
```

Aliases accepted by the V4 parser include `postgres`, `pg`, `db2-zos`, `db2`, `zos`, `db2-luw`, `luw`, `sql-server`, `mssql`, and `sqlsrv`. Missing platform selection means all supported platforms for EA generation.

## Schema Conformance Audit

Base path:

```text
/api/v1/conformance
```

Report contract:

```text
schemaforge-schema-conformance/v3
```

### One table

```text
GET /api/v1/conformance/table?platform=<dbms>&schema=<schema>&table=<table>
Produces: application/json
```

Required parameters: `platform`, `schema`, `table`.

### Whole schema

```text
GET /api/v1/conformance/schema?platform=<dbms>&schema=<schema>
Produces: application/json
```

Required parameters: `platform`, `schema`.

Rule families in V3:

1. `STRUCTURAL`
2. `METADATA_CONVENTION`
3. `DATATYPE_COMPATIBILITY`
4. `CONSTRAINT_REFERENCES`
5. `KEY_CONSTRAINTS`
6. `REFERENTIAL_INTEGRITY`
7. `INDEX_COVERAGE`
8. `PHYSICAL_NAMING`

Schema Conformance is read-only. `compliant=false` may be caused only by advisory warnings; it does not mean the HTTP operation failed.

## Oracle metadata-based CRUD

```text
POST /api/v1/generate/oracle/crud
Consumes: application/json
Produces: application/sql
```

Request body:

```json
{"schema":"TSTSHMA","table":"CUSTOMERS"}
```

Requires live Oracle metadata configuration.

## SQL Server metadata-based CRUD

```text
POST /api/v1/generate/sqlserver/crud
Consumes: application/json
Produces: application/sql
```

Request body:

```json
{"schema":"TSTSHMA","table":"CUSTOMERS"}
```

Requires live SQL Server metadata configuration.

## Mermaid diagram export

```text
POST /api/v1/diagram/mermaid/canonical-json
Consumes: multipart/form-data
Produces: text/plain
```

Parameters:

| Parameter | Default | Notes |
|---|---|---|
| `file` | required | canonical JSON snapshot or ZIP |
| `type` | `er` | `er`, `dependency`, `conceptual-erd` |
| `scope` | `all` | `all`, `schema`, `table`, `table-with-dependencies`, `selected-tables` |
| `schema` | none | required for `schema` scope |
| `root` | none | `SCHEMA.TABLE`; required for table scopes |
| `selected` | none | comma-separated `SCHEMA.TABLE` values for selected-tables |
| `depth` | `1` | dependency depth |
| `includeColumns` | `true` | include columns |
| `includeDataTypes` | `true` | include data types |
| `includePrimaryKeys` | `true` | include PKs |
| `includeForeignKeys` | `true` | include physical FKs |
| `includeLogicalForeignKeys` | `false` | include logical FKs |

Response headers include diagram type, diagram scope, and input table count.

## REST error contract

Contract:

```text
schemaforge-rest-error/v1
```

Correlation header:

```text
X-SchemaForge-Request-Id
```

Frozen machine-readable error codes:

```text
INVALID_REQUEST
INPUT_IO_ERROR
MISSING_PART
MISSING_PARAMETER
MALFORMED_REQUEST
INVALID_PARAMETER
UNSUPPORTED_MEDIA_TYPE
NOT_ACCEPTABLE
METHOD_NOT_ALLOWED
NOT_FOUND
PAYLOAD_TOO_LARGE
SERVICE_UNAVAILABLE
INTERNAL_ERROR
```
