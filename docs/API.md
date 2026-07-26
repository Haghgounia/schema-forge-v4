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

Every endpoint returns an `application/zip`. Word and Word-ZIP inputs keep the timestamped document-level output. EA XML/XMI input produces one Oracle and one PostgreSQL SQL file per table, consolidated `model.json` and `manifest.json`, dialect-specific `run_all.sql` files, and one comparison workbook per visible database table and dialect.

## Enterprise Architect XML/XMI

EA exports frequently omit the physical schema. Configure the fallback schema in `application.yml`:

```yaml
schemaforge:
  ea:
    default-schema: FEE
```

The value may also be supplied with the `SCHEMAFORGE_EA_DEFAULT_SCHEMA` environment variable. An explicit EA schema/owner tagged value takes precedence over the configured fallback.

The EA importer reads table classes, ordered columns, datatype/length/precision/scale, nullability, descriptions, primary keys, foreign keys and standalone indexes. All imported objects enter the same canonical model used by Word input, so Oracle/PostgreSQL DDL, JSON, validation and Excel comparison remain database-neutral.
