# SchemaForge REST API

## Run

```bash
mvn spring-boot:run
```

Server: `http://localhost:9090`

Swagger UI: `http://localhost:9090/swagger-ui.html`

OpenAPI JSON: `http://localhost:9090/v3/api-docs`

## Endpoints

- `POST /api/v1/generate/word` — multipart field `file`, one `.docx`
- `POST /api/v1/generate/zip` — multipart field `file`, one `.zip` containing `.docx` files
- `POST /api/v1/generate/ea-xml` — multipart field `file`, one EA `.xml` or `.xmi`

Every endpoint returns an `application/zip`. Word and Word-ZIP inputs keep the timestamped document-level output and include Oracle, PostgreSQL, Db2 for z/OS, and SQL Server DDL. EA XML/XMI input produces one SQL file per table for all four registered dialects, consolidated `model.json` and `manifest.json`, dialect-specific `run_all.sql` files, and one comparison workbook per visible database table and metadata-enabled dialect.

For ZIP input, each Word document is isolated. Temporary Office files such as `~$*.docx`, hidden dot files, AppleDouble files, and `__MACOSX` entries are ignored. A malformed or non-specification Word document no longer aborts the whole request; successful documents are generated and the returned archive always includes:

- `batch-generation-summary.csv` — one row per processable Word document with `SUCCESS` or `FAILED` status
- `batch-generation-errors.log` — full stack traces for failed documents

A ZIP with no processable `.docx` file still returns HTTP 400.

## Enterprise Architect XML/XMI

EA exports frequently omit the physical schema. Configure the fallback schema in `application.yml`:

```yaml
schemaforge:
  ea:
    default-schema: FEE
```

The value may also be supplied with the `SCHEMAFORGE_EA_DEFAULT_SCHEMA` environment variable. An explicit EA schema/owner tagged value takes precedence over the configured fallback.

The EA importer reads table classes, ordered columns, datatype/length/precision/scale, nullability, descriptions, primary keys, foreign keys and standalone indexes. All imported objects enter the same canonical model used by Word input, so Oracle, PostgreSQL, Db2 for z/OS, and SQL Server DDL share the same database-neutral JSON and validation pipeline. Comparison workbooks are produced only for dialects with an available metadata repository.
