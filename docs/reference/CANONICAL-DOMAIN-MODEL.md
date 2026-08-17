# Canonical Domain Model

## 1. Purpose

The canonical model is the DBMS-neutral contract between parsers, validation, snapshots, DDL generation, diagrams, metadata reconstruction, and comparison reporting.

## 2. Root model

### `DatabaseSchema`

Contains:

- schema name;
- description;
- tables;
- sequences;
- schema metadata.

Within one `DatabaseSchema`, duplicate table or sequence qualified names are rejected.

## 3. Table model

### `Table`

Contains:

- `QualifiedName qualifiedName`;
- Persian name;
- description;
- columns;
- optional primary key;
- foreign keys;
- unique keys;
- check constraints;
- indexes;
- `Map<String,String> physicalOptions`.

Important invariants:

- a table must contain at least one column;
- PK/FK/UK/index column references must resolve to table columns;
- ordinary index expressions are allowed without a column reference;
- included index columns must resolve to real table columns.

## 4. Column model

### `Column`

Contains:

- technical identifier;
- canonical `DataType`;
- nullable flag;
- default value;
- description;
- identity flag;
- ordinal position;
- optional generated expression;
- `physicalOptions`.

A generated column cannot simultaneously be an identity column or define a normal default value.

Current persistent column-level physical modeling is used for PostgreSQL `STORAGE` and `COMPRESSION`.

## 5. Keys and constraints

### `PrimaryKey`

Contains:

- optional name;
- ordered columns;
- deferrable flags;
- backing-index `physicalOptions`.

Physical options describe the enforcing/backing index and do not change primary-key semantics.

### `UniqueKey`

Contains:

- optional name;
- ordered columns;
- deferrable flags;
- backing-index `physicalOptions`.

### `ForeignKey`

Contains:

- optional name;
- local columns;
- referenced qualified table;
- referenced columns;
- `ON DELETE` and `ON UPDATE` actions;
- deferrable flags;
- `physicalReference`;
- `schemaExplicit`.

`physicalReference=false` represents a logical reference. Logical references stay visible in the canonical model and reports but are not rendered as executable FK constraints.

### `CheckConstraint`

Contains an optional identifier and non-blank check expression.

## 6. Index model

### `Index`

Contains:

- optional identifier;
- ordered index key entries;
- index type;
- description;
- include columns;
- optional predicate;
- `physicalOptions`;
- `buildOptions`.

### `IndexColumn`

Represents exactly one of:

- a column identifier; or
- a scalar expression.

Sort direction is retained.

## 7. Persistent physical options versus build options

This distinction is a frozen V4 rule.

### `physicalOptions`

Describe persistent object state or approved physical design intent, for example:

- tablespace/filegroup placement;
- PCTFREE/fillfactor;
- compression;
- locking/storage attributes;
- PostgreSQL column storage/compression.

Object scope exists on:

- `Table`;
- `Column`;
- `Index`;
- `PrimaryKey` backing index;
- `UniqueKey` backing index.

### `Index.buildOptions`

Describe how `CREATE INDEX` is executed, not persistent state.

Examples currently modeled include:

- Oracle `ONLINE`;
- PostgreSQL `CONCURRENTLY`;
- SQL Server `ONLINE`, `RESUMABLE`, `MAX_DURATION`, `MAXDOP`, `SORT_IN_TEMPDB`;
- Db2 for z/OS `DEFINE` / `DEFER` in the explicit P7 build-option path.

Build options are never inferred from current database metadata.

## 8. Snapshot compatibility

The canonical snapshot layer round-trips physical options for tables, columns, PKs, UKs, and indexes. Index snapshots also preserve `buildOptions` independently.

Snapshot compatibility is guarded by explicit snapshot/model/parser versions and source hashes. Dialect-only changes do not require reparsing the historical Word corpus when a compatible canonical snapshot exists.
