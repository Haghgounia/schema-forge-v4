# SchemaForge V4 - Artifact Naming/Layout Design Decisions (C5.2)

Status: **C5.2 DESIGN DECISIONS COMPLETE - NO RUNTIME IMPLEMENTATION YET**  
Parent analysis: `ARTIFACT-NAMING-LAYOUT-C5.1.md`  
Baseline source: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C4.3`

## 1. Purpose

C5.2 records the naming/layout decisions that must be fixed before any C5 Java implementation starts. These decisions are consumer-visible by design, so the implementation phase must follow them exactly and regression-test the new contract.

## 2. Decision D1 - artifact-first directory roots

**Decision: APPROVED**

The same artifact type uses the same root path regardless of whether it came from Word, Legacy Word, ZIP Batch, or EA.

```text
ddl/<platform>/
migration/<platform>/
crud/<platform>/
model/
comparison/<platform>/
diagram/mermaid/tables/
diagram/mermaid/batch/
diagram/graphviz/tables/
diagram/graphviz/batch/
scripts/<platform>/
reports/
```

Rationale:

- eliminates endpoint-specific placement;
- makes artifact type discoverable from the path;
- preserves platform as a second-level classification where relevant;
- maps directly to C4 `ArtifactType`.

`manifest.json` remains a reserved root artifact for C6 and is not introduced by C5.

## 3. Decision D2 - canonical JSON identity

**Decision: APPROVED**

Canonical JSON uses the existing canonical snapshot suffix already recognized elsewhere in SchemaForge:

```text
model/<source>_<generation-timestamp>.schema.json
```

Do **not** introduce a competing `*.canonical.json` convention.

Rationale:

- `*.schema.json` is already used by canonical snapshot tooling and Mermaid canonical-input loading;
- avoids two names for the same canonical concept;
- removes the current Word `<source>_<ts>.json` vs EA `model.json` inconsistency.

## 4. Decision D3 - comparison workbook identity

**Decision: APPROVED**

```text
comparison/<platform>/<schema.table>_<generation-timestamp>.<platform>.compare.xlsx
```

Examples:

```text
comparison/oracle/BIM.PROVINCES_20260823_091500_123.oracle.compare.xlsx
comparison/postgresql/bim.provinces_20260823_091500_123.postgresql.compare.xlsx
```

Rules:

- every comparison workbook has the generation timestamp;
- platform token remains in the filename as well as the path because the file can be copied independently;
- the semantic token is `compare`, preserving continuity with the current `_compare_` naming while normalizing its position.

Identifier casing is not forcibly changed by C5; logical naming continues to respect source/dialect conventions.

## 5. Decision D4 - diagram identities

**Decision: APPROVED**

Per-source packaged diagrams:

```text
diagram/mermaid/tables/<source>_<ts>.er.mmd
diagram/mermaid/tables/<source>_<ts>.conceptual-erd.mmd

diagram/graphviz/tables/<source>_<ts>.er.dot
diagram/graphviz/tables/<source>_<ts>.conceptual-erd.dot
```

Batch diagrams keep their existing semantic fixed names but move under the common `diagram/` root:

```text
diagram/mermaid/batch/schema-er.mmd
diagram/mermaid/batch/schema-conceptual-erd.mmd
diagram/mermaid/batch/schema-dependency.mmd

diagram/graphviz/batch/schema-conceptual-erd.dot
diagram/graphviz/batch/schema-dependency.dot
diagram/graphviz/batch/schema-clustered.dot
diagram/graphviz/batch/schema-compact.dot
diagram/graphviz/batch/schema-overview.dot
```

Standalone Mermaid retains deterministic selector-based filenames (no timestamp) but the grammar must move under the central naming policy. C5 changes ownership of the rule, not the existing standalone semantics.

## 6. Decision D5 - one generation timestamp per top-level request

**Decision: APPROVED**

A top-level generation request has:

```text
generationId
+
generationTimestamp
```

Both are shared by all child contexts.

Consequences:

- Word/Legacy: all normal artifacts use one timestamp;
- EA: all per-table DDL, comparison, model, diagrams, summaries, and run-all scripts use one timestamp;
- ZIP Batch: all source documents in the same batch use the **same batch generation timestamp**;
- batch-level reports/diagrams use the same timestamp context where the filename grammar contains a timestamp;
- standalone Mermaid remains deterministic and non-timestamped;
- Flyway migration filenames retain independent monotonic Flyway versions and do not use the general artifact timestamp grammar.

Implementation direction: extend `ArtifactGenerationContext` with one request-local naming timestamp rather than recomputing timestamps in individual writers.

## 7. Decision D6 - deterministic collision policy

**Decision: APPROVED**

Generalize the existing DDL collision principle to every artifact path that can collide inside one package.

Normal identity:

```text
<logical>_<timestamp>...
```

On collision:

```text
<logical>__sf_<10-hex-stable-hash>_<timestamp>...
```

Rules:

1. first reservation keeps the normal name;
2. suffix is deterministic from source identity/artifact identity;
3. no random UUID is exposed in consumer-visible filenames;
4. collision is resolved against the **final canonical relative path**, not a staging path;
5. Flyway migration collision behavior remains governed by `MigrationFileWriter`/Flyway naming and is not folded into this allocator.

## 8. Decision D7 - compatibility strategy

**Decision: APPROVED - SINGLE CONTRACT SWITCH**

C5 will not introduce a parallel `legacyLayout=true` mode and will not duplicate artifacts under old and new paths.

Rationale:

- dual layouts would preserve the inconsistency C5 is meant to remove;
- duplicate artifacts make C6 manifest semantics ambiguous;
- the project is still consolidating V4 contracts before a front-end/API ecosystem is frozen.

Compatibility protection instead consists of:

1. unchanged REST endpoint URLs;
2. unchanged SQL contents and DBMS semantics;
3. unchanged Flyway filename grammar;
4. unchanged CRUD semantic filename suffixes;
5. explicit ZIP/path contract tests updated atomically with the implementation;
6. release/baseline documentation listing every consumer-visible path change.

## 9. Central naming authority design

**Decision: use one artifact naming policy, not additional ad hoc helpers.**

Implementation should introduce a central policy abstraction (recommended name: `ArtifactNamingPolicy`) responsible for:

- final relative directory by `ArtifactType`/platform;
- normal filename grammar;
- request timestamp use;
- deterministic collision-safe allocation;
- standalone Mermaid deterministic naming profile.

`FlywayMigrationNamer` remains authoritative for migration **filenames**. `ArtifactNamingPolicy` only determines the migration directory.

`OutputFileNamer` should be migrated behind/into the new policy rather than leaving two independent public naming authorities. The exact mechanical migration is an implementation concern for C5.3, but the end state is one public naming policy for non-Flyway artifact identities.

## 10. Canonical target examples

For one Word/Legacy source `MCB.BIM.TBL.PROVINCES.V1.2.docx`:

```text
ddl/oracle/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.oracle.sql
ddl/postgresql/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.postgresql.sql
ddl/db2zos/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.db2zos.sql
ddl/sqlserver/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.sqlserver.sql
ddl/mysql/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.mysql.sql

migration/oracle/V...__BIM_PROVINCES_ALTER.sql
...

crud/oracle/BIM.PROVINCES_<ts>.oracle.crud-package.sql
crud/sqlserver/BIM.PROVINCES_<ts>.sqlserver.crud-procedures.sql

model/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.schema.json

comparison/oracle/BIM.PROVINCES_<ts>.oracle.compare.xlsx
comparison/postgresql/BIM.PROVINCES_<ts>.postgresql.compare.xlsx
...

diagram/mermaid/tables/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.er.mmd
diagram/mermaid/tables/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.conceptual-erd.mmd
diagram/graphviz/tables/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.er.dot
diagram/graphviz/tables/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.conceptual-erd.dot

reports/MCB.BIM.TBL.PROVINCES.V1.2_<ts>.metadata-crud-summary.csv
```

For EA, per-table DDL uses the exact same `ddl/<platform>/...` rule; the EA run-all scripts use:

```text
scripts/<platform>/<source>_<ts>.<platform>.run-all.sql
```

## 11. Explicit non-goals

C5 implementation must not change:

- parser behavior;
- canonical domain semantics;
- DDL SQL content;
- physical DDL content;
- migration diff/safety semantics;
- Flyway filename grammar;
- metadata recovery;
- CRUD SQL content;
- REST endpoint URLs;
- error contract (C7);
- manifest schema (C6);
- `SchemaForgeApiService` decomposition (C8).

## 12. Gate for C5.3 implementation

C5.3 may start only with these decisions as its fixed input. Before Java changes, its stage-start change list must identify:

1. new/modified naming-policy classes;
2. every current manual naming call-site to migrate;
3. every current output directory call-site to migrate;
4. affected tests and expected path changes;
5. exact invariant that SQL bytes remain unchanged;
6. rollback/diagnostic evidence if artifact-set equality fails.
