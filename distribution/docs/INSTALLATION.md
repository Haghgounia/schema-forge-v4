# SchemaForge V4 4.0.0 - Installation

## 1. Runtime prerequisites

- Windows host for the supplied `.cmd` scripts.
- Java 21 available on `PATH`.
- `curl.exe` for the supplied smoke test.
- Network access to any database explicitly enabled for live metadata operations.
- Port `9090` available, unless `SCHEMAFORGE_SERVER_PORT` is set to another port.

Verify Java before installation:

```bat
java -version
```

The validated build baseline uses Java 21.

## 2. Extract the distribution

Extract `schemaforge-v4-4.0.0-distribution.zip` to a dedicated directory, for example:

```text
D:\SchemaForge\schemaforge-v4-4.0.0
```

Expected top-level layout:

```text
bin\
config\
scripts\
docs\
samples\
checksums\
```

Do not run the application from inside the ZIP.

## 3. Verify the GA binary

Run:

```bat
scripts\verify-checksum-windows.cmd
```

The authoritative GA JAR SHA-256 is stored in `checksums/SHA256SUMS.txt`. Run `scripts\verify-checksum-windows.cmd` after extraction.

A checksum mismatch means the binary must not be started.

## 4. Configure runtime settings

The shipped `config\application.yml` is intentionally safe by default:

- every live metadata repository is disabled;
- database URLs, users, and passwords are externalized;
- no production database credential is supplied by the distribution configuration.

Set only the database profile(s) required by the deployment. See `CONFIGURATION.md`.

Example SQL Server environment variables:

```bat
set "SCHEMAFORGE_METADATA_SQLSERVER_ENABLED=true"
set "SCHEMAFORGE_METADATA_SQLSERVER_URL=jdbc:sqlserver://dbhost:1433;databaseName=MyDb;encrypt=true;trustServerCertificate=true"
set "SCHEMAFORGE_METADATA_SQLSERVER_USERNAME=schemaforge_user"
set "SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD=replace-with-secret-from-secure-store"
```

Do not save real passwords in the distribution ZIP or documentation.

## 5. Start SchemaForge

Use the supplied launcher:

```bat
scripts\start-windows.cmd
```

The launcher deliberately starts the JAR with:

```text
--spring.config.location=file:./config/application.yml
```

This external configuration boundary is part of the V4 distribution contract.

### Important

For the 4.0.0 distribution, do not use a bare command such as:

```text
java -jar bin\schema-forge-v4-4.0.0.jar
```

The validated GA JAR contains the original development `application.yml` resource. The distribution launcher overrides that embedded resource with the safe external runtime configuration. Phase 19.3 must preserve this launcher behavior unless a new maintenance binary is intentionally produced.

## 6. Verify startup

From another command prompt run:

```bat
scripts\smoke-test-windows.cmd
```

The script verifies `/v3/api-docs` and confirms that the frozen Schema Conformance API is present in OpenAPI output.

Manual verification:

```bat
curl.exe --fail-with-body -sS http://localhost:9090/v3/api-docs
```

Swagger UI is configured at:

```text
http://localhost:9090/swagger-ui.html
```

## 7. Enable live database metadata only when required

DDL generation from Word/Legacy Word/EA does not require every metadata connection to be enabled. Enable a metadata repository only for operations that need that live database.

The standard 4.0.0 GA runtime contains Oracle, PostgreSQL, SQL Server, and MySQL JDBC dependencies. IBM JCC is not bundled in the standard GA JAR; keep Db2 LUW and Db2 z/OS runtime metadata profiles disabled in this distribution. See `KNOWN-LIMITATIONS-4.0.0.md`.
