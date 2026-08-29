# SchemaForge V4 — Cross-DBMS Object Naming Policy

## Purpose

This policy separates the **logical object name** carried by the canonical model from the
**physical object name** emitted for a target DBMS. It is based on the attached production-style
sample corpus `010128-all.zip` (616 SQL files) and the supported six-database target matrix.

The core rule is: **never destroy source naming information merely to satisfy one target**.
Names that already fit the target are preserved exactly, including repeated underscores. Only
supporting/generated objects that exceed a target limit receive deterministic physical shortening.

## Observed project naming convention

The sample corpus establishes the following dominant convention:

| Object | Convention |
|---|---|
| Sequence | `SEQ_<TABLE>` (or an explicit extended `SEQ_...` name) |
| Primary-key constraint | `PK_<TABLE>` |
| Oracle PK enforcing index | `PK_<TABLE>_<PK_COLUMNS>` |
| Unique constraint | `UK_<TABLE>_<COLUMNS>` |
| Oracle UK enforcing index | same logical name as the UK constraint |
| Foreign key | `FK_<TABLE>_<FK_COLUMNS>` |
| Check constraint | `CHK_<TABLE>_...` |
| Standalone index | `IX_<TABLE>_...` |

Explicit source names remain authoritative. The convention above is used for generated fallback
names and supporting objects; SchemaForge does not rewrite a valid explicit `IDX_...` name merely
because the preferred generated prefix is `IX_`.

### Sample-corpus evidence

| Object | Matches | Maximum observed length | >63 | >64 |
|---|---:|---:|---:|---:|
| Table | 377 | 46 | 0 | 0 |
| Sequence | 752 | 87 | 2 | 2 |
| Index | 823 | 107 | 158 | 141 |
| PK constraint | 377 | 49 | 0 | 0 |
| UK constraint | 356 | 107 | 74 | 66 |
| FK constraint | 743 | 87 | 43 | 41 |
| CHECK constraint | 781 | 68 | 6 | 6 |

All 781 observed check names use the `CHK_` prefix. The 823 indexes split into 377 `PK_`, 356
`UK_`, and 90 `IX_` names. No observed object exceeds 128 characters.

The source EA model can legitimately contain repeated underscores (for example
`IX_PATTERN_OPERATION__2`). Repeated underscores therefore carry source identity and **must not**
be collapsed during parsing or normalization.

## Target physical limits

SchemaForge V4 uses the following target baselines for ordinary object identifiers:

| DBMS | Physical limit used by SchemaForge | Policy |
|---|---:|---|
| Oracle | 128 bytes | Oracle `COMPATIBLE >= 12.2` baseline |
| PostgreSQL | 63 bytes | default `NAMEDATALEN=64` build |
| Microsoft SQL Server | 128 characters | regular/delimited identifier baseline |
| MySQL | 64 characters | table/index/constraint identifier baseline |
| Db2 LUW | 128 bytes | ordinary SQL object baseline |
| Db2 for z/OS | 128 bytes | ordinary SQL object baseline |

Canonical SchemaForge identifiers are ASCII by contract, so byte and character length are equal
for generated names governed by this policy.

## Logical-to-physical conversion

1. If a supporting/generated logical name fits the target, emit it unchanged.
2. If it exceeds the target limit, do **not** use plain truncation.
3. Keep the visible stem and append `_` plus the first 10 uppercase hexadecimal characters of a
   SHA-256 digest of the full logical name.
4. The resulting name is deterministic and target-length bounded.
5. Collision analysis runs on the final physical name in the DBMS-specific namespace.

Example shape for PostgreSQL:

```text
FK_VERY_LONG_BUSINESS_TABLE_NAME_WITH_A_LONG_REF...  (logical)
FK_VERY_LONG_BUSINESS_TABLE_NAME_WITH_A_LONG_R_<10-HEX>  (physical <= 63)
```

## Source identifiers versus generated identifiers

Schema, table and column names are source/business identifiers. SchemaForge does not silently
shorten them because doing so changes the external schema contract. A target-specific overlength
source identifier is reported as `SOURCE_IDENTIFIER_TOO_LONG` and requires model/DBA resolution.

Constraint, index and sequence names are supporting/generated object identifiers. They may use the
deterministic physical shortening policy while retaining the original logical name in the
canonical model and reports.

## Collision namespaces

Collision validation is target-aware rather than global:

| DBMS | Relevant audit behavior |
|---|---|
| Oracle | index names are schema-wide; constraint names are schema-wide; tables and sequences participate in Oracle's shared schema-object namespace used by the audit |
| PostgreSQL | tables, indexes and sequences share the relation namespace in a schema; PK/UK names are also audited against that namespace because they create enforcing indexes |
| SQL Server | index names are table-local; named constraints and other schema-scoped objects are audited schema-wide |
| MySQL | index names are table-local; each constraint type has its own schema-level namespace |
| Db2 LUW | index names are audited schema-wide; table constraints are audited table-locally |
| Db2 z/OS | index names are audited schema-wide; table constraints are audited table-locally |

The audit key includes the owning table where appropriate, so two equal index names on different
SQL Server/MySQL tables are not false collisions, while the same pattern is rejected for
Oracle/PostgreSQL/Db2 index namespaces.

## Round-trip convergence requirement

Physical naming participates in migration equivalence. A desired logical object name and the
stable shortened physical name discovered from a live target represent the same object. Therefore:

```text
Generate -> Deploy -> Read metadata -> Compare same model
```

must not generate a rename/add/drop migration solely because a target required deterministic
physical shortening.

The same convergence rule also covers Oracle sequence-backed identity, semantic datatype
normalization (for example `TIMESTAMP` versus Oracle catalog `TIMESTAMP(6)`), and indexes that are
physically redundant with PK/UK enforcement.
