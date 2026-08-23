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
| POST | `/api/v1/generate/word` | multipart `file`, one standard `.docx` | `application/zip` |
| POST | `/api/v1/generate/legacy-word` | multipart `file` plus required `schema` parameter | `application/zip` |
| POST | `/api/v1/generate/zip` | multipart `file`, ZIP containing standard `.docx` files | `application/zip` |
| POST | `/api/v1/generate/ea-xml` | multipart `file`, EA `.xml`/`.xmi`; optional `schema` | `application/zip` |
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

Both paths prepare one canonical schema and generate five-dialect DDL plus the additional enabled artifacts such as comparison workbooks, migration output, metadata-based CRUD, canonical JSON, Mermaid and Graphviz outputs.

## ZIP batch

```http
POST /api/v1/generate/zip
Content-Type: multipart/form-data
```

The ZIP batch path processes standard `.docx` specifications. Legacy `.doc` files are not routed through the batch endpoint.

Each Word document is isolated. Temporary Office files such as `~$*.docx`, hidden dot files, AppleDouble files, and `__MACOSX` entries are ignored. A malformed or non-specification Word document does not abort the whole request; successful documents are generated and the returned archive includes:

- `batch-generation-summary.csv` — one row per processable Word document with `SUCCESS` or `FAILED` status;
- `batch-generation-errors.log` — full stack traces for failed documents.

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

EA output follows the C5 common artifact-first layout: per-table DDL under `ddl/<platform>/`, a timestamped canonical snapshot under `model/*.schema.json`, dialect run-all scripts under `scripts/<platform>/`, comparison workbooks under `comparison/<platform>/` when metadata is available, and the current EA `manifest.json`. The common manifest contract for Word/Legacy/ZIP/EA is C6 scope.

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

## Current REST contract boundary

The endpoints are functional but do not yet share one universal artifact package layout, manifest, naming contract, or centralized error envelope. EA already has a manifest, ZIP batch has batch summary/error artifacts, and standalone CRUD/Mermaid return direct files. Unification of these contracts is a separate V4 consolidation stage and is intentionally not implied by this API document.
