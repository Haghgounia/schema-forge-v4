# Mermaid Production Integration

SchemaForge V4 Mermaid export is exposed through the supported Spring Boot REST runtime without changing the legacy Word parser or any SQL dialect/DDL generator.

## Endpoint

`POST /api/v1/diagram/mermaid/canonical-json`

Content type: `multipart/form-data`

The `file` part accepts either:

- one `*.schema.json` canonical snapshot; or
- one ZIP containing multiple `*.schema.json` canonical snapshots.

Production input must contain exactly one definition for every qualified table. Historical corpora that contain several versions of the same `SCHEMA.TABLE` are rejected with `INPUT_DUPLICATE_TABLE`. Production code never chooses a historical version automatically.

## Parameters

- `type`: `er` or `dependency` (default `er`)
- `scope`: `all`, `schema`, `table`, `table-with-dependencies`, `selected-tables` (default `all`)
- `schema`: required for `schema`
- `root`: required for `table` and `table-with-dependencies`, in `SCHEMA.TABLE` form
- `selected`: comma-separated `SCHEMA.TABLE` values for `selected-tables`
- `depth`: dependency depth for `table-with-dependencies` (default `1`)
- `includeColumns`: default `true`
- `includeDataTypes`: default `true`
- `includePrimaryKeys`: default `true`
- `includeForeignKeys`: default `true`
- `includeLogicalForeignKeys`: default `false`

The response is a UTF-8 Mermaid `.mmd` attachment. Response headers also report diagram type, scope, and the number of unique canonical tables loaded from the input.

## Example with curl

```bat
curl -X POST "http://localhost:8080/api/v1/diagram/mermaid/canonical-json?type=dependency&scope=table-with-dependencies&root=TSTSHMA.CTMACCTYPEPARAMGRPARZSOURCE&depth=2" ^
  -H "accept: text/plain" ^
  -F "file=@D:\SchemaForgeInput\canonical-unique.zip;type=application/zip" ^
  -o TSTSHMA_CTMACCTYPEPARAMGRPARZSOURCE__dependency-depth-2.mmd
```

## Swagger UI

After starting SchemaForge, open the project's Swagger UI, locate **Diagram Export**, choose `POST /api/v1/diagram/mermaid/canonical-json`, upload the snapshot/ZIP, fill the requested scope parameters, and execute. The returned response can be saved as `.mmd` and opened in Mermaid-compatible tools.

## Safety and determinism

- ZIP path traversal is rejected.
- ZIP entry count is capped at 20,000.
- Total uncompressed ZIP content is capped at 256 MiB.
- Input files are processed in deterministic path order.
- Duplicate qualified tables are rejected.
- Mermaid output is deterministic for the same canonical input and options.
- No database connection is used.
- No DDL is generated or modified.
