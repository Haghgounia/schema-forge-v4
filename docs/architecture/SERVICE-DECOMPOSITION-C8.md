# SchemaForge V4 - C8 API/Application Service Decomposition

Status: **C8 DONE / USER-VERIFIED / C8.10 FROZEN**
Input baseline: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C7.2`

## Objective

Reduce the orchestration and production responsibilities concentrated in `SchemaForgeApiService`
without changing any public endpoint, successful payload, C7 error behavior, C5 artifact path,
C6 manifest contract, parser behavior, or generated SQL semantics.

C8 is intentionally incremental. Each extraction is independently regression-gated before the next
responsibility is moved.

## Frozen behavior invariants

Every C8 extraction must preserve:

- the seven current REST endpoint URLs and methods;
- successful response bodies, media types, filenames, and `Content-Disposition` behavior;
- `schemaforge-rest-error/v1` and `X-SchemaForge-Request-Id` behavior from C7;
- Artifact Contract V1 descriptor semantics;
- C5 naming/layout and one request generation timestamp;
- C6 Standard Manifest V1 content/integrity rules;
- DDL, migration, CRUD, comparison, diagram, and canonical JSON semantics;
- request-local ledger/provenance/generationId behavior;
- ZIP entry sets and deterministic ordering/collision rules.

Mature Word/Legacy/EA parser internals are not refactor targets merely because they are large.

## C8.1 - DiagramArtifactProducer extraction

### Responsibility moved

The following methods were moved out of `SchemaForgeApiService` into
`com.behsazan.schemaforge.application.DiagramArtifactProducer`:

- per-document Mermaid ER generation;
- per-document Graphviz ER generation;
- conceptual Mermaid/Graphviz generation;
- batch Mermaid ER/conceptual/dependency + issue/summary artifacts;
- batch Graphviz conceptual/dependency/profile + issue/summary artifacts.

### Deliberately unchanged

- same Mermaid/Graphviz exporter implementations;
- same `ArtifactNamingPolicy` path methods;
- same media types;
- same ledger artifact types/logical names/producer strings;
- same batch issue CSV escaping and summary text;
- no controller or REST contract change;
- no SQL/parser/metadata/migration/CRUD change.

### Structural result

Before C8.1:

```text
SchemaForgeApiService.java : 1424 lines
```

After extraction:

```text
SchemaForgeApiService.java : 1214 lines
DiagramArtifactProducer.java : 231 lines
```

The extraction removes diagram production implementation from the API facade while preserving
orchestration calls at the same points in Word/ZIP/EA generation.

### Regression coverage

New focused coverage:

- `DiagramArtifactProducerTest.shouldProducePerDocumentDiagramsWithCanonicalPathsAndLedgerEntries`
- `DiagramArtifactProducerTest.shouldProduceBatchDiagramReportsWithoutChangingBatchContract`

Existing archive/tracking/manifest/diagram tests remain required because they prove the extraction
did not change package-visible behavior.

C8.1 expected full Surefire count: `527` (C7.2 official `525` + 2 new tests).

## Candidate source identity

```text
Main Java files : 267
Test Java files : 180
Source fingerprint: 128900965948b2686b4d1fa7d5b8b78278756b3be8e4926d48db320f271cba8e
```

The new producer core was compiled independently with Java 21. User-side Maven verification then passed:

```text
Targeted C8.1 : 55 / 0 / 0 / 0
Full C8.1     : 527 / 0 / 0 / 4
BUILD SUCCESS
Finished full : 2026-08-23T01:58:11-07:00
```

C8.1 was frozen as the official source checkpoint before the verified C8.2 promotion.

## C8.2 - MigrationArtifactProducer extraction

### Responsibility moved

C8.2 moves only migration artifact orchestration from `SchemaForgeApiService` into
`com.behsazan.schemaforge.application.MigrationArtifactProducer`:

- repository-availability skip registration;
- desired/live table resolution for migration generation;
- no-diff skip registration;
- Flyway migration file writing;
- migration Artifact Ledger registration.

### Deliberately unchanged

- `SchemaDiffEngine`;
- `MigrationSqlRenderer`;
- `MigrationGenerationService`;
- `FlywayMigrationNamer` and filename grammar;
- `MigrationRenderOptions.safeDefaults()`;
- metadata repository behavior;
- migration SQL semantics;
- `MigrationGenerationService` provenance/producer string;
- C5 migration directory layout.

### Structural result

```text
Before C8.2:
SchemaForgeApiService.java       : 1214 lines

After C8.2:
SchemaForgeApiService.java       : 1149 lines
MigrationArtifactProducer.java   : 122 lines
```

Source diff vs frozen C8.1 is limited to:

```text
MODIFIED src/main/java/com/behsazan/schemaforge/api/SchemaForgeApiService.java
NEW      src/main/java/com/behsazan/schemaforge/application/MigrationArtifactProducer.java
NEW      src/test/java/com/behsazan/schemaforge/application/MigrationArtifactProducerTest.java
```

### Regression coverage

New focused tests:

- repository unavailable -> migration descriptors are `SKIPPED`;
- live table matches desired -> no file and `SKIPPED`;
- live table differs -> deterministic Flyway file, canonical path, SQL, and `GENERATED` ledger entry.

Expected full Surefire count: `530` (C8.1 official `527` + 3 new tests).

## C8.2-R1 regression repair

The first user targeted regression compiled the complete C8.2 candidate and executed `45` tests. It reported
`3` errors, all in `MigrationArtifactProducerTest`, before the producer method could be exercised. The helper
created `ArtifactGenerationContext` with `20260823020000000`, while the frozen C5 contract requires
`yyyyMMdd_HHmmss_SSS`. R1 changes that fixture value to `20260823_020000_000`.

This is a test-only repair. `SchemaForgeApiService`, `MigrationArtifactProducer`, migration diff/rendering,
Flyway naming, metadata lookup, ledger behavior, REST behavior, and artifact paths are unchanged.

Expected rerun: targeted `45 / 0 / 0 / 0`; full `530 / 0 / 0 / 4`.

## C8.2-R1 candidate source identity

```text
Main Java files : 268
Test Java files : 181
Source fingerprint: aa77b6bfe9248ebe7b061b2cd39a75ece5e34e765fd121a0ea62d1701a6f1e14
```

User-side regression passed:

```text
Targeted C8.2-R1 : 45 / 0 / 0 / 0
Full C8.2        : 530 / 0 / 0 / 4
BUILD SUCCESS
Finished full    : 2026-08-23T03:16:46-07:00
```

C8.2 is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.2`.

## C8.3 - ComparisonArtifactProducer extraction

### Responsibility moved

C8.3 moves only comparison-workbook artifact orchestration from `SchemaForgeApiService` into
`com.behsazan.schemaforge.application.ComparisonArtifactProducer`:

- repository-unavailable skip registration;
- live table lookup with the same case-insensitive schema fallback;
- column-usage frequency map construction;
- `SchemaCompareExcelWriter` invocation;
- comparison workbook path/file writing;
- comparison Artifact Ledger registration;
- EA per-table comparison path return/logging.

### Deliberately unchanged

- `SchemaCompareExcelWriter` rendering and all logical/physical compare rules;
- `MetadataComparisonValidator`;
- repository implementations and DBMS metadata behavior;
- MySQL physical comparison deferral;
- C5 `comparison/<platform>/...compare.xlsx` naming/layout;
- EA PostgreSQL lowercase comparison filename convention;
- workbook media type and ledger producer `SchemaCompareExcelWriter`;
- SQL, migration, CRUD, parser, manifest, and REST behavior.

### Structural result

```text
Before C8.3:
SchemaForgeApiService.java       : 1149 lines

After C8.3:
SchemaForgeApiService.java       : 1024 lines
ComparisonArtifactProducer.java  : 194 lines
```

### Regression coverage

New focused tests cover:

- unavailable repository -> comparison descriptor `SKIPPED`, no workbook;
- document flow -> canonical workbook path and `GENERATED` ledger entry;
- EA PostgreSQL -> lowercase comparison path plus case-insensitive schema fallback.

Expected full Surefire count: `533` (C8.2 official `530` + 3 new tests).

C8.3 passed targeted and full regression and is frozen; C8.4 may proceed as the next isolated extraction.

## C8.3 official verification

```text
Targeted C8.3 : 52 / 0 / 0 / 0
Full C8.3     : 533 / 0 / 0 / 4
BUILD SUCCESS
Finished full : 2026-08-23T03:35:39-07:00
```

C8.3 source identity:

```text
Main Java files : 269
Test Java files : 182
Source fingerprint: 90b8fcb7c8a2998b0aa878e01b59bb1d77f6916560514ce4e65dc2afdc927cab
```

C8.3 is frozen as the official checkpoint.

## C8.4 - CrudArtifactProducer extraction

### Responsibility moved

C8.4 moves only metadata-based Oracle/SQL Server CRUD artifact orchestration from
`SchemaForgeApiService` into `com.behsazan.schemaforge.application.CrudArtifactProducer`:

- Oracle/SQL Server metadata repository resolution for CRUD artifacts;
- document/live primary-key skip decisions;
- case-insensitive live schema fallback;
- grant-derived CRUD generation options;
- Oracle package / SQL Server procedure generation dispatch;
- CRUD artifact path/file writing;
- per-document metadata CRUD summary CSV;
- CRUD/summary Artifact Ledger registration and failure capture.

### Deliberately unchanged

- `OracleCrudPackageGenerator`;
- `SqlServerCrudProcedureGenerator`;
- Oracle/SQL Server CRUD SQL semantics and naming strategies;
- configured write-grantee selection (`INSERT`/`UPDATE`/`DELETE`);
- `MetadataRepositoryResolver` and repository implementations;
- C5 `crud/<platform>/...` and `reports/...metadata-crud-summary.csv` paths;
- summary status/error strings;
- generated artifact media type and Ledger producer strings;
- C6 manifest, C7 REST error contract, DDL, migration, comparison, parser, and metadata semantics.

### Structural result

```text
Before C8.4:
SchemaForgeApiService.java : 1024 lines

After C8.4:
SchemaForgeApiService.java : 872 lines
CrudArtifactProducer.java  : 257 lines
```

### Regression coverage

New focused tests cover:

- document table without a PK -> Oracle/SQL Server CRUD `SKIPPED` plus unchanged summary;
- live Oracle/SQL Server tables -> canonical CRUD paths, configured write grants, and generated Ledger entries;
- generator failures -> per-platform `FAILED` Ledger entries while summary generation still completes.

Candidate source identity:

```text
Main Java files : 270
Test Java files : 183
Source fingerprint: 8f134a74c2967a3c005b0250280d09cb75854d0c185f9485de947a749b2e8c57
Expected full Surefire count: 536
```

C8.4 passed user-side regression and is frozen as the official checkpoint.

```text
Targeted C8.4 : 44 / 0 / 0 / 0
Full C8.4     : 536 / 0 / 0 / 4
BUILD SUCCESS
Finished full : 2026-08-23T03:52:38-07:00
```

C8.4 is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.4`.
The next isolated extraction is C8.5 `BatchArchiveSupport`.


## C8.5 - BatchArchiveSupport extraction

### Responsibility moved

C8.5 moves only ZIP-batch filesystem and ledger helper mechanics out of `SchemaForgeApiService` into
`com.behsazan.schemaforge.application.BatchArchiveSupport`:

- safe ZIP extraction with zip-slip rejection;
- processable DOCX discovery/filtering and stable case-insensitive ordering;
- per-document generated-file counting;
- collision-safe staged-file movement into the batch output root;
- remapping isolated child Ledger descriptors to final collision-resolved paths;
- batch CSV row escaping, safe exception-message extraction, and detailed error-block formatting.

`generateFromZipTracked(...)` remains in `SchemaForgeApiService`; C8.5 does not move batch orchestration itself.

### Deliberately unchanged

- ZIP entry set and archive generation order;
- C5 collision suffix policy and migration-collision failure behavior;
- child/top-level generationId and provenance semantics;
- C6 manifest assembly and checksum rules;
- batch summary/error filenames and Ledger producer strings;
- Word parsing, diagrams, DDL, migration, comparison, CRUD, EA, metadata, and REST behavior.

### Structural result

```text
Before C8.5:
SchemaForgeApiService.java : 872 lines

After C8.5:
SchemaForgeApiService.java : 756 lines
BatchArchiveSupport.java   : 156 lines
```

### Regression coverage

New focused tests cover:

- zip-slip entry rejection before any outside write;
- exclusion of temporary/hidden/macOS/legacy non-DOCX files with stable document ordering;
- collision remapping plus exact Ledger path/generationId preservation.

Candidate source identity:

```text
Main Java files : 271
Test Java files : 184
Source fingerprint: 49eaad1760cd693a32fb0f9571b404cd395d679a1b45d084cc30ee33eb80ae0f
Expected full Surefire count: 539
```

C8.5 passed user-side regression and is frozen as the official checkpoint.

```text
Targeted C8.5 : 39 / 0 / 0 / 0
Full C8.5     : 539 / 0 / 0 / 4
BUILD SUCCESS
Finished full : 2026-08-23T04:08:54-07:00
```

C8.5 is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.5`.
The next isolated extraction is C8.6 `ArtifactPackageBuilder`.


## C8.6 - ArtifactPackageBuilder extraction

### Responsibility moved

C8.6 moves only common artifact-package mechanics out of `SchemaForgeApiService` into
`com.behsazan.schemaforge.application.ArtifactPackageBuilder`:

- output-directory to in-memory ZIP packaging;
- relative archive-path normalization to forward slashes;
- best-effort recursive temporary-directory cleanup.

No Word, Legacy Word, ZIP-batch, or EA orchestration is moved.

### Deliberately unchanged

- ZIP entry relative paths and file content;
- packaging call points for Word, Legacy Word, ZIP batch, and EA;
- C5 naming/layout and collision behavior;
- C6 manifest content and integrity rules;
- Diagram, Migration, Comparison, CRUD, DDL, parser, metadata, and REST behavior.

### Structural result

```text
Before C8.6:
SchemaForgeApiService.java : 756 lines

After C8.6:
SchemaForgeApiService.java : 733 lines
ArtifactPackageBuilder.java: 49 lines
```

### Regression coverage

New focused tests cover:

- ZIP entry relative-path and content preservation;
- platform-independent forward-slash path normalization;
- recursive cleanup plus harmless repeated cleanup of an already-removed tree.

Candidate source identity:

```text
Main Java files : 272
Test Java files : 185
Source fingerprint: a5f01c4c0fa180632743ae6be7a4d7cd0b49f618c1eb087a09bd0375658e8afb
Expected full Surefire count: 542
```

C8.6 passed user-side regression and is frozen as the official checkpoint.

```text
Targeted C8.6 : 34 / 0 / 0 / 0
Full C8.6     : 542 / 0 / 0 / 4
BUILD SUCCESS
Finished full : 2026-08-23T04:24:52-07:00
```

C8.6 is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.6`.
The next controlled extraction is C8.7 `DocumentGenerationOrchestrator`.

## C8.7 - DocumentGenerationOrchestrator extraction

### Responsibility moved

C8.7 moves the shared Standard Word/Legacy Word generation orchestration from `SchemaForgeApiService` into `com.behsazan.schemaforge.application.DocumentGenerationOrchestrator`:

- Standard Word parse -> canonical preparation -> all DBMS artifact generation;
- Legacy Word parse with caller-supplied schema -> canonical preparation -> the same all-DBMS artifact generation;
- per-platform metadata validation aggregation for canonical JSON;
- CREATE DDL writing and DDL Ledger registration;
- dispatch to the already-frozen Migration, Comparison, CRUD and Diagram producers;
- canonical JSON writing and Ledger registration.

### Deliberately unchanged

- `WordSpecificationParser` and mature Legacy Word recovery/parser internals;
- `SchemaPreparationService` normalization/enrichment/validation behavior;
- `DdlGenerator`, dialects and all SQL semantics;
- `MetadataRepositoryResolver` and metadata validation behavior;
- `MigrationArtifactProducer`, `ComparisonArtifactProducer`, `CrudArtifactProducer`, and `DiagramArtifactProducer`;
- the shared request timestamp and Ledger generationId/provenance;
- C5 paths, C6 manifest, C7 REST contract, ZIP packaging, and EA orchestration;
- Oracle DDL sanity semantics; the same checker instance is passed to the new orchestrator.

### Structural result

```text
Before C8.7:
SchemaForgeApiService.java          : 733 lines

After C8.7:
SchemaForgeApiService.java          : 648 lines
DocumentGenerationOrchestrator.java : 198 lines
```

### Regression coverage

New focused tests cover:

- Standard Word -> all five CREATE DDL artifacts plus canonical JSON and Ledger entries;
- unavailable metadata -> Migration and Comparison `SKIPPED` descriptors for every DBMS while CREATE output remains additive;
- Legacy Word -> caller-supplied schema is preserved in generated Oracle DDL.

Candidate source identity:

```text
Main Java files : 273
Test Java files : 186
Source fingerprint: a8e0f2d94895ab2142ac8bc88aefa7f5b8d4029df2bf3ea297b883f076add57d
Expected full Surefire count: 545
```

C8.7 passed user-side regression and is frozen as the official checkpoint.

```text
Targeted C8.7 : 35 / 0 / 0 / 0
Full C8.7     : 545 / 0 / 0 / 4
BUILD SUCCESS
Finished full : 2026-08-23T04:54:29-07:00
```

C8.7 is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.7`.
C8.8 `BatchGenerationOrchestrator` is user-verified and frozen; the C8.8-R1 test-only repair and its verification are recorded below.

## C8.8 - BatchGenerationOrchestrator extraction

### Responsibility moved

C8.8 moves the ZIP-batch workflow from `SchemaForgeApiService` into `com.behsazan.schemaforge.application.BatchGenerationOrchestrator`:

- safe unzip and processable DOCX discovery using the frozen `BatchArchiveSupport`;
- isolated child generation contexts for each document;
- Standard Word generation through the frozen `DocumentGenerationOrchestrator`;
- collision-safe artifact move and Ledger merge;
- aggregate Mermaid/Graphviz batch diagrams;
- batch summary CSV and error log;
- Standard Manifest V1 writing;
- final directory-to-ZIP packaging through the frozen `ArtifactPackageBuilder`.

### Deliberately unchanged

- `/api/v1/generate/zip` and all controller/REST behavior;
- extension validation, safe upload filename handling and root request context creation in `SchemaForgeApiService`;
- document parsing/preparation and all five DBMS generation semantics;
- collision allocator behavior and remapped paths;
- summary/error file names, text format and Ledger producer string `SchemaForgeApiService`;
- batch diagram contents/paths;
- C5 naming/layout, C6 manifest contract and C7 error contract;
- request generationId/timestamp and all generated descriptor identities.

The legacy SLF4J logger category `com.behsazan.schemaforge.api.SchemaForgeApiService` is intentionally retained inside the extracted orchestrator so the refactor does not change existing batch warning log categorization.

### Structural result

```text
Before C8.8:
SchemaForgeApiService.java          : 648 lines

After C8.8:
SchemaForgeApiService.java          : 565 lines
BatchGenerationOrchestrator.java    : 152 lines
```

### Regression coverage

New focused tests cover:

- successful + failing document fault isolation, diagnostics, one manifest and shared Ledger generationId;
- duplicate document collision remap while preserving the shared request timestamp;
- rejection of archives with no processable DOCX entries.

Candidate source identity:

```text
Main Java files : 274
Test Java files : 187
Source fingerprint: b8fb0a328ef0ad382e963227a050e14f8f128cadc2a2ff38afd55eb05379b396
Expected full Surefire count: 548
```

The first user-side C8.8 targeted run compiled `274` main and `187` test Java files, then finished `39 / 1 / 0 / 0`. The single failure was in the new test-only provenance assertion: it filtered all `SUMMARY_REPORT`/`ERROR_REPORT` records and therefore included valid Mermaid/Graphviz/CRUD summaries whose producers are intentionally not `SchemaForgeApiService`.

### C8.8-R1 repair

R1 changes only `BatchGenerationOrchestratorTest.java`. The assertion now selects only batch diagnostic descriptors whose `logicalName` is `batch-generation`, asserts that exactly two such records exist (summary + error), and then checks their preserved legacy producer `SchemaForgeApiService`. `src/main` is byte-for-byte unchanged from C8.8.

```text
First targeted result : 39 / 1 / 0 / 0
R1 expected targeted  : 39 / 0 / 0 / 0
R1 expected full      : 548 / 0 / 0 / 4
R1 source fingerprint : b8fb0a328ef0ad382e963227a050e14f8f128cadc2a2ff38afd55eb05379b396
```

C8.8-R1 passed the repaired targeted and full Maven gates and is frozen as the official C8.8 checkpoint.

```text
Targeted C8.8-R1 : 39 / 0 / 0 / 0   at 2026-08-23T05:21:18-07:00
Full C8.8         : 548 / 0 / 0 / 4  at 2026-08-23T05:24:08-07:00
Main Java         : 274
Test Java         : 187
Fingerprint       : b8fb0a328ef0ad382e963227a050e14f8f128cadc2a2ff38afd55eb05379b396
```

C8.8 is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.8`. The next extraction is selected only after inventory of the remaining facade responsibilities.

## C8.9 - EaGenerationOrchestrator extraction

Status: **DONE / USER-VERIFIED / FROZEN**

C8.9 moves the remaining Enterprise Architect preparation and multi-table artifact workflow from `SchemaForgeApiService` into `com.behsazan.schemaforge.application.EaGenerationOrchestrator`. Public API methods, XML/XMI extension validation, source-name normalization, context creation and `GenerationArchive` response remain in the facade.

Moved responsibilities:

- `EnterpriseArchitectXmlParser` invocation and schema override/default-schema resolution;
- `SchemaPreparationService` for the parsed EA schema;
- per-table DDL for Oracle, PostgreSQL, Db2 for z/OS, SQL Server and MySQL;
- table-scoped validation and metadata-comparison filtering;
- per-table comparison workbooks and schema-level migrations;
- metadata CRUD, Mermaid/Graphviz/conceptual diagrams and canonical JSON;
- dependency ordering/cycle capture and per-DBMS run-all scripts;
- EA Standard Manifest extension.

Preserved invariants:

- PostgreSQL EA artifact basenames remain lowercase;
- CREATE DDL remains unconditional and migration remains additive;
- Oracle DDL sanity checks use the same checker instance;
- run-all producer identity remains `SchemaForgeApiService`;
- context timestamp is still created after EA parse/preparation;
- C5 paths, C6 manifest contract, C7 REST contract and all prior C8 producer contracts are unchanged.

Structural result:

```text
SchemaForgeApiService before C8.9 : 565 lines
SchemaForgeApiService after C8.9  : 244 lines
EaGenerationOrchestrator          : 418 lines
Main Java files                   : 275
Test Java files                   : 188
New focused tests                 : 3
Expected full Surefire            : 551
Source fingerprint                : 9ef7e1315e82b86817864d94431b20ce02a367ca1777c622dd5a5f7102db6898
```

Focused tests lock dependency-ordered run-all/EA manifest behavior, API schema override plus PostgreSQL lowercase naming, and the five preserved run-script Ledger producer identities.

C8.9 passed user-side regression and is frozen as the official checkpoint:

```text
Targeted C8.9 : 42 / 0 / 0 / 0  at 2026-08-23T05:39:34-07:00
Full C8.9     : 551 / 0 / 0 / 4 at 2026-08-23T05:43:05-07:00
Main Java     : 275
Test Java     : 188
Fingerprint   : 9ef7e1315e82b86817864d94431b20ce02a367ca1777c622dd5a5f7102db6898
```

C8.9 is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.9`. The remaining named decomposition boundary from the roadmap inventory is C8.10 `ArtifactGenerationService`.

## C8.10 - ArtifactGenerationService extraction

Status: **DONE / USER-VERIFIED / FROZEN VIA C8.10-R1**

C8.10 moves the remaining shared single-document packaging workflow from `SchemaForgeApiService` into `com.behsazan.schemaforge.application.ArtifactGenerationService`. Public endpoint methods, file/schema validation, source-name normalization, generation-context creation and `GenerationArchive` response construction remain in the facade.

Moved responsibilities:

- create the Standard/Legacy temporary workspace;
- transfer the uploaded Word binary to its normalized input path;
- create the output directory;
- invoke the existing `DocumentGenerationOrchestrator`;
- write Standard Artifact Manifest V1 with the same source/model identity;
- package the output directory through the existing `ArtifactPackageBuilder`;
- cleanup the temporary workspace in `finally`, including generation-failure paths.

Preserved invariants:

- `schemaforge-word-` and `schemaforge-legacy-word-` workspace prefixes remain unchanged;
- Standard Word and Legacy Word parser/preparation behavior remains in `DocumentGenerationOrchestrator`;
- generation context is still created in `SchemaForgeApiService`;
- manifest logical name remains the source basename without extension;
- artifact Ledger snapshot is still returned by the facade after packaging;
- C5 naming/layout, C6 manifest contract, C7 REST contract and all prior C8 producer/orchestrator behavior are unchanged.

Structural result:

```text
SchemaForgeApiService before C8.10 : 244 lines
SchemaForgeApiService after C8.10  : 210 lines
ArtifactGenerationService          : 110 lines
Main Java files                    : 276
Test Java files                    : 189
New focused tests                  : 3
Targeted tests                     : 43 / 0 / 0 / 0
Full Surefire                      : 554 / 0 / 0 / 4
Source fingerprint                 : dfe575066ace7ac8de555e9f1a561c00f1a0b217d54b6c8147680d1aa552db40
```

Focused tests lock Standard Word manifest/package cleanup, Legacy schema delegation and `finally` cleanup when document generation fails. After the one-import C8.10-R1 repair, both targeted and full regression are green. C8.10 is the final named decomposition boundary and C8 is DONE.



## C8.10-R1 - PreparedSchema import compile repair

Status: **DONE / USER-VERIFIED / FROZEN AS C8.10**

The first user-side C8.10 targeted command compiled `276` source files until `SchemaForgeApiService.java` failed at line 159 with `cannot find symbol: class PreparedSchema`; Surefire did not start. The EA facade path still uses `PreparedSchema` returned by `EaGenerationOrchestrator.prepare(...)`, but C8.10 accidentally removed the import while extracting the Standard/Legacy packaging workflow.

R1 changes exactly one production line:

```java
import com.behsazan.schemaforge.application.PreparedSchema;
```

No method body, test source, parser, DBMS dialect, DDL, migration, comparison, CRUD, diagram, C5 naming, C6 manifest, C7 REST, Ledger, or ZIP behavior changes.

```text
Main Java files                    : 276
Test Java files                    : 189
Expected targeted tests            : 43
Expected full Surefire             : 554
SchemaForgeApiService after R1     : 211 lines
R1 source fingerprint              : 03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
First C8.10 targeted result        : COMPILE FAILURE before tests
Repair targeted regression         : 43 / 0 / 0 / 0 at 2026-08-23T06:15:32-07:00
Repair full regression             : 554 / 0 / 0 / 4 at 2026-08-23T06:22:23-07:00
```

C8.10-R1 passed both user-side gates and is frozen as `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.10`. C8 API/Application Service Decomposition is complete. C9 Test Matrix / Live-Validation Classification and C10 Documentation Consolidation are also complete with source unchanged; C11 Final Consolidation Regression / Baseline Freeze is next.
