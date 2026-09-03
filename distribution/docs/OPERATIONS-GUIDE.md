# SchemaForge V4 4.0.0 - Operations Guide

## Runtime identity

```text
Product : SchemaForge V4
Version : 4.0.0
Artifact: bin\schema-forge-v4-4.0.0.jar
```

The authoritative frozen GA JAR SHA-256 is stored in `checksums/SHA256SUMS.txt`. It is produced by the reproducible-build freeze gate before distribution assembly.

## Startup procedure

1. Verify the checksum.
2. Set required environment variables.
3. Run the supported launcher.
4. Run the smoke test from another terminal.

Commands:

```bat
scripts\verify-checksum-windows.cmd
scripts\start-windows.cmd
```

In another terminal:

```bat
scripts\smoke-test-windows.cmd
```

## Supported launch boundary

The supported runtime launcher is `scripts\start-windows.cmd` because it forces:

```text
--spring.config.location=file:./config/application.yml
```

Do not bypass this boundary for production deployment of the 4.0.0 distribution.

## Smoke verification

The supplied smoke test:

- checks that `/v3/api-docs` responds successfully;
- verifies that `/api/v1/conformance/schema` is present in the OpenAPI document.

Override the base URL when a non-default port/host is used:

```bat
set "SCHEMAFORGE_BASE_URL=http://localhost:9191"
scripts\smoke-test-windows.cmd
```

## Live database verification

When a live metadata profile is enabled, verify its required endpoint separately. Example SQL Server Schema Conformance:

```bat
curl.exe --fail-with-body -sS ^
  "http://localhost:9090/api/v1/conformance/schema?platform=sqlserver&schema=TSTSHMA" ^
  -o schema-conformance.json
```

Confirm the response contains:

```text
schemaforge-schema-conformance/v3
```

## Shutdown

The supplied 4.0.0 staging package does not install SchemaForge as a Windows Service and does not expose a product-specific remote shutdown endpoint. Stop the foreground Java process using the hosting terminal/process-management mechanism approved by Operations.

A Windows Service wrapper, container, or service manager is deployment infrastructure and is not bundled in the V4 4.0.0 distribution.

## Logs and support evidence

The distribution does not override Spring Boot logging configuration. Capture the application console output according to the host's operational logging standard.

For an incident, collect at minimum:

```text
SchemaForge version
JAR SHA-256
startup console log
request path
HTTP status
X-SchemaForge-Request-Id
relevant schema/table/platform
sanitized runtime configuration (no passwords)
```

For generation failures also preserve the returned ZIP/report artifacts when available.

## Common startup failures

### Java not found

`start-windows.cmd` exits with an error if `java` is not on PATH.

### JAR missing

The launcher requires:

```text
bin\schema-forge-v4-4.0.0.jar
```

### Runtime config missing

The launcher requires:

```text
config\application.yml
```

Restore it from `config\application-example.yml` and reapply environment-specific settings externally.

### Enabled metadata profile is incomplete

If a live metadata profile is enabled without URL/username/driver configuration, application startup can fail because V4 validates required metadata datasource settings.

### JDBC driver unavailable

The standard GA JAR bundles Oracle, PostgreSQL, SQL Server, and MySQL JDBC drivers through the normal Maven build. IBM JCC is optional/profile-specific and is not in the standard GA binary.

### Port conflict

Change the REST port before startup:

```bat
set "SCHEMAFORGE_SERVER_PORT=9191"
```

Then set the matching smoke URL:

```bat
set "SCHEMAFORGE_BASE_URL=http://localhost:9191"
```

## Change control

The 4.0.0 binary is immutable once distributed. Do not replace the JAR while retaining the same checksum record. Any binary change requires a controlled maintenance release and a new checksum.
