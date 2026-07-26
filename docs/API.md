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

Every endpoint returns an `application/zip` containing canonical JSON plus Oracle and PostgreSQL SQL files.
