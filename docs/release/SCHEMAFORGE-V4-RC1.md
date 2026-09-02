# SchemaForge V4 - Release Candidate 1

Release candidate: `4.0.0-RC1`
Date: 2026-09-02
Java: 21
Spring Boot: 3.5.0

## Release scope

SchemaForge V4 RC1 freezes the completed V4 mainline through:

- canonical schema model and Word / Legacy Word / Enterprise Architect ingestion
- six-DBMS DDL generation: Oracle, PostgreSQL, Db2 z/OS, Db2 LUW, SQL Server, MySQL
- metadata comparison and schema diff / migration generation
- cross-DBMS semantic and physical-object naming contracts
- artifact / manifest / ZIP contract and collision-safe output naming
- unspecified numeric precision contract (`NUM-001`)
- schema conformance audit for TABLE and SCHEMA scopes
- API contract freeze
- final cross-contract regression and executable-JAR build

## Schema Conformance Audit

Read-only endpoints:

```text
GET /api/v1/conformance/table?platform=<dbms>&schema=<schema>&table=<table>
GET /api/v1/conformance/schema?platform=<dbms>&schema=<schema>
```

Frozen report contract:

```text
schemaforge-schema-conformance/v3
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

The audit is read-only. It does not emit or execute ALTER/DROP/CREATE statements and does not perform automatic remediation.

## NUM-001

Unspecified exact-numeric precision remains unspecified in the canonical model and is non-blocking. The target-specific rendering contract is:

```text
Oracle       NUMBER
PostgreSQL   NUMERIC
Db2 LUW      DECIMAL(31,0)
Db2 z/OS     DECIMAL(31,0)
SQL Server   DECIMAL(38,0)
MySQL        DECIMAL(65,0)
```

The diagnostic code is `NUMERIC_PRECISION_UNSPECIFIED` and is rendered as a warning/hint rather than a generation blocker.

## Release build

Build the exact RC artifact with:

```bat
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean package
```

Expected executable artifact:

```text
target\schema-forge-v4-4.0.0-RC1.jar
```

Generate release checksum:

```bat
certutil -hashfile target\schema-forge-v4-4.0.0-RC1.jar SHA256
```

The checksum generated from the final RC1 JAR is the authoritative binary identity for release evidence.

## Final smoke

Run the exact RC1 JAR and verify at minimum:

```text
GET /v3/api-docs
GET /api/v1/conformance/schema?platform=sqlserver&schema=TSTSHMA
```

The conformance response must report `schemaforge-schema-conformance/v3`.
