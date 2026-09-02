# SchemaForge V4 4.0.0 - Release Notes

Release date: 2026-09-02

Status: General Availability

## Core capabilities

- Canonical schema model and validation pipeline.
- Word specification ingestion.
- Legacy Word specification ingestion.
- Enterprise Architect XML/XMI ingestion.
- Batch ZIP processing with document isolation.
- DDL generation for Oracle, PostgreSQL, Db2 for z/OS, Db2 LUW, SQL Server, and MySQL.
- Cross-DBMS semantic contract for nullability, defaults, numeric precision/scale, lengths, keys, references, generated columns, identity/sequence behavior, and timestamp semantics.
- Deterministic logical and physical database object naming.
- Metadata comparison against live databases.
- Migration/diff and Flyway-oriented artifact generation.
- Oracle and SQL Server metadata-derived CRUD generation.
- Mermaid diagram export from canonical JSON snapshots.
- Artifact/manifest/ZIP contract with deterministic status semantics.
- Safe external archive naming.
- Schema Conformance Audit for existing TABLE and SCHEMA scopes.
- Frozen REST contract and versioned REST error contract.

## Schema Conformance Audit V3

Report contract:

```text
schemaforge-schema-conformance/v3
```

Rule families:

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

The audit is read-only and excludes data-quality/content checks.

## NUM-001

Missing exact numeric precision is represented canonically with unspecified precision/scale and does not block generation. DBMS-specific fallback behavior is frozen under the V4 contract.

## Release validation

The final V4 regression baseline completed with:

```text
743 tests
0 failures
0 errors
9 skipped
BUILD SUCCESS
```

GA runtime verification included OpenAPI and a live SQL Server Schema Conformance audit.

See `VALIDATION-EVIDENCE-4.0.0.md` for the recorded evidence.
