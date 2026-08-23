# SchemaForge V4 - C6.1 Standard Artifact Manifest V1 Design

Status: **C6.1 DESIGN COMPLETE / DOCUMENTATION ONLY**
Production implementation: **NOT STARTED - waits for C5.3-R2 regression gate**
Current official baseline: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C5.3`

## 1. Objective

C6 introduces one authoritative `manifest.json` contract for every ZIP-producing generation path.
The manifest must let a consumer determine what SchemaForge generated, skipped, or failed without
inferring semantics from filenames or endpoint-specific archive layouts.

C6 consumes, and does not redefine:

- Artifact Contract V1 from C4;
- canonical artifact paths from C5;
- request-level `generationId` and `generationTimestamp` from the artifact generation context.

## 2. Manifest scope

Manifest V1 is required for these package-producing paths:

```text
POST /api/v1/generate/word
POST /api/v1/generate/legacy-word
POST /api/v1/generate/zip
POST /api/v1/generate/ea-xml
```

Standalone SQL/text endpoints remain standalone in C6 and do not become ZIP responses merely to
carry a manifest:

```text
POST /api/v1/generate/oracle/crud
POST /api/v1/generate/sqlserver/crud
POST /api/v1/diagram/mermaid/canonical-json
```

Their HTTP contract remains a C7 concern.

## 3. Fixed root identity

C5 already reserves the package-root path:

```text
manifest.json
```

C6 does not introduce any alternative path or legacy duplicate manifest.

## 4. Contract identifiers

Manifest V1 uses two independent version identifiers:

```json
{
  "manifestContract": "schemaforge-manifest/v1",
  "artifactContractVersion": "1"
}
```

`manifestContract` versions the JSON package contract. `artifactContractVersion` identifies the C4
metadata contract represented by each artifact entry. A future incompatible manifest-shape change
increments the manifest contract without automatically changing Artifact Contract V1.

## 5. V1 JSON shape

The normative V1 shape is:

```json
{
  "manifestContract": "schemaforge-manifest/v1",
  "artifactContractVersion": "1",
  "generation": {
    "id": "7e25b6d2-...",
    "timestampToken": "20260823_071500_123",
    "generatedAt": "2026-08-23T07:15:00.123-07:00"
  },
  "source": {
    "origin": "ENTERPRISE_ARCHITECT",
    "name": "Party_14050514.xml"
  },
  "models": [
    {
      "sourceName": "Party_14050514.xml",
      "schema": "DPS",
      "tables": [
        "DPS.PARTY",
        "DPS.PARTY_CLASSIFICATION"
      ]
    }
  ],
  "validation": {
    "available": true,
    "errorCount": 0,
    "warningCount": 128,
    "recoveryWarningCount": 128
  },
  "artifactOutcomes": {
    "generated": 120,
    "skipped": 4,
    "failed": 0
  },
  "artifacts": [
    {
      "type": "DDL",
      "platform": "MYSQL",
      "logicalName": "DPS.PARTY_CLASSIFICATION",
      "path": "ddl/mysql/DPS.PARTY_CLASSIFICATION_20260823_071500_123.mysql.sql",
      "mediaType": "application/sql",
      "status": "GENERATED",
      "provenance": {
        "origin": "ENTERPRISE_ARCHITECT",
        "sourceName": "Party_14050514.xml",
        "producer": "DdlGenerator"
      },
      "integrity": {
        "algorithm": "SHA-256",
        "sha256": "<64-lowercase-hex>",
        "sizeBytes": 12345
      }
    },
    {
      "type": "CRUD",
      "platform": "ORACLE",
      "logicalName": "DPS.TABLE_WITHOUT_PK",
      "path": null,
      "mediaType": null,
      "status": "SKIPPED",
      "provenance": {
        "origin": "ENTERPRISE_ARCHITECT",
        "sourceName": "Party_14050514.xml",
        "producer": "OracleCrudGenerationService"
      },
      "integrity": null
    },
    {
      "type": "MANIFEST",
      "platform": null,
      "logicalName": "Party_14050514",
      "path": "manifest.json",
      "mediaType": "application/json",
      "status": "GENERATED",
      "provenance": {
        "origin": "ENTERPRISE_ARCHITECT",
        "sourceName": "Party_14050514.xml",
        "producer": "ArtifactManifestWriter"
      },
      "integrity": null
    }
  ],
  "extensions": {
    "enterpriseArchitect": {
      "dependencyOrder": ["DPS.PARTY", "DPS.PARTY_CLASSIFICATION"],
      "cyclicTables": []
    }
  }
}
```

## 6. Field rules

### 6.1 generation

- `id` is exactly the top-level request `generationId` already introduced by C4.3.
- `timestampToken` is exactly the C5 request timestamp used in artifact filenames.
- `generatedAt` is an ISO-8601 offset timestamp captured once for the top-level request.
- child ZIP-document contexts inherit the same generation identity/time.

C6 implementation may extend `ArtifactGenerationContext` with `generatedAt`; it must not derive the
UTC offset by parsing the filename token.

### 6.2 source

The top-level source reflects the HTTP/package request:

- Word -> `STANDARD_WORD`;
- Legacy Word -> `LEGACY_WORD`;
- ZIP -> `ZIP_BATCH`;
- EA -> `ENTERPRISE_ARCHITECT`.

Each artifact still carries its own C4 provenance. This is important for ZIP Batch, where individual
artifacts may identify the child document that produced them.

### 6.3 models

`models` is the canonical schema/table identity inventory and is independent of artifact filenames.

Each model entry contains:

- `sourceName`;
- canonical `schema`;
- fully qualified `tables`, sorted deterministically.

Word/Legacy normally contribute one model. EA contributes one model with many tables. ZIP Batch may
contribute multiple models, one per successfully parsed child document.

### 6.4 validation

Manifest V1 records counts, not duplicated issue bodies:

- `available`;
- `errorCount`;
- `warningCount`;
- `recoveryWarningCount`.

Detailed validation/recovery content remains in canonical JSON, issue/error/summary artifacts, and
inline SQL warnings where those artifacts already exist.

### 6.5 artifact entries

Every entry is a serialization of an Artifact Contract V1 outcome plus integrity metadata.

Required fields always present:

```text
type
platform          # nullable for DBMS-neutral
logicalName
path              # nullable for SKIPPED/FAILED
mediaType         # nullable for SKIPPED/FAILED
status
provenance
integrity         # nullable by rule below
```

The manifest does not invent a free-form error/reason field in V1 because `ArtifactDescriptor` does
not currently own such a field. Detailed failure diagnostics remain report/error artifacts until a
future Artifact Contract revision explicitly adds outcome details.

## 7. Integrity/checksum policy

For every non-manifest `GENERATED` artifact:

```text
algorithm = SHA-256
sha256    = lowercase 64-hex digest of the exact packaged bytes
sizeBytes = exact packaged byte length
```

Rules:

1. checksum is computed after final C5 collision/path allocation;
2. checksum is computed from the exact bytes that enter the archive;
3. a generated descriptor whose file is missing at manifest time is a manifest-generation error;
4. generated relative paths must be unique;
5. `SKIPPED` and `FAILED` entries have `integrity = null`;
6. the `MANIFEST` self-entry has `integrity = null` to avoid a recursive self-checksum contract;
7. package-level/signature checksums are outside V1.

## 8. Manifest self-entry

`manifest.json` is itself an Artifact Contract `MANIFEST` outcome and therefore appears in
`artifacts`.

The implementation sequence must be:

1. finish all other package artifact outcomes;
2. reserve/add one `MANIFEST / GENERATED / manifest.json` descriptor to the ledger;
3. validate the ledger snapshot;
4. compute integrity for every other generated artifact;
5. serialize `manifest.json` once;
6. do not append a second manifest descriptor after writing.

The self-entry has no checksum because its bytes contain the entry itself.

## 9. Deterministic ordering

Manifest arrays must be deterministic for diffability.

- `models` sort by `sourceName`, then `schema`;
- model `tables` sort by normalized qualified name;
- `artifacts` sort by `type`, platform (null first), normalized `logicalName`, `status`, then `path`;
- EA dependency order is not sorted because its ordering is semantic.

JSON object property order is fixed by the manifest writer DTO/record definitions, not by arbitrary
`Map` iteration.

## 10. Archive/ledger invariants

For a successful ZIP-producing request:

1. every `ArtifactDescriptor.generationId` equals `generation.id`;
2. every generated descriptor path is a portable C5 package-relative path;
3. generated descriptor paths are unique;
4. every regular archive entry has exactly one `GENERATED` descriptor;
5. every `GENERATED` descriptor points to exactly one regular archive entry;
6. `manifest.json` has exactly one MANIFEST self-entry;
7. every non-manifest generated entry has valid SHA-256 and `sizeBytes` matching packaged bytes;
8. skipped/failed outcomes never claim a fake file path.

These invariants extend the C4.3 path-equality test rather than replacing it.

## 11. EA legacy-manifest migration

The current EA-only map-shaped manifest is replaced, not duplicated.

Legacy EA information maps as follows:

| Existing EA field | Manifest V1 location |
|---|---|
| `sourceFile` | `source.name` |
| `generatedAt` | `generation.timestampToken` + ISO `generation.generatedAt` |
| `schema` / `tableCount` | `models[]` |
| per-table `oracleSql`, `mysqlSql`, Excel paths | `artifacts[]` |
| Mermaid/Graphviz paths | `artifacts[]` |
| `dependencyOrder` | `extensions.enterpriseArchitect.dependencyOrder` |
| `cyclicTables` | `extensions.enterpriseArchitect.cyclicTables` |

No parallel legacy EA manifest is retained. Consumers can identify the new contract from
`manifestContract = schemaforge-manifest/v1`.

## 12. C6.2 planned implementation components

After the R2 regression gate is green, C6.2 may add a small dedicated package such as:

```text
ArtifactManifest
ArtifactManifestArtifact
ArtifactIntegrity
ArtifactManifestModel
ArtifactManifestValidation
ArtifactManifestWriter
ArtifactManifestAssembler
```

Exact class names may be adjusted during implementation, but responsibilities must remain separate
from `SchemaForgeApiService` so C6 does not make the existing service larger than necessary.

`SchemaForgeApiService` should only provide request/schema/ledger context and invoke the manifest
component at package finalization points.

## 13. C6.2 production paths to wire

- Standard Word package finalization;
- Legacy Word package finalization;
- ZIP Batch package finalization after child-ledger remapping/collision handling;
- EA package finalization replacing the current custom manifest writer.

## 14. C6 non-goals

C6 must not change:

```text
Artifact naming/layout from C5
DDL/CRUD/Migration SQL semantics
Word/Legacy/EA parsers
Canonical model semantics
Metadata recovery rules
Physical DDL
REST endpoint URLs
REST error/status contract (C7)
standalone CRUD/Mermaid response type
```

## 15. C6.2 test plan

Minimum targeted coverage:

1. manifest DTO/schema serialization test;
2. checksum/size verification test;
3. manifest self-entry test;
4. duplicate generated-path rejection test;
5. Word ZIP manifest vs real archive test;
6. Legacy Word ZIP manifest vs real archive test;
7. ZIP Batch manifest vs collision-remapped final archive paths;
8. EA manifest contract and EA extension migration test;
9. generation ID/timestamp consistency test;
10. skipped/failed outcome serialization test;
11. assertion that standalone CRUD/Mermaid HTTP payloads remain unchanged.

After targeted tests, the normal full `mvnw.cmd clean test` remains the promotion gate.

## 16. C6.1 exit decision

C6.1 design is complete. No C6 production source is changed by this design step.

C6.2 implementation is allowed only after the pending C5.3-R2 MySQL repair passes its regression gate.
