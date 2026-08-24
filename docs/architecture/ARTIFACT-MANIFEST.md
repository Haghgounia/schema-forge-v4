# SchemaForge V4 - Standard Artifact Manifest V1

**Manifest contract:** `schemaforge-manifest/v1`  
**Artifact contract:** `1`  
**Stage:** `C6.2`  
**Status:** `DONE / USER-VERIFIED / OFFICIAL`  
**Official baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C6.2`

## Purpose

Every ZIP-producing REST generation path now writes one authoritative package-root `manifest.json`:

- Standard Word;
- Legacy Word;
- ZIP Batch;
- Enterprise Architect XML/XMI.

Standalone Oracle CRUD, SQL Server CRUD, and canonical-JSON Mermaid endpoints keep their existing
non-ZIP HTTP payloads in C6. Their response/error normalization remains C7 scope.

## Contract shape

```json
{
  "manifestContract": "schemaforge-manifest/v1",
  "artifactContractVersion": "1",
  "generation": {
    "id": "...",
    "timestampToken": "yyyyMMdd_HHmmss_SSS",
    "generatedAt": "ISO-8601 offset datetime"
  },
  "source": {
    "origin": "STANDARD_WORD|LEGACY_WORD|ZIP_BATCH|ENTERPRISE_ARCHITECT",
    "name": "..."
  },
  "models": [],
  "validation": {
    "available": true,
    "errorCount": 0,
    "warningCount": 0,
    "recoveryWarningCount": 0
  },
  "artifactOutcomes": {
    "generated": 0,
    "skipped": 0,
    "failed": 0
  },
  "artifacts": [],
  "extensions": {}
}
```

## Integrity rules

Every non-manifest `GENERATED` artifact records:

```text
algorithm = SHA-256
sha256    = lowercase 64-hex digest of exact packaged bytes
sizeBytes = exact byte size of the packaged file
```

`SKIPPED` and `FAILED` outcomes have null path/media/integrity. `manifest.json` appears once as a
`MANIFEST / GENERATED` self-entry with null integrity to avoid recursive self-checksum semantics.

Before serialization the assembler verifies that every regular package file has exactly one
`GENERATED` descriptor and that every non-manifest generated descriptor points to one real file.
After serialization the writer verifies exact equality between final package files and generated
descriptor paths.

## Determinism

- models: source name, then schema;
- model tables: normalized qualified name;
- artifacts: type, nullable platform, normalized logical name, status, path;
- EA dependency order is preserved semantically and is not re-sorted.

## Enterprise Architect migration

The former EA-only map-shaped manifest is removed. EA now uses the same standard contract as other
ZIP-producing paths. EA-specific dependency/cycle information is retained under:

```text
extensions.enterpriseArchitect.dependencyOrder
extensions.enterpriseArchitect.cyclicTables
```

There is no parallel legacy EA manifest.

## Implementation components

```text
com.behsazan.schemaforge.artifact.manifest
    ArtifactManifest
    ArtifactManifestArtifact
    ArtifactManifestModel
    ArtifactManifestValidation
    ArtifactManifestOutcomes
    ArtifactIntegrity
    ArtifactManifestAssembler
    ArtifactManifestWriter
```

`ArtifactGenerationContext` now captures one request-level offset `generatedAt` in addition to the
existing generation ID and C5 timestamp token. Child and isolated-child contexts inherit the same
captured time.

## Compatibility boundary

C6.2 does not change:

- C5 filename or directory grammar;
- DDL/CRUD/Migration SQL semantics;
- Word/Legacy/EA parsing;
- canonical model semantics;
- metadata recovery;
- REST endpoint URLs or current HTTP error contract;
- standalone CRUD/Mermaid response bodies.

Word and Legacy ZIPs gain exactly one new file: `manifest.json`. EA replaces its old `manifest.json`
in place. ZIP Batch gains one root `manifest.json`; child documents never receive nested manifests.

## Verification state

Local preparation checks:

```text
Manifest package Java 21 compile with dependency stubs : PASS
Artifact context/naming Java 21 compile                 : PASS
SchemaForgeApiService Java syntax parse                 : PASS
New/changed test Java syntax parse                      : PASS
Maven wrapper                                            : unavailable in build environment (Maven Central blocked)
```

C6.2 official source inventory:

```text
Main Java files : 261
Test Java files : 175
Source fingerprint : b9fa369b5e9b079279fb577d40c77e1b14c8193fedea2f85ab0e6edadaf8969f
Surefire tests : 504
```

User verification: targeted `46/46`; full `504 / 0 / 0 / 4`; `BUILD SUCCESS` at `2026-08-23T00:58:39-07:00`.
