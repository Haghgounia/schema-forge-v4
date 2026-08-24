# SchemaForge V4 - Artifact Contract V1 (C4 Complete)

**C4 frozen checkpoint:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C4.3`  
**Current official project baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C6.2`  
**Stage:** `C4 - Artifact Contract V1 (C4.1 inventory + C4.2 core model + C4.3 production-path mapping)`  
**Contract version:** `1`  
**Frozen C4.3 source fingerprint:** `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`  
**Status:** `C4 COMPLETE / USER-VERIFIED / PRODUCTION PATHS WIRED`  

## 1. Purpose

Artifact Contract V1 gives SchemaForge one database-neutral metadata model for every artifact generation outcome. It is intentionally separate from parser behavior, SQL semantics, physical DDL, migration diff logic, filename policy, ZIP layout, and HTTP transport.

The contract is metadata only. It does not carry artifact bytes and it does not decide where a file is physically written.

C4.2 is based on the source-derived inventory in `SCHEMAFORGE-ARTIFACT-INVENTORY.md`. No artifact family was invented solely for future functionality.

## 2. Core model

The implementation lives under:

```text
com.behsazan.schemaforge.artifact
```

Core types:

```text
ArtifactContract
ArtifactDescriptor
ArtifactType
ArtifactStatus
ArtifactOrigin
ArtifactProvenance
```

Conceptual model:

```text
ArtifactDescriptor
    type             ArtifactType
    platform         DatabasePlatform?   // null means DBMS-neutral
    logicalName      String
    relativePath     String
    mediaType        String
    generationId     String
    status           ArtifactStatus
    provenance       ArtifactProvenance
```

The name `ArtifactDescriptor` is deliberate. Contract V1 must represent `GENERATED`, `SKIPPED`, and `FAILED` outcomes. Calling the model `GeneratedArtifact` would incorrectly imply that a file always exists.

## 3. Contract version semantics

Current metadata contract version:

```text
ArtifactContract.VERSION = "1"
```

This version applies only to the artifact metadata schema. It does **not** version:

- Oracle/PostgreSQL/Db2-zOS/SQL Server/MySQL SQL semantics;
- canonical-domain semantics;
- parser behavior;
- migration safety rules;
- filename grammar;
- directory layout;
- ZIP archive naming;
- REST endpoint versioning.

A future incompatible metadata-schema change must increment the artifact contract version. Additive behavior that does not invalidate V1 consumers may remain within the same contract version, subject to the later manifest policy in C6.

## 4. Artifact types

Contract V1 normalizes the C4.1 source inventory into these top-level semantic types:

| ArtifactType | C4.1 inventory mapping |
|---|---|
| `DDL` | Five-DBMS create-oriented DDL |
| `MIGRATION` | Flyway-compatible ALTER migration SQL |
| `CRUD` | Oracle CRUD packages and SQL Server CRUD procedures |
| `CANONICAL_JSON` | Word/Legacy timestamped canonical JSON and EA `model.json` |
| `COMPARISON_WORKBOOK` | Logical/physical metadata comparison XLSX |
| `MERMAID_DIAGRAM` | Normal, conceptual, dependency and batch Mermaid diagrams |
| `GRAPHVIZ_DIAGRAM` | Normal, conceptual, dependency, clustered, compact and overview DOT diagrams |
| `MANIFEST` | Standard Manifest V1 self-entry for Word/Legacy/ZIP/EA |
| `RUN_SCRIPT` | EA platform run-all SQL |
| `SUMMARY_REPORT` | CRUD summary, batch generation summary, diagram summary |
| `ERROR_REPORT` | Batch generation error log |
| `ISSUE_REPORT` | Batch diagram issue CSV |

Conceptual ERD is not a separate top-level artifact type. Its semantic role remains distinguishable through logical identity/provenance while its concrete representation is Mermaid or Graphviz.

The HTTP ZIP archive is **not** an `ArtifactType` in V1. It is currently treated as a transport/package container. Packaging is a C5/C6 concern.

## 5. Platform semantics

`ArtifactDescriptor.platform` uses the existing `DatabasePlatform` enum.

Examples of platform-specific artifacts:

```text
DDL
MIGRATION
CRUD
COMPARISON_WORKBOOK
RUN_SCRIPT
```

Examples of platform-neutral artifacts:

```text
CANONICAL_JSON
MERMAID_DIAGRAM
GRAPHVIZ_DIAGRAM
MANIFEST
SUMMARY_REPORT
ERROR_REPORT
ISSUE_REPORT
```

`platform == null` is the explicit V1 representation of a DBMS-neutral artifact. `platformOptional()` is provided for callers that prefer `Optional` access.

Contract V1 deliberately does not add artificial `NONE` or `MULTI` database-platform enum values to the existing DBMS model.

## 6. Logical identity

`logicalName` is mandatory and represents the semantic subject of the artifact, independently of its current filename.

Examples:

```text
BIM.PROVINCES
FEE.FEE_VERSION
schema
batch
manifest
```

C4.2 does not yet impose a new global naming grammar. The naming policy remains a C5 responsibility.

## 7. Relative path

For a generated artifact, `relativePath` is mandatory.

The contract defines it as a **portable package-relative path**:

- must not be absolute;
- must not contain Windows drive roots;
- must use `/` rather than `\` as the metadata separator;
- must not contain empty, `.` or `..` path segments.

Examples:

```text
oracle/BIM.PROVINCES_20260822.oracle.sql
oracle/migrations/V20260822070138552__BIM_PROVINCES_ALTER.sql
comparison/mysql/BIM.PROVINCES.mysql.xlsx
json/model.json
```

This rule changes metadata representation only. It does not change the host filesystem or current ZIP layout in C4.2.

`SKIPPED` and `FAILED` outcomes may omit `relativePath`, because no file may exist.

## 8. Media type

For `GENERATED` artifacts, `mediaType` is mandatory and must contain a MIME type/subtype form.

Expected mappings when pipeline wiring is introduced include:

| Artifact content | Expected media type |
|---|---|
| SQL | `application/sql` |
| JSON | `application/json` |
| XLSX | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| Mermaid | `text/plain` |
| Graphviz DOT | `text/vnd.graphviz` or the existing transport-compatible text type chosen during wiring |
| CSV | `text/csv` |
| TXT / LOG | `text/plain` |

C4.2 does not modify existing HTTP `Content-Type` values.

## 9. Generation ID

`generationId` is mandatory for every descriptor.

Its purpose is correlation: artifacts produced by one logical generation operation can later be grouped without parsing timestamps out of filenames.

C4.2 defines only the field and its non-blank invariant. It does **not** yet replace the current timestamp generators or controller archive timestamps. Unifying generation identity is part of later C4/C5 wiring.

## 10. Status

Contract V1 has three source-derived statuses:

```text
GENERATED
SKIPPED
FAILED
```

`GENERATED` requires both a `relativePath` and `mediaType`.

`SKIPPED` and `FAILED` may omit those two fields. This allows current outcomes such as metadata CRUD skip/failure rows to be represented without inventing a fake file.

## 11. Provenance

`ArtifactProvenance` contains:

```text
origin
sourceName
producer
```

Current `ArtifactOrigin` values are:

```text
STANDARD_WORD
LEGACY_WORD
ZIP_BATCH
ENTERPRISE_ARCHITECT
CANONICAL_JSON
DATABASE_METADATA
INTERNAL
```

`sourceName` may be empty where there is no stable external input name. `producer` is mandatory and identifies the subsystem that created or attempted the artifact.

Origin and producer are separate on purpose. A comparison workbook may originate from a Word request but be produced by the comparison writer using live metadata.

## 12. Invariants enforced by the model

`ArtifactDescriptor` enforces:

1. non-null `ArtifactType`;
2. non-null `ArtifactStatus`;
3. non-null `ArtifactProvenance`;
4. non-blank `logicalName`;
5. non-blank `generationId`;
6. portable relative path when a path is present;
7. type/subtype-shaped media type when present;
8. mandatory path and media type for `GENERATED` outcomes.

`ArtifactProvenance` enforces:

1. non-null origin;
2. non-blank producer;
3. normalized optional source name.

## 13. Explicit C4.2 non-goals

C4.2 does not change:

```text
SchemaForgeApiService
SchemaForgeController
OutputFileNamer
FlywayMigrationNamer
ZIP layout
filenames
REST endpoints
HTTP responses
Word parser
Legacy Word parser
EA parser
canonical-domain semantics
DDL generators
physical DDL
migration diff/safety semantics
CRUD SQL generators
metadata repositories
```

At the C4.2 checkpoint the new package was not yet wired into the production generation pipeline. That separation intentionally kept C4.2 low risk. C4.3 subsequently completed production-path tracking, and C5 later standardized the tracked relative paths without changing Artifact Contract V1 field semantics.

## 14. Test coverage

`ArtifactContractTest` covers:

- V1 contract version;
- platform-specific artifact metadata;
- DBMS-neutral artifact metadata;
- generated-file invariants;
- skipped/failed outcomes without fake file identities;
- portable relative-path validation;
- provenance validation;
- complete mapping of current C4.1 artifact families to top-level V1 types.

Targeted Maven command:

```bat
mvnw.cmd -Dtest=ArtifactContractTest test
```

The targeted contract test and the normal project regression were executed as follows:

```bat
mvnw.cmd clean test
```

Baseline before C4.2 was `467` tests, `0` failures, `0` errors, `4` configuration-based skips. C4.2 adds eight contract tests. User verification completed with targeted `ArtifactContractTest`: `8` tests, `0` failures, `0` errors, `0` skips, `BUILD SUCCESS` at `2026-08-22T08:42:15-07:00`; and full `mvnw.cmd clean test`: `475` tests, `0` failures, `0` errors, `4` skips, `BUILD SUCCESS` at `2026-08-22T21:39:20-07:00`.

## 15. C4.2 exit status

Core-model implementation is complete when:

- all C4.1 artifact families are representable;
- V1 contract metadata types exist independently of packaging;
- source compiles;
- targeted contract tests pass;
- no existing generation path has been rewired or behaviorally changed;
- this contract document and the execution roadmap are updated.

C4.2 exit criteria are satisfied. The user-side targeted and full Maven regressions are the authoritative stage-closure evidence. C4.3 may now wire existing production paths to this contract while preserving current filenames, layouts, REST responses, and generation semantics.

## 16. C4.3 production-pipeline mapping - official

**C4.3 official source fingerprint:** `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`  
**C4.3 source inventory:** `251` main Java files / `170` test Java files  
**Status:** `DONE / USER-VERIFIED`  

C4.3 wires the already-generated REST artifacts to Artifact Contract V1 without changing current
filenames, directory layouts, SQL semantics, ZIP entries, endpoint signatures, or HTTP response bodies.

New request-local support types:

```text
ArtifactGenerationContext
ArtifactLedger
ArtifactPaths
```

### Request correlation

One top-level generation request owns one `generationId`. Child document contexts in ZIP batch
processing preserve that generation ID. They may use isolated temporary ledgers while staging files,
then the descriptors are remapped to the final ZIP-relative paths before being merged into the batch
ledger.

This prevents the Artifact Contract from exposing temporary staging paths.

### Production paths wired in C4.3

```text
POST /api/v1/generate/word
POST /api/v1/generate/legacy-word
POST /api/v1/generate/zip
POST /api/v1/generate/ea-xml
POST /api/v1/generate/oracle/crud
POST /api/v1/generate/sqlserver/crud
POST /api/v1/diagram/mermaid/canonical-json
```

Tracked artifact families include:

```text
DDL
MIGRATION
CRUD
CANONICAL_JSON
COMPARISON_WORKBOOK
MERMAID_DIAGRAM
GRAPHVIZ_DIAGRAM
MANIFEST
RUN_SCRIPT
SUMMARY_REPORT
ERROR_REPORT
ISSUE_REPORT
```

### Transport compatibility

The existing public REST methods still return the same `byte[]`/attachment content. Internal tracked
archive results are used only for contract verification and future C6 manifest production. Standalone
CRUD and Mermaid generation results carry an internal descriptor, but their controllers continue to
emit the same SQL/text response bodies and attachment filenames.

### Batch-path rule

ZIP batch generation stages each Word document in a temporary directory. C4.3 does not expose those
staging paths. Every generated descriptor is mapped through the same existing `packagedBatchTarget`
logic used to move the file, so the descriptor `relativePath` matches the actual ZIP entry.

### C4.3 verification strategy

`SchemaForgeArtifactTrackingTest` compares the set of generated descriptor paths with the set of real
ZIP entries for Word, ZIP Batch, and EA. Equality is required in both directions: no generated file may
be missing from the ledger and no generated descriptor may point to a non-existent archive entry.

Additional assertions verify the standalone Oracle CRUD, SQL Server CRUD, and canonical-JSON Mermaid
descriptors.

C4.3 verification is complete. User-side targeted regression passed with `23` tests, `0` failures, `0` errors, and `0` skips at `2026-08-22T22:50:14-07:00`. The exact C4.3 source then passed full `mvnw.cmd clean test` with `482` tests, `0` failures, `0` errors, and `4` environment-gated skips at `2026-08-22T22:53:13-07:00`. Artifact Contract V1 is therefore complete and frozen at source fingerprint `2d75fbbc67e0d1006282d3485bbb25055da120265dd05655324f6c79e8129423`. The next controlled stage is C5 naming/layout consolidation.
