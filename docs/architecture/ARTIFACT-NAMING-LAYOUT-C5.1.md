# SchemaForge V4 - Artifact Naming and Layout Analysis (C5.1)

Status: **C5.1 ANALYSIS COMPLETE - PROPOSAL ONLY / NOT IMPLEMENTED**  
Baseline: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C4.3`  
Source fingerprint: `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`

## 1. Objective

C5.1 inventories the current consumer-visible artifact names and package-relative paths and defines a proposed common naming/layout target for C5.2-C5.4 design. No runtime code, file name, ZIP path, SQL, REST response, parser, or generation semantic is changed by C5.1.

## 2. Current naming authorities

The current system does not have one naming authority for all artifacts.

| Authority | Current responsibility |
|---|---|
| `OutputFileNamer` | DDL, CRUD and EA run-all SQL naming; generic timestamped non-SQL helper |
| `FlywayMigrationNamer` | Flyway migration version/name contract |
| `SchemaForgeApiService` | comparison workbook, canonical JSON, Mermaid, Graphviz, conceptual ERD, reports, EA model/manifest, batch paths |
| `MermaidDiagramGenerationService` | standalone Mermaid deterministic selector-based filename |
| `packagedBatchTarget(...)` | remaps Word staging artifacts into ZIP batch-specific directories |
| batch diagram writers | fixed batch diagram/report filenames |

This split is the core C5 issue: artifact identity exists after C4, but naming and placement are still endpoint-specific.

## 3. Current layouts

### 3.1 Word / Legacy Word

```text
<source>_<timestamp>.<platform>.sql
<schema>.<table>_compare_<timestamp>.<platform>.xlsx
<source>_<timestamp>.json
<source>_<timestamp>.mermaid.mmd
<source>_<timestamp>.graphviz.dot
<source>_<timestamp>.conceptual-erd.mermaid.mmd
<source>_<timestamp>.conceptual-erd.graphviz.dot
<source>_<timestamp>.metadata-crud-summary.csv
<platform>/migrations/V...__..._ALTER.sql
oracle/crud/*
sqlserver/crud/*
```

The root is mostly flat, while migrations/CRUD already use platform directories.

### 3.2 ZIP Batch

```text
<platform>/*.sql
<platform>/migrations/*
<platform>/crud/*
excel/*.xlsx
json/*.json
mermaid/tables/*
graphviz/tables/*
mermaid/batch/*
graphviz/batch/*
reports/*
```

ZIP Batch does not generate different per-document semantics; it repackages Word artifacts into a second directory contract.

### 3.3 EA XML/XMI

```text
<platform>/<table>_<timestamp>.<platform>.sql
<platform>/<source>_<timestamp>.<platform>.run-all.sql
comparison/<platform>/<table>.<platform>.xlsx
<platform>/migrations/*
<platform>/crud/*
<source>_<timestamp>.metadata-crud-summary.csv
<source>_<timestamp>.mermaid.mmd
<source>_<timestamp>.graphviz.dot
<source>_<timestamp>.conceptual-erd.mermaid.mmd
<source>_<timestamp>.conceptual-erd.graphviz.dot
model.json
manifest.json
```

EA is the third distinct layout and also has different canonical JSON and comparison workbook identities.

### 3.4 Standalone endpoints

CRUD returns the existing SQL filename directly. Standalone Mermaid uses deterministic selector-based names such as:

```text
schema__er-all.mmd
<schema>__<type>-schema.mmd
<schema_table>__<type>-table-with-dependencies-depth-<n>.mmd
```

## 4. C5 target principles

1. Artifact **type and DBMS decide the directory**, not the endpoint that generated it.
2. Word, Legacy Word, ZIP Batch, and EA use the same package-relative layout for the same `ArtifactType`.
3. `ArtifactDescriptor.relativePath` is authoritative and equals the real archive entry.
4. One top-level generation request has one `generationId`; user-facing filenames do not need to expose the full generation ID.
5. One source-generation unit uses one shared timestamp for normal non-Flyway artifacts.
6. Flyway `V...__DESCRIPTION.sql` naming remains unchanged because it is an external execution contract.
7. SQL platform token remains the existing `DatabasePlatform.commandLineName()` value.
8. Collision handling remains deterministic; existing `__sf_<hash>` behavior should be preserved/generalized rather than replaced by random suffixes.
9. Paths use `/` in the Artifact Contract independent of host OS.
10. C5 does not change SQL content, parsers, metadata semantics, migration diff/safety, or REST business behavior.

## 5. Proposed canonical layout

The following is the C5.1 proposal for C5.2 design review; it is not implemented yet.

```text
ddl/
    oracle/
    postgresql/
    db2zos/
    sqlserver/
    mysql/

migration/
    oracle/
    postgresql/
    db2zos/
    sqlserver/
    mysql/

crud/
    oracle/
    sqlserver/

model/

comparison/
    oracle/
    postgresql/
    db2zos/
    sqlserver/
    mysql/

diagram/
    mermaid/
        tables/
        batch/
    graphviz/
        tables/
        batch/

scripts/
    oracle/
    postgresql/
    db2zos/
    sqlserver/
    mysql/

reports/

manifest.json        # introduced/standardized in C6, not by C5
```

`manifest.json` is shown only to reserve its future root identity. C6 remains responsible for the shared manifest schema and generation.

## 6. Proposed naming grammar

### 6.1 Normal timestamped artifacts

Canonical base grammar:

```text
<logical-name>_<timestamp>.<artifact-token>.<extension>
```

Platform-specific artifacts include the platform token where it remains useful to the file outside its directory:

```text
<logical-name>_<timestamp>.<platform>.sql
<logical-name>_<timestamp>.<platform>.comparison.xlsx
<logical-name>_<timestamp>.<platform>.crud-package.sql
<logical-name>_<timestamp>.<platform>.crud-procedures.sql
<logical-name>_<timestamp>.<platform>.run-all.sql
```

DBMS-neutral artifacts use semantic type tokens rather than endpoint-specific names:

```text
<source>_<timestamp>.schema.json
<source>_<timestamp>.er.mmd
<source>_<timestamp>.er.dot
<source>_<timestamp>.conceptual-erd.mmd
<source>_<timestamp>.conceptual-erd.dot
<source>_<timestamp>.metadata-crud-summary.csv
```

### 6.2 Flyway migrations

Keep unchanged:

```text
V<monotonic-version>__<SCHEMA_TABLE>_ALTER.sql
```

Only the enclosing path changes from `<platform>/migrations/` to `migration/<platform>/`.

### 6.3 Standalone Mermaid

Keep selector semantics deterministic, but route naming through the central naming policy. Proposed filename grammar remains compatible with the existing names:

```text
schema__er-all.mmd
<schema>__<type>-schema.mmd
<schema_table>__<type>-table.mmd
<schema_table>__<type>-table-with-dependencies-depth-<n>.mmd
selected_<n>_tables__<type>-selected-tables.mmd
```

The main C5 change for standalone Mermaid is **central ownership of the grammar**, not forcing timestamps into a deterministic query result.

## 7. Current -> proposed mapping

| Artifact | Current Word/Legacy | Current ZIP Batch | Current EA | Proposed common path/name |
|---|---|---|---|---|
| DDL | root `<source>_<ts>.<db>.sql` | `<db>/<source>_<ts>.<db>.sql` | `<db>/<table>_<ts>.<db>.sql` | `ddl/<db>/<logical>_<ts>.<db>.sql` |
| Migration | `<db>/migrations/V...sql` | same | same | `migration/<db>/V...sql` |
| Oracle CRUD | `oracle/crud/<table>_<ts>.oracle.crud-package.sql` | same | same | `crud/oracle/<table>_<ts>.oracle.crud-package.sql` |
| SQL Server CRUD | `sqlserver/crud/<table>_<ts>.sqlserver.crud-procedures.sql` | same | same | `crud/sqlserver/<table>_<ts>.sqlserver.crud-procedures.sql` |
| Canonical JSON | root `<source>_<ts>.json` | `json/<source>_<ts>.json` | root `model.json` | `model/<source>_<ts>.canonical.json` |
| Comparison workbook | root `<table>_compare_<ts>.<db>.xlsx` | `excel/*.xlsx` | `comparison/<db>/<table>.<db>.xlsx` | `comparison/<db>/<table>_<ts>.<db>.comparison.xlsx` |
| Mermaid ER | root `<source>_<ts>.mermaid.mmd` | `mermaid/tables/*` | root same | `diagram/mermaid/tables/<source>_<ts>.er.mmd` |
| Graphviz ER | root `<source>_<ts>.graphviz.dot` | `graphviz/tables/*` | root same | `diagram/graphviz/tables/<source>_<ts>.er.dot` |
| Conceptual Mermaid | root `*.conceptual-erd.mermaid.mmd` | under `mermaid/tables` | root same | `diagram/mermaid/tables/<source>_<ts>.conceptual-erd.mmd` |
| Conceptual Graphviz | root `*.conceptual-erd.graphviz.dot` | under `graphviz/tables` | root same | `diagram/graphviz/tables/<source>_<ts>.conceptual-erd.dot` |
| EA run-all | N/A | N/A | `<db>/<source>_<ts>.<db>.run-all.sql` | `scripts/<db>/<source>_<ts>.<db>.run-all.sql` |
| CRUD summary | root `*.metadata-crud-summary.csv` | `reports/*` | root same | `reports/<source>_<ts>.metadata-crud-summary.csv` |
| Batch Mermaid | N/A | `mermaid/batch/*` | N/A | `diagram/mermaid/batch/*` |
| Batch Graphviz | N/A | `graphviz/batch/*` | N/A | `diagram/graphviz/batch/*` |
| Batch summary/errors | N/A | `reports/*` | N/A | `reports/*` (keep existing filenames) |
| Manifest | none | none | root `manifest.json` | root `manifest.json` in C6 |

## 8. Key normalization decisions proposed for C5.2

### 8.1 Directory vocabulary

Use singular functional roots:

```text
ddl/
migration/
crud/
model/
comparison/
diagram/
scripts/
reports/
```

This avoids mixing platform-first (`oracle/...`) and artifact-first (`comparison/oracle/...`) conventions.

### 8.2 Platform casing

Use existing lower-case command-line platform tokens for paths:

```text
oracle
postgresql
db2zos
sqlserver
mysql
```

Do not change table/schema identifier casing merely for naming consistency. Logical-name casing remains source/dialect aware; the naming layer only sanitizes illegal filename characters and collision cases.

### 8.3 Timestamp policy

- Word/Legacy: one shared timestamp for all normal artifacts from that source request.
- EA: one shared request timestamp for per-table DDL, comparison, model, diagrams, run-all and summaries.
- ZIP Batch: each source document may retain its own source-generation timestamp; batch-level artifacts use the batch generation timestamp.
- Flyway migrations: retain their independent monotonic Flyway version naming.
- Standalone Mermaid: deterministic selector-based filename remains non-timestamped.

C5.2 should decide whether a single explicit `GenerationNamingContext` should carry the request timestamp instead of recomputing/transporting raw strings.

### 8.4 Collision policy

Current DDL collision protection via `CollisionSafeScriptTargetAllocator` is sound and deterministic. C5 should generalize the same principle to all artifact types that can collide inside one package:

```text
first identity  -> normal name
collision       -> <logical>__sf_<stable-hash>_<timestamp>...
```

No random UUID suffix should be used for consumer-visible filenames when a deterministic stable suffix is sufficient.

## 9. Compatibility impact by change

| Proposed change | Consumer impact | Risk |
|---|---|---|
| Move root Word DDL to `ddl/<db>/` | ZIP/archive paths change | High |
| Move migrations to `migration/<db>/` | Flyway filename unchanged; archive path changes | Medium |
| Move CRUD to `crud/<db>/` | archive path changes | Medium |
| `json/` or `model.json` -> `model/*.canonical.json` | filename + path change | High |
| comparison naming normalization | filename + path change | High |
| diagram path/token normalization | filename + path change | High |
| batch `mermaid/graphviz` -> `diagram/...` | path change | Medium |
| run-all -> `scripts/<db>/` | path change; script internal relative references must remain correct | High |
| standalone Mermaid centralization only | expected same filename | Low |

Because C5 is intentionally consumer-visible, compatibility tests must assert the new contract rather than silently preserving all old paths.

## 10. Explicitly preserved external contracts

C5 should preserve these unless a separate decision is recorded:

1. Flyway migration filename grammar and monotonic version behavior.
2. `.oracle.crud-package.sql` and `.sqlserver.crud-procedures.sql` semantic suffixes.
3. platform command-line tokens (`oracle`, `postgresql`, `db2zos`, `sqlserver`, `mysql`).
4. standalone Mermaid selector semantics.
5. SQL file contents and execution order semantics.
6. `ArtifactDescriptor.relativePath == actual package entry` invariant from C4.3.

## 11. C5.2 design work before implementation

Before changing Java source, C5.2 must explicitly approve or revise:

1. canonical directory vocabulary;
2. canonical JSON filename (`*.schema.json`, aligned with the existing canonical snapshot contract);
3. comparison filename grammar;
4. diagram semantic suffixes (`.er.mmd`, `.er.dot`, conceptual names);
5. timestamp scope for EA and ZIP Batch;
6. generalized collision policy;
7. compatibility strategy: immediate contract switch vs optional legacy-layout mode.

No code implementation should begin until these seven decisions are recorded.

## 12. C5.1 conclusion

The current inconsistency is not in artifact semantics; it is in naming ownership and endpoint-specific placement. C4.3 now gives every produced artifact a tracked relative path, which makes C5 safe to implement as a controlled path/name transformation. The recommended direction is **artifact-first directories + existing platform tokens + shared timestamp context + unchanged Flyway filename contract**.
