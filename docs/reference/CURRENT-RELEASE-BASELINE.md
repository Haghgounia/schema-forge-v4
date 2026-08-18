# SchemaForge V4 - Current Release Baseline

**Baseline ID:** `SCHEMAFORGE-V4-R4-402-20260817`  
**Project version:** `4.0.0-SNAPSHOT`  
**Baseline date:** 2026-08-17  
**Parent functional baseline:** P8-D Physical Metadata Comparison Freeze / Documentation Finalization  
**Status:** CURRENT VERIFIED BASELINE

## 1. Functional state

Frozen workstreams retained from the parent baseline:

- legacy/standard specification processing baseline;
- four-dialect DDL generation;
- Physical DDL P0-P7;
- Physical Metadata Comparison P8-A/P8-B/P8-C;
- P8-D regression/freeze contract;
- consolidated `docs/reference/` documentation set.

Maintenance changes included after the 399-test documentation baseline:

- Oracle Excel comparison treats EA logical identity as equivalent to the deterministic SchemaForge sequence-backed default for the exact table/column, avoiding false `IDENTITY_MODE` and `DATA_DEFAULT` differences;
- EA XML/XMI create-table REST output now includes Mermaid and Graphviz artifacts and records both paths in `manifest.json`;
- EA/document tables without a primary key remain valid DDL inputs; metadata CRUD generation is skipped explicitly as `SKIPPED_NO_PRIMARY_KEY` instead of failing;
- the EA per-table output regression expectation was updated from 23 to 25 files after the two graph artifacts were added;
- the missing `QualifiedName` import introduced during the identity-comparison change was corrected.

## 2. User-verified regression

```text
Tests run: 402
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Total time: 02:29 min
Finished: 2026-08-17T23:19:54-07:00
```

## 3. Current source fingerprint

```text
e2b6969837c6a8ce8c34b75b51126bc9fb7cfcad37d4d0795371c99196510a35
```

This fingerprint is the SHA-256 of the sorted per-file SHA-256 manifest for the complete current `src` tree.

## 4. Current documentation authority

The authoritative current documentation set is `docs/reference/`.

Older phase documents remain implementation history and evidence. Earlier files that call themselves `FINAL` or record 270/376/399 tests refer to earlier V4 milestones; they are not the current regression authority.

## 5. Current output contract highlights

### Physical comparison workbook

Frozen physical sheets remain:

```text
TABLE_PHYSICAL_COMPARE
INDEX_PHYSICAL_COMPARE
COLUMN_PHYSICAL_COMPARE
```

Actual JDBC metadata remains comparison evidence only and never mutates design intent or generated DDL.

### EA create-table REST graph parity

The EA XML/XMI create-table REST workflow now includes both:

```text
*.mermaid.mmd
*.graphviz.dot
```

and records the artifact paths in `manifest.json`.

### No-primary-key tables

A table without an explicit PK is a valid schema object. SchemaForge does not infer a PK from `*_ID`, identity, or sequence naming. DDL/Excel/graphs continue; metadata CRUD output is skipped with `SKIPPED_NO_PRIMARY_KEY`.

### Oracle identity comparison

An EA logical identity is comparison-equivalent to an Oracle sequence-backed default only when the sequence name matches SchemaForge's deterministic expected sequence for that exact table/column. Arbitrary `NEXTVAL` defaults remain mismatches.

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

Future SchemaForge V4 development should start from this R4/402 source baseline and preserve the 402-test regression unless an intentional, reviewed change updates the contract and tests.
