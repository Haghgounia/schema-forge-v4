# SchemaForge V4 4.0.1 - Maintenance Release Notes

Release date: 2026-09-07

Status: Maintenance Release

## Purpose

4.0.1 is a focused maintenance release over the frozen 4.0.0 GA baseline. It contains FIX-001 only and does not add a new feature family or change the frozen REST, artifact, manifest, Schema Conformance, or six-DBMS semantic contracts.

## FIX-001 - Word object-name isolation and section boundary

A real Word specification (`MCB.RTG.TBL.BANK_TO_BANK_TRANSFERS.docx`) places the Sequence section in the same physical Word table after the column rows. The 4.0.0 parser could continue treating that section as column metadata. Sequence-description text could therefore enter the Index field and produce an invalid over-length generated index identifier.

4.0.1 fixes the section boundary and preserves the established SchemaForge naming policy:

```text
Sequence physical name    -> derived by SchemaForge naming policy
Index physical name       -> derived by SchemaForge naming policy
Foreign-key physical name -> derived by SchemaForge naming policy
Unique-key physical name  -> derived by SchemaForge naming policy
```

Names written in the Word document do not override the SchemaForge physical naming contract. Source tokens may still describe semantic grouping/relationships, but generated object names remain deterministic and policy-owned.

## Real-output acceptance

The previously failing `BANK_TO_BANK_TRANSFERS` document was regenerated successfully in a 19-document Batch run:

```text
Input documents     : 19
Successful          : 19
Failed              : 0
Request status      : SUCCESS
Generated artifacts : 243
Skipped artifacts   : 266
Blocked artifacts   : 0
Failed artifacts    : 0
Validation errors   : 0
Validation warnings : 89
Six-DBMS DDL        : 114 / 114
```

All metadata-dependent skips in that offline run were caused by unavailable/disabled live metadata repositories and remained isolated from DDL generation.

## Full regression

```text
Tests    : 765
Failures : 0
Errors   : 0
Skipped  : 9
Result   : BUILD SUCCESS
```

## Unchanged scope

The V4 4.0.0 GA capability scope remains unchanged, including DDL generation for Oracle, PostgreSQL, Db2 for z/OS, Db2 LUW, SQL Server, and MySQL; Schema Conformance Audit V3 for TABLE and SCHEMA; migration/comparison/CRUD artifacts; API contracts; and artifact/manifest/ZIP contracts.
