# SchemaForge V4 - Artifact Inventory (C4.1)

**Baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C1`  
**Source fingerprint:** `77e038a4acb5631d4a407174d9e075cc3d773d21b96a7e884410d9fbdc00525c`  
**Stage:** `C4.1 - Artifact Inventory`  
**Status:** `DONE / SOURCE-DERIVED`  

## 1. Purpose

This document records the artifact families that SchemaForge V4 actually emits from the frozen C1 source. It is an inventory only. It does not define the final Artifact Contract V1 and does not change any existing filename, directory layout, media type, SQL semantics, parser behavior, or REST behavior.

The inventory is the source-derived input to `C4.2`, where common artifact attributes and the contract model will be designed.

## 2. Generation entry points covered

The inventory covers all currently exposed REST generation paths:

1. `POST /api/v1/generate/word`
2. `POST /api/v1/generate/legacy-word`
3. `POST /api/v1/generate/zip`
4. `POST /api/v1/generate/ea-xml`
5. `POST /api/v1/generate/oracle/crud`
6. `POST /api/v1/generate/sqlserver/crud`
7. `POST /api/v1/diagram/mermaid/canonical-json`

It also records artifacts written by the common migration, comparison, diagram, and metadata-driven CRUD producers when those producers are enabled by repository availability and table conditions.

## 3. Current artifact families

| Family | Current content | Platform-specific | Current extensions / media | Conditional |
|---|---|---:|---|---:|
| DDL | CREATE-oriented database DDL | Yes - 5 DBMS | `.sql` / `application/sql` when standalone SQL | No for main generation |
| Migration | Flyway-compatible ALTER migration | Yes - 5 DBMS | `.sql` | Yes - live table + non-empty diff |
| CRUD | Oracle package / SQL Server procedures | Yes - Oracle, SQL Server | `.sql` / `application/sql` | Yes - metadata + PK |
| Canonical/validation JSON | Canonical schema plus validation report | No | `.json` | No for Word/Legacy/EA |
| Comparison workbook | Document/canonical vs live metadata comparison | Yes - 5 logical; physical sheets only where supported | `.xlsx` | Yes - repository + table resolution |
| Mermaid ER | Table/ER diagram | No | `.mmd` / `text/plain` in standalone endpoint | No for normal generation |
| Graphviz ER | DOT diagram | No | `.dot` | No for normal generation |
| Conceptual ERD Mermaid | Field-free conceptual ERD | No | `.mmd` | No for normal generation |
| Conceptual ERD Graphviz | Field-free conceptual ERD | No | `.dot` | No for normal generation |
| CRUD generation summary | Per-table metadata CRUD generated/skipped/failed status | Oracle + SQL Server rows | `.csv` | No for Word/Legacy/EA; rows may be skips |
| EA manifest | EA package inventory/dependency metadata | Mixed | `manifest.json` | EA only |
| EA run-all | Ordered per-platform execution helper | Yes - 5 DBMS | `.sql` | EA only |
| Batch Mermaid diagrams | ER/conceptual/dependency set | No | `.mmd` | ZIP batch with usable tables |
| Batch Graphviz diagrams | Conceptual/dependency/cluster/compact/overview set | No | `.dot` | ZIP batch with usable tables |
| Batch diagram issues | Duplicate/unresolved batch graph issues | No | `.csv` | ZIP batch |
| Batch diagram summary | Batch diagram counts/policy/profile | No | `.txt` | ZIP batch |
| Batch generation summary | Per-input success/failure/generated file count | No | `.csv` | ZIP batch |
| Batch generation errors | Detailed failed-input log | No | `.log` | ZIP batch |

No final `ArtifactType` enum is defined by this inventory. The rows above are source-derived families and may be normalized or split during C4.2/C4.3 design.

## 4. Word and Legacy Word current output layout

`/generate/word` and `/generate/legacy-word` both use `writeAllDatabaseOutputs(...)`. Their package root is currently mostly flat, with platform subdirectories used only for migration and metadata-driven CRUD.

Representative layout:

```text
<source>_<timestamp>.oracle.sql
<source>_<timestamp>.postgresql.sql
<source>_<timestamp>.db2zos.sql
<source>_<timestamp>.sqlserver.sql
<source>_<timestamp>.mysql.sql

<schema>.<table>_compare_<timestamp>.oracle.xlsx
<schema>.<table>_compare_<timestamp>.postgresql.xlsx
<schema>.<table>_compare_<timestamp>.db2zos.xlsx
<schema>.<table>_compare_<timestamp>.sqlserver.xlsx
<schema>.<table>_compare_<timestamp>.mysql.xlsx

<source>_<timestamp>.json
<source>_<timestamp>.mermaid.mmd
<source>_<timestamp>.graphviz.dot
<source>_<timestamp>.conceptual-erd.mermaid.mmd
<source>_<timestamp>.conceptual-erd.graphviz.dot
<source>_<timestamp>.metadata-crud-summary.csv

oracle/migrations/V...__..._ALTER.sql
postgresql/migrations/V...__..._ALTER.sql
db2zos/migrations/V...__..._ALTER.sql
sqlserver/migrations/V...__..._ALTER.sql
mysql/migrations/V...__..._ALTER.sql

oracle/crud/<SCHEMA.TABLE>_<timestamp>.oracle.crud-package.sql
sqlserver/crud/<SCHEMA.TABLE>_<timestamp>.sqlserver.crud-procedures.sql
```

Notes:

- All five DDL scripts for one source share one request timestamp.
- Comparison workbooks share the same request timestamp used by the main document generation.
- Migration filenames are produced by the migration subsystem/Flyway namer and do not use the same visible naming pattern as normal DDL.
- CRUD is currently emitted only for Oracle and SQL Server.
- `metadata-crud-summary.csv` is written even when CRUD rows are skipped.

## 5. ZIP batch current output layout

Each DOCX is first generated using the normal Word path, then `packagedBatchTarget(...)` relocates already-generated files into a batch-specific layout.

Current mapping:

```text
oracle/*.oracle.sql
postgresql/*.postgresql.sql
db2zos/*.db2zos.sql
sqlserver/*.sqlserver.sql
mysql/*.mysql.sql

oracle/migrations/*
postgresql/migrations/*
db2zos/migrations/*
sqlserver/migrations/*
mysql/migrations/*

oracle/crud/*
sqlserver/crud/*

excel/*.xlsx
json/*.json
mermaid/tables/*.mermaid.mmd
graphviz/tables/*.graphviz.dot
reports/*.metadata-crud-summary.csv
```

Batch-level additions:

```text
mermaid/batch/schema-er.mmd
mermaid/batch/schema-conceptual-erd.mmd
mermaid/batch/schema-dependency.mmd
mermaid/batch/issues.csv
mermaid/batch/summary.txt

graphviz/batch/schema-conceptual-erd.dot
graphviz/batch/schema-dependency.dot
graphviz/batch/schema-clustered.dot
graphviz/batch/schema-compact.dot
graphviz/batch/schema-overview.dot
graphviz/batch/issues.csv
graphviz/batch/summary.txt

reports/batch-generation-summary.csv
reports/batch-generation-errors.log
```

Important C4 observation: ZIP batch does not generate a different semantic DDL model; it repackages normal per-document artifacts into a different directory contract.

## 6. EA XML/XMI current output layout

EA uses a third layout. DDL is per table and placed under each platform directory. Comparison files are placed under a dedicated comparison tree. The package also contains a root manifest and root canonical model.

Representative layout:

```text
oracle/<SCHEMA.TABLE>_<timestamp>.oracle.sql
postgresql/<schema.table>_<timestamp>.postgresql.sql
db2zos/<SCHEMA.TABLE>_<timestamp>.db2zos.sql
sqlserver/<SCHEMA.TABLE>_<timestamp>.sqlserver.sql
mysql/<SCHEMA.TABLE>_<timestamp>.mysql.sql

oracle/<source>_<timestamp>.oracle.run-all.sql
postgresql/<source>_<timestamp>.postgresql.run-all.sql
db2zos/<source>_<timestamp>.db2zos.run-all.sql
sqlserver/<source>_<timestamp>.sqlserver.run-all.sql
mysql/<source>_<timestamp>.mysql.run-all.sql

comparison/oracle/<SCHEMA.TABLE>.oracle.xlsx
comparison/postgresql/<schema.table>.postgresql.xlsx
comparison/db2zos/<SCHEMA.TABLE>.db2zos.xlsx
comparison/sqlserver/<SCHEMA.TABLE>.sqlserver.xlsx
comparison/mysql/<SCHEMA.TABLE>.mysql.xlsx

<platform>/migrations/V...__..._ALTER.sql
oracle/crud/*
sqlserver/crud/*

<source>_<timestamp>.metadata-crud-summary.csv
<source>_<timestamp>.mermaid.mmd
<source>_<timestamp>.graphviz.dot
<source>_<timestamp>.conceptual-erd.mermaid.mmd
<source>_<timestamp>.conceptual-erd.graphviz.dot
model.json
manifest.json
```

EA-specific observations:

- PostgreSQL per-table DDL and comparison logical base names are lower-cased; other DBMSs retain the source-style casing.
- EA comparison workbook names do not include the shared request timestamp.
- `model.json` is the canonical/validation JSON identity for EA, unlike timestamped JSON in Word/Legacy.
- `manifest.json` currently exists only in this main REST package path.
- `manifest.json` is an ad hoc map structure, not yet a versioned shared artifact contract.

## 7. Standalone CRUD current artifact contract

The Oracle and SQL Server CRUD endpoints return one SQL file directly rather than a ZIP.

Oracle naming:

```text
SCHEMA.TABLE_<timestamp>.oracle.crud-package.sql
```

SQL Server naming:

```text
SCHEMA.TABLE_<timestamp>.sqlserver.crud-procedures.sql
```

HTTP behavior:

- payload: raw UTF-8 SQL bytes;
- content type: `application/sql;charset=UTF-8`;
- `Content-Disposition`: attachment using the generated SQL filename;
- errors are currently endpoint-local JSON maps and are outside C4 inventory semantics.

## 8. Standalone Mermaid current artifact contract

`/api/v1/diagram/mermaid/canonical-json` returns one Mermaid file directly.

Filename is deterministic from diagram selector/type/scope and is not timestamped. Examples of the naming grammar:

```text
schema__er-all.mmd
<schema>__<type>-schema.mmd
<schema_table>__<type>-table.mmd
<schema_table>__<type>-table-with-dependencies-depth-<n>.mmd
selected_<n>_tables__<type>-selected-tables.mmd
```

HTTP behavior:

- payload: UTF-8 Mermaid text;
- content type: `text/plain;charset=UTF-8`;
- `Content-Disposition`: attachment using the deterministic filename;
- response includes diagram metadata headers for type, scope, and input-table count.

This endpoint does not use `OutputFileNamer` and currently has no generation timestamp in the artifact identity.

## 9. Current naming authorities

Artifact names are currently controlled by multiple mechanisms:

| Mechanism | Current scope |
|---|---|
| `OutputFileNamer` | DDL SQL, CRUD SQL, EA run-all SQL; generic helper exists but is not used everywhere |
| `FlywayMigrationNamer` / migration subsystem | Migration SQL |
| Manual string construction in `SchemaForgeApiService` | JSON, comparison XLSX, Mermaid, Graphviz, conceptual ERD, metadata CRUD summary, EA model/manifest |
| Fixed constants in `SchemaForgeApiService` | Batch report and batch diagram filenames/directories |
| `MermaidDiagramGenerationService.outputFileName(...)` | Standalone canonical-JSON Mermaid |
| `SchemaForgeController.archiveName(...)` | HTTP ZIP download filenames |

This confirms that the comment in `OutputFileNamer` describing it as the central policy for every generated artifact is broader than its actual current usage.

## 10. Current HTTP container identities

Main generation endpoints return ZIP files with controller-generated archive names:

```text
schemaforge-word-output_<timestamp>.zip
schemaforge-legacy-word-output_<timestamp>.zip
schemaforge-batch-output_<timestamp>.zip
schemaforge-ea-output_<timestamp>.zip
```

These archive timestamps are generated by the controller and are independent from the internal request timestamp created by `OutputFileNamer` inside generation. Therefore one HTTP response can currently have two different generation-time identities: one in the ZIP filename and another in internal artifact names.

Standalone CRUD and standalone Mermaid return raw single-artifact responses rather than ZIP packages.

## 11. Contract gaps confirmed by C4.1

The following are facts of the current source and are inputs to C4.2; they are not fixes applied in C4.1.

1. There is no shared `GeneratedArtifact` abstraction across generation paths.
2. There is no shared `ArtifactType` model.
3. Word/Legacy, ZIP batch, and EA use different directory layouts for semantically equivalent artifacts.
4. Canonical JSON identity differs: timestamped `<source>_<timestamp>.json` vs EA `model.json`.
5. Comparison naming differs: timestamped root files vs EA non-timestamped comparison tree.
6. Standalone Mermaid uses a separate deterministic naming policy and no `OutputFileNamer`.
7. HTTP ZIP filename timestamp and internal generation timestamp are independently created.
8. EA alone has a root `manifest.json`; Word, Legacy, and ZIP batch do not have a shared manifest.
9. Migration identity is managed separately from normal SQL naming.
10. Batch diagram/report files use fixed names and directories.
11. Artifact status is represented indirectly in CSV/log outputs rather than one common artifact metadata model.
12. Provenance/source identity is embedded inconsistently in filenames, schema metadata, EA manifest, and endpoint context.

## 12. C4.1 decisions

C4.1 makes no behavioral design decision beyond preserving the source-derived inventory.

The following are intentionally deferred to C4.2/C4.3:

- exact final `ArtifactType` values;
- whether package ZIP itself is modeled as an artifact or only as a transport container;
- exact generation-ID semantics;
- whether manifest/report artifacts have a platform of `NONE`, `MULTI`, or an optional platform field;
- content checksum policy;
- final media-type vocabulary;
- final naming/layout policy (C5 responsibility).

## 13. Exit evidence

C4.1 is complete when:

- all seven REST generation entry points have been inspected;
- Word/Legacy shared output has been inventoried;
- ZIP remapping and batch-only artifacts have been inventoried;
- EA per-table, comparison, migration, run-all, CRUD, diagram, model and manifest outputs have been inventoried;
- standalone CRUD and Mermaid outputs have been inventoried;
- naming authorities and current inconsistencies are explicitly recorded;
- no Java source/runtime behavior has been changed.

All conditions above are satisfied for the frozen C1 source.
