# SchemaForge V4 - C7.1 REST Response and Error Contract Design

Status: **C7.1 DESIGN COMPLETE / C7.2 IMPLEMENTED AND USER-VERIFIED**
Input baseline: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C6.2`

## Objective

Standardize HTTP error behavior across all seven current REST endpoints without changing generation semantics or successful payload formats.

## Success-response compatibility

C7 preserves current successful response bodies, media types, attachment filenames, and endpoint URLs. It adds one correlation header only:

`X-SchemaForge-Request-Id`

The request ID is generated per HTTP request and is intentionally separate from Artifact `generationId`. The correlation filter is web-global so pre-handler failures can also be correlated.

## Error contract

Contract identifier: `schemaforge-rest-error/v1`

```json
{
  "contract": "schemaforge-rest-error/v1",
  "code": "INVALID_REQUEST",
  "status": 400,
  "message": "EA file must be XML or XMI",
  "path": "/api/v1/generate/ea-xml",
  "requestId": "...",
  "timestamp": "...Z",
  "details": {}
}
```

Required fields: contract, code, status, message, path, requestId, timestamp, details.

## Error code mapping

| Condition | HTTP | Code |
|---|---:|---|
| Illegal request/domain input | 400 | INVALID_REQUEST |
| Input IOException preserving current behavior | 400 | INPUT_IO_ERROR |
| Missing multipart part | 400 | MISSING_PART |
| Missing request parameter | 400 | MISSING_PARAMETER |
| Malformed JSON/body | 400 | MALFORMED_REQUEST |
| Argument binding/type mismatch | 400 | INVALID_PARAMETER |
| Unsupported media type | 415 | UNSUPPORTED_MEDIA_TYPE |
| Requested response media type unavailable | 406 | NOT_ACCEPTABLE |
| HTTP method not allowed | 405 | METHOD_NOT_ALLOWED |
| Resource not found | 404 | NOT_FOUND |
| Upload exceeds configured limit | 413 | PAYLOAD_TOO_LARGE |
| Required metadata service unavailable | 503 | SERVICE_UNAVAILABLE |
| Unexpected failure | 500 | INTERNAL_ERROR |

Unexpected failures expose a generic message and do not return stack traces or implementation details.

## Architecture

- Remove per-controller `@ExceptionHandler` duplication.
- Add one global `@RestControllerAdvice` for error mapping. It intentionally has no controller selector so pre-handler MVC failures such as 405 and multipart resolution errors can use the same contract; `NoResourceFoundException` is mapped explicitly to 404 so the generic 500 handler cannot corrupt not-found semantics.
- Add one request-correlation filter for the request ID response header.
- Keep business/application exceptions unchanged in C7; the web adapter maps them centrally.
- Do not make controllers depend on Artifact/Manifest generation IDs.

## Non-goals

C7 does not change parsers, canonical model, DDL, migration, CRUD SQL, metadata behavior, artifact naming/layout, Manifest V1, or standalone successful response bodies.

## C7.2 verification

The implementation derived from this design was frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C7.2` after targeted `31/31` and full `525 / 0 / 0 / 4` user-verified Maven regression.
