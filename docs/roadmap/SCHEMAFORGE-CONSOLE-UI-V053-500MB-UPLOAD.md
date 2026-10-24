# SchemaForge Console UI-C10 — Generation upload up to 500 MB (V0.5.3)

**Baseline:** source ZIP SchemaForge Console V0.5.2, derived from user-provided 2026-10-24-0748 baseline. Scope: Spring multipart defaults and React generation preflight only.

## Contract and design

- Spring Boot multipart file maximum: `${SCHEMAFORGE_UPLOAD_MAX_FILE_SIZE:500MB}`.
- Spring Boot multipart request maximum: `${SCHEMAFORGE_UPLOAD_MAX_REQUEST_SIZE:520MB}`; the difference accommodates multipart framing.
- Applies to `src/main/resources/application.yml` and both `distribution/config/application*.yml` files.
- Five `POST /api/v1/generate/*` multipart routes unchanged; the browser rejects generation files above 500 MiB before upload.
- All 7 DBMS connectors, Java parsing logic, H2, Glossary governance, and response schemas are untouched.
- The **100 MiB ZIP inspection cap** in the browser is independent and remains unchanged. Browser download can complete even when local ZIP preview is skipped.
- Small-document parser-specific limits and protections against ZIP expansion bombs are not relaxed. Heap, temporary-disk, and output buffering can still constrain successful processing of near-500 MB archives.

## Acceptance checklist

| Gate | Acceptance | Evidence |
|---|---|---|
| UI-C10a | All 3 Spring YAML sources use 500MB/520MB defaults | automated regression check |
| UI-C10b | UI allows 500 MiB, rejects >500 MiB before fetch | Node boundary tests |
| UI-C10c | No regression in all preexisting Node REST/diagram/Glossary tests | `npm run test` (Windows) |
| UI-C10d | Typecheck & build succeed | `npm run typecheck && npm run build` (Windows) |
| UI-C10e | Java Maven REST exception tests succeed | `mvnw.cmd -Dtest=SchemaForgeRestExceptionHandlerTest test` (Windows) |
| UI-C10f | Live multipart file between 100 and 500 MB reaches Java and produces an outcome | Windows/live, pending |
| UI-C10g | Oversized multipart request gets HTTP 413 instead of causing a misleading success | Windows/live, pending |

## Operational notes

Restart **Spring Boot** after applying the Delta; restarting Vite alone does not change the Java multipart limits. If an external `config/application.yml` overrides the packaged config, update it as well or set the environment variables before starting the backend. The defaults apply only when not overridden. An HTTP 200 on `/v3/api-docs` proves routing, not success processing a large `POST /api/v1/generate/zip` request.

Keep browser preview resources bounded; do not increase the 100 MiB JSZip inspection threshold to match the upload allowance without a streaming/bounded-memory design.
