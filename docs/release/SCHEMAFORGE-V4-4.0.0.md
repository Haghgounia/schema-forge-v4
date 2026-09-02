# SchemaForge V4 - General Availability

Release: `4.0.0`
Date: 2026-09-02
Java: 21
Spring Boot: 3.5.0

## Release decision

SchemaForge V4 is promoted from `4.0.0-RC1` to `4.0.0` with no production Java changes after RC1 validation. The GA promotion changes release metadata/versioning only.

RC1 binary validated before promotion:

```text
schema-forge-v4-4.0.0-RC1.jar
SHA-256: 38c6b0e4fef76cdf71b2535cdf92e866365a32a322f572659ef1bec9b4bed8f5
```

## Frozen V4 scope

- canonical schema model
- Word, Legacy Word, and Enterprise Architect ingestion
- DDL generation for Oracle, PostgreSQL, Db2 z/OS, Db2 LUW, SQL Server, and MySQL
- metadata comparison and migration/diff generation
- cross-DBMS semantic contract
- logical and physical object naming contracts
- artifact, manifest, ZIP, and archive naming contracts
- `NUM-001` unspecified numeric precision behavior
- schema conformance audit for TABLE and SCHEMA scopes
- frozen REST API contract
- executable Spring Boot JAR distribution

## Schema Conformance Audit

Frozen report contract:

```text
schemaforge-schema-conformance/v3
```

Read-only endpoints:

```text
GET /api/v1/conformance/table?platform=<dbms>&schema=<schema>&table=<table>
GET /api/v1/conformance/schema?platform=<dbms>&schema=<schema>
```

Rule families:

1. STRUCTURAL
2. METADATA_CONVENTION
3. DATATYPE_COMPATIBILITY
4. CONSTRAINT_REFERENCES
5. KEY_CONSTRAINTS
6. REFERENTIAL_INTEGRITY
7. INDEX_COVERAGE
8. PHYSICAL_NAMING

## GA build

```bat
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean package
```

Expected artifact:

```text
target\schema-forge-v4-4.0.0.jar
```

Generate the authoritative GA checksum:

```bat
certutil -hashfile target\schema-forge-v4-4.0.0.jar SHA256
```

## GA smoke

Run the exact GA JAR and verify:

```text
GET /v3/api-docs
GET /api/v1/conformance/schema?platform=sqlserver&schema=TSTSHMA
```

The conformance response must report `schemaforge-schema-conformance/v3`.
