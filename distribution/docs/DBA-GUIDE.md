# SchemaForge V4 4.0.0 - DBA Guide

## Supported database targets

SchemaForge V4 generates DDL for:

- Oracle
- PostgreSQL
- Db2 for z/OS
- Db2 LUW
- SQL Server
- MySQL

The DDL generation contract is distinct from live metadata connectivity. A DBMS can be a supported generation target even when its runtime metadata profile is disabled.

## Live metadata access

Live metadata repositories are opt-in in the distribution. All are disabled by default.

When enabled, SchemaForge creates a metadata-only JDBC datasource for the selected DBMS. The application requires a JDBC URL, username, and driver class for an enabled profile.

Use a database principal with the least permissions required to read system/catalog metadata. SchemaForge distribution documentation does not require or recommend an administrative database account.

## Schema Conformance Audit

Schema Conformance Audit evaluates an existing database structure against SchemaForge rules.

Flow:

```text
Actual database metadata
        -> validation rules
        -> conformance report
```

Scopes:

```text
TABLE
SCHEMA
```

The audit path is read-only by design. It does not generate or execute `ALTER`, `DROP`, or automatic fixes against the audited database.

V3 rule families:

```text
STRUCTURAL
METADATA_CONVENTION
DATATYPE_COMPATIBILITY
CONSTRAINT_REFERENCES
KEY_CONSTRAINTS
REFERENTIAL_INTEGRITY
INDEX_COVERAGE
PHYSICAL_NAMING
```

Data-quality/content inspection is not part of the V4 Schema Conformance scope.

## Live audit example - SQL Server

Required environment configuration:

```bat
set "SCHEMAFORGE_METADATA_SQLSERVER_ENABLED=true"
set "SCHEMAFORGE_METADATA_SQLSERVER_URL=jdbc:sqlserver://dbhost:1433;databaseName=MyDb;encrypt=true;trustServerCertificate=true"
set "SCHEMAFORGE_METADATA_SQLSERVER_USERNAME=schemaforge_metadata"
set "SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD=<secret>"
```

After startup:

```bat
curl.exe --fail-with-body -sS ^
  "http://localhost:9090/api/v1/conformance/schema?platform=sqlserver&schema=TSTSHMA" ^
  -o conformance.json
```

A successful audit may still return `compliant=false` when warnings are present.

## Metadata-derived CRUD

V4 exposes metadata-derived CRUD generation for:

- Oracle
- SQL Server

These endpoints read the live table definition and return SQL content. They require the corresponding metadata profile to be enabled.

## Db2 runtime note

Db2 LUW generation was validated including live test work under its dedicated Maven profile. Db2 z/OS generation has offline validation, with live execution explicitly deferred.

The standard GA JAR in this distribution does not bundle IBM JCC. Therefore Db2 LUW and Db2 z/OS metadata profiles must remain disabled in the standard distribution package.
