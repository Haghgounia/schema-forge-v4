# SchemaForge REST API

## Run

```bash
mvn spring-boot:run
```

Server: `http://localhost:9090`  
Swagger UI: `http://localhost:9090/swagger-ui.html`  
OpenAPI JSON: `http://localhost:9090/v3/api-docs`

## Current endpoints

| Method | Endpoint | Input | Response |
|---|---|---|---|
| POST | `/api/v1/generate/word` | multipart `file`; optional audit parameters | `application/zip` |
| POST | `/api/v1/generate/legacy-word` | multipart `file`, required `schema`; optional audit parameters | `application/zip` |
| POST | `/api/v1/generate/zip` | multipart `file`, ZIP containing standard `.docx` files; optional audit parameters | `application/zip` |
| POST | `/api/v1/generate/ea-xml` | multipart `file`, EA `.xml`/`.xmi`; optional `schema` and audit parameters | `application/zip` |
| POST | `/api/v1/generate/oracle/crud` | JSON `schema` + `table` | `application/sql` |
| POST | `/api/v1/generate/sqlserver/crud` | JSON `schema` + `table` | `application/sql` |
| POST | `/api/v1/diagram/mermaid/canonical-json` | multipart canonical JSON or ZIP plus diagram options | `text/plain` Mermaid artifact |

## Generation DBMS set

The standard Word, Legacy Word, ZIP-batch and EA generation pipelines use the registered five-database set:

- Oracle
- PostgreSQL
- Db2 for z/OS
- Microsoft SQL Server
- MySQL

The same prepared canonical model is supplied to every registered dialect. CREATE DDL remains unconditional; when live metadata is enabled and an existing table differs, Flyway-compatible migration output is emitted as an additional artifact rather than replacing CREATE DDL.

### Audit options shared by generation endpoints

The `word`, `legacy-word`, `zip`, and `ea-xml` endpoints accept:

- `includeAuditFields=true|false` — when omitted, the configured `schemaforge.standards.audit.enabled` value is used;
- `auditProfile=AUTO|CREATED_UPDATED|CREATED_LAST_MODIFIED` — default `AUTO`.

`CREATED_UPDATED` represents `CREATED_AT`, `CREATED_BY`, `UPDATED_AT`, `UPDATED_BY`. `CREATED_LAST_MODIFIED` represents `CREATED_DATE`, `CREATED_BY`, `LAST_MODIFIED_DATE`, `LAST_MODIFIED_BY`.

When audit enrichment is enabled, existing source audit columns are preserved and only missing members of the effective family are appended. `AUTO` first detects the family already used by the table/source model. It never adds the other family merely because SchemaForge has a different configured default. A table containing evidence from both families returns HTTP 400 with `AUDIT_PROFILE_CONFLICT`. The requested audit options are recorded under `manifest.json -> extensions.generationOptions.audit`.

## Word and Legacy Word

### Standard Word

```http
POST /api/v1/generate/word
Content-Type: multipart/form-data
```

Multipart field: `file`.

### Legacy Word

```http
POST /api/v1/generate/legacy-word?schema=TSTSHMA
Content-Type: multipart/form-data
```

Multipart field: `file`. The `schema` query parameter is required for the legacy parser path.

Both paths prepare one canonical schema and generate five-dialect DDL plus the additional enabled artifacts such as comparison workbooks, migration output, metadata-based CRUD, canonical JSON, Mermaid and Graphviz outputs. Each returned ZIP also contains one root `manifest.json` using `schemaforge-manifest/v1`.

## ZIP batch

```http
POST /api/v1/generate/zip
Content-Type: multipart/form-data
```

The ZIP batch path processes standard `.docx` specifications. Legacy `.doc` files are not routed through the batch endpoint.

Each Word document is isolated. Temporary Office files such as `~$*.docx`, hidden dot files, AppleDouble files, and `__MACOSX` entries are ignored. A malformed or non-specification Word document does not abort the whole request; successful documents are generated and the returned archive includes:

- `reports/batch-generation-summary.csv` — one row per processable Word document with `SUCCESS` or `FAILED` status;
- `reports/batch-generation-errors.log` — full stack traces for failed documents;
- `manifest.json` — one package-level Standard Manifest V1 covering final collision-resolved artifact paths. Child documents do not receive nested manifests.

A ZIP with no processable `.docx` file returns HTTP 400.

## Enterprise Architect XML/XMI

```http
POST /api/v1/generate/ea-xml
Content-Type: multipart/form-data
```

Multipart field: `file`; optional query parameter: `schema`.

EA exports frequently omit the physical schema. Configure the fallback schema in `application.yml`:

```yaml
schemaforge:
  ea:
    default-schema: FEE
```

The value may also be supplied with `SCHEMAFORGE_EA_DEFAULT_SCHEMA`. An explicit request/schema or EA schema/owner value takes precedence according to the importer configuration.

The EA importer reads table classes, ordered columns, datatype/length/precision/scale, nullability, descriptions, primary keys, foreign keys and standalone indexes. Imported objects enter the same canonical model used by Word input.

EA output follows the C5 common artifact-first layout: per-table DDL under `ddl/<platform>/`, a timestamped canonical snapshot under `model/*.schema.json`, dialect run-all scripts under `scripts/<platform>/`, comparison workbooks under `comparison/<platform>/` when metadata is available, and root `manifest.json`. The root manifest uses the same `schemaforge-manifest/v1` contract as Word, Legacy Word, and ZIP Batch; endpoint URLs remain unchanged.

## Oracle metadata CRUD package

```http
POST /api/v1/generate/oracle/crud
Content-Type: application/json
Accept: application/sql
```

```json
{
  "schema": "BIM",
  "table": "PROVINCES"
}
```

The endpoint reads live Oracle metadata and generates `PKG_<TABLE>` with `CREATE_ROW`, `UPDATE_ROW`, `DELETE_ROW`, `GET_BY_ID`, and `SEARCH`. It does not use Word input and it does not perform transaction control.

See `docs/dialects/ORACLE-CRUD-METADATA.md`.

## SQL Server metadata CRUD procedures

```http
POST /api/v1/generate/sqlserver/crud
Content-Type: application/json
Accept: application/sql
```

```json
{
  "schema": "BIM",
  "table": "PROVINCES"
}
```

The endpoint reads live SQL Server `sys.*` metadata and returns the configured table-level CRUD stored-procedure script.

See `docs/dialects/SQLSERVER-CRUD-METADATA.md`.

## Metadata CRUD artifacts in generated archives

Word/Legacy/ZIP/EA generation may add Oracle CRUD packages and SQL Server CRUD procedure scripts for parsed tables when the required live repository is enabled and the table exists. A metadata CRUD summary records generated/skipped/failed status.

Metadata-based CRUD is not currently implemented for PostgreSQL, Db2 for z/OS, or MySQL.

## Mermaid from canonical JSON

```http
POST /api/v1/diagram/mermaid/canonical-json
Content-Type: multipart/form-data
Accept: text/plain
```

Required multipart field: `file`.

Supported request parameters currently include:

- `type` — default `er`;
- `scope` — default `all`;
- `schema` — optional;
- `root` — optional;
- `selected` — optional;
- `depth` — default `1`;
- `includeColumns` — default `true`;
- `includeDataTypes` — default `true`;
- `includePrimaryKeys` — default `true`;
- `includeForeignKeys` — default `true`;
- `includeLogicalForeignKeys` — default `false`.

The endpoint accepts one canonical snapshot or a ZIP of canonical snapshots according to the Mermaid service contract and returns a downloadable Mermaid text artifact.

## REST error contract (C7.2 official)

Successful payloads remain endpoint-specific (`application/zip`, `application/sql`, or Mermaid text). C7.2 adds the response header `X-SchemaForge-Request-Id` at the web layer and replaces controller-local `{ "error": "..." }` bodies with one versioned JSON error contract:

```json
{
  "contract": "schemaforge-rest-error/v1",
  "code": "INVALID_REQUEST",
  "status": 400,
  "message": "...",
  "path": "/api/v1/...",
  "requestId": "...",
  "timestamp": "...Z",
  "details": {}
}
```

Stable codes include `INVALID_REQUEST`, `INPUT_IO_ERROR`, `MISSING_PART`, `MISSING_PARAMETER`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_ACCEPTABLE`, `METHOD_NOT_ALLOWED`, `NOT_FOUND`, `PAYLOAD_TOO_LARGE`, `SERVICE_UNAVAILABLE`, and `INTERNAL_ERROR`. Unexpected failures return a generic public message while server logs retain the correlated exception. C7.2 is user-verified and frozen in the official baseline.

The package layout, Standard Manifest V1, and artifact naming contracts are already official from C5/C6; C7 does not redesign those contracts.
