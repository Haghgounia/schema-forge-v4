# SchemaForge V4 - Maintenance Release 4.0.1

Release: `4.0.1`
Date: 2026-09-07
Java: 21
Spring Boot: 3.5.0
Base release: `4.0.0`

## Release decision

4.0.1 is the first V4 maintenance release. Its production-code delta is restricted to FIX-001 in Word specification parsing. The fix prevents a following Sequence section from being interpreted as column/index metadata and preserves SchemaForge-owned naming for generated Sequence, Index, Unique Key, and Foreign Key objects.

No new REST API, manifest, artifact, Schema Conformance, canonical-model, migration-contract, or DBMS feature family is introduced.

## FIX-001 acceptance

```text
Focused regression : 5 tests, 0 failures, 0 errors
Real Batch          : 19 / 19 documents successful
Six-DBMS DDL         : 114 / 114
Validation errors    : 0
Full regression      : 765 tests, 0 failures, 0 errors, 9 skipped
```

## Maintenance build

Expected artifact:

```text
target\schema-forge-v4-4.0.1.jar
```

Freeze the authoritative binary only through:

```bat
distribution\scripts\reproducible-ga-build-windows.cmd
```

Then assemble and verify:

```bat
distribution\scripts\assemble-distribution-windows.cmd
distribution\scripts\verify-distribution-windows.cmd
```
