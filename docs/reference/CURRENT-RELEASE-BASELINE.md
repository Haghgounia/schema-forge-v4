# SchemaForge V4 - Current Release Baseline

**Baseline ID:** `SCHEMAFORGE-V4-DOCFINAL-20260817`  
**Project version:** `4.0.0-SNAPSHOT`  
**Baseline date:** 2026-08-17  
**Parent functional baseline:** P8-D Physical Metadata Comparison Freeze  
**Status:** CURRENT DOCUMENTED BASELINE

## 1. Functional state

Frozen workstreams:

- legacy/standard specification processing baseline;
- four-dialect DDL generation;
- Physical DDL P0-P7;
- Physical Metadata Comparison P8-A/P8-B/P8-C;
- P8-D regression/freeze contract.

This documentation finalization adds no Java production or test behavior.

## 2. User-verified regression

```text
Tests run: 399
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Finished: 2026-08-17T08:40:09-07:00
```

## 3. Source fingerprint

```text
a864b0f1db1099436a766b39ce9de651503ed217596f252ae3e2dc039ad73c3f
```

This fingerprint is the SHA-256 of the sorted per-file SHA-256 manifest for the complete `src` tree.

## 4. Current documentation authority

The authoritative current documentation set is `docs/reference/`.

Older phase documents remain as implementation history and evidence. Earlier files that call themselves `FINAL` may refer to an earlier V4 milestone and test count; they are not the current regression authority.

## 5. Frozen physical comparison workbook

Current physical sheets:

```text
TABLE_PHYSICAL_COMPARE
INDEX_PHYSICAL_COMPARE
COLUMN_PHYSICAL_COMPARE
```

Current physical comparison rule:

```text
Expected = design/specification/profile
Actual   = current database metadata
Actual never mutates Expected or generated DDL
```

## 6. Deferred scope

Major intentionally deferred areas include:

- Oracle LOB storage model;
- partition/subpartition physical model;
- SQL Server TEXTIMAGE/FILESTREAM model;
- PostgreSQL explicit table access method;
- Db2 recovery/cluster/null-key semantics;
- shared storage-object provisioning;
- historical build-operation reverse engineering.

See `KNOWN-LIMITATIONS.md` for the detailed boundary.

## 7. Baseline rule

Future SchemaForge V4 development should start from this documented package/source baseline and preserve the current 399-test regression unless an intentional, reviewed change updates the contract and tests.
