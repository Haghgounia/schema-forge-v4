# Canonical JSON Snapshot Cache

## Purpose

Legacy Word parsing is intentionally separated from dialect development. A full recursive Word run can take several hours, while Oracle, PostgreSQL and SQL Server DDL rules change much more frequently. SchemaForge therefore supports a versioned canonical JSON snapshot cache:

```text
Word (.doc/.docx)
        |
        v
Legacy / standard Word parser
        |
        v
DBMS-neutral DatabaseSchema
        |
        v
*.schema.json
        |
        +--> Oracle DDL
        +--> PostgreSQL DDL
        +--> SQL Server DDL
```

The JSON stores the canonical model before dialect-specific rendering. It must not persist decisions such as Oracle `TIMESTAMP(9)`, PostgreSQL `TIMESTAMP(6)`, or SQL Server `DATETIME2(7)` when the source model originally carried another precision.

## Snapshot compatibility contract

Every snapshot records:

- `snapshotVersion`: JSON persistence contract version.
- `modelVersion`: canonical domain semantic version.
- `parserVersion`: Word parsing/normalization pipeline version.
- source relative path, SHA-256, size and last-modified timestamp.
- parser path used (`legacy-word`, `standard-word`, or `legacy-word-fallback`).
- the complete database-neutral schema model.

A cache entry is reused only when all three versions match and the source SHA-256 is unchanged. Changing a dialect does not invalidate snapshots. Changing Word parsing/normalization requires changing `CanonicalSnapshotVersions.PARSER_VERSION`, which forces a refresh.

## One-time / incremental Word to JSON run

```bat
mvnw.cmd -Dtest=WordDirectoryToCanonicalJsonIT test ^
  -Dschemaforge.snapshot.word.inputDir="D:\word-root" ^
  -Dschemaforge.snapshot.outputDir="D:\schemaforge-canonical-json" ^
  -Dschemaforge.snapshot.legacySchema=TSTSHMA ^
  -Dschemaforge.snapshot.failOnErrors=false
```

The first run parses the Word corpus and writes one JSON file per accepted table document while preserving the source directory structure:

```text
D:\schemaforge-canonical-json\
  manifest.json
  Customer\JTMSCUSTOMERS.doc.schema.json
  ...
```

Running the same command again computes source hashes but does not reopen unchanged Word files through the parser. Typical output after no source/parser changes is:

```text
Word documents    : 4766
Snapshots written : 0
Cache hits        : 4766
Skipped no table  : 0
Failures          : 0
```

Force a complete rebuild only when required:

```bat
-Dschemaforge.snapshot.forceRefresh=true
```

## Fast JSON to DDL run

Generate PostgreSQL and SQL Server without reading Word documents:

```bat
mvnw.cmd -Dtest=CanonicalJsonDirectoryToDdlIT test ^
  -Dschemaforge.snapshot.ddl.inputDir="D:\schemaforge-canonical-json" ^
  -Dschemaforge.snapshot.ddl.outputDir="D:\LegacyMultiDbSql" ^
  -Dschemaforge.snapshot.ddl.platforms=postgresql,sqlserver ^
  -Dschemaforge.snapshot.ddl.failOnErrors=false
```

Generate all supported project targets in this runner:

```bat
-Dschemaforge.snapshot.ddl.platforms=oracle,postgresql,sqlserver
```

Output layout:

```text
LegacyMultiDbSql\
  oracle\...
  postgresql\...
  sqlserver\...
  reports\
    canonical-json-ddl-summary_<timestamp>.csv
    canonical-json-ddl-issues_<timestamp>.csv
    canonical-json-ddl-summary_<timestamp>.txt
```

## Cache invalidation rules

| Change | Reparse Word? | Regenerate DDL? |
|---|---:|---:|
| Oracle dialect change | No | Oracle only |
| PostgreSQL dialect change | No | PostgreSQL only |
| SQL Server dialect change | No | SQL Server only |
| DDL sanity-checker change | No | affected DBMS |
| Word source file content changes | Changed file only | affected outputs |
| Legacy/standard parser changes | Yes, version-invalidated snapshots | Yes |
| Canonical model semantics change | Yes, model-version-invalidated snapshots | Yes |

## Safety and audit

`CanonicalSnapshotJsonStore` writes JSON using a temporary file and atomic replacement where the filesystem supports it. An interrupted run therefore does not intentionally replace a valid snapshot with a partial file.

`manifest.json` records every discovered source as `WRITTEN`, `CACHE_HIT`, `SKIPPED_NO_TABLE`, `HASH_FAILED`, or `PARSE_FAILED`, together with the source hash and snapshot path. This provides the audit trail required to explain which Word documents were actually reparsed.

## Current fast-path command after the 2026-08-08 dialect fixes

With snapshots already materialized under `D:\get-git-doc-files-master\SchemaForgeCanonicalJson`, regenerate PostgreSQL and SQL Server without reopening Word files:

```bat
mvnw.cmd -Dtest=CanonicalJsonDirectoryToDdlIT test ^
  -Dschemaforge.snapshot.ddl.inputDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson" ^
  -Dschemaforge.snapshot.ddl.outputDir="D:\get-git-doc-files-master\LegacyMultiDbSql-json" ^
  -Dschemaforge.snapshot.ddl.platforms=postgresql,sqlserver ^
  -Dschemaforge.snapshot.ddl.failOnErrors=false
```

SQL Server precision bounding is reported as `DIALECT_MAPPING` findings. These findings document a target-platform limitation; they do not modify or invalidate the canonical snapshot.


## Clean regeneration and PostgreSQL index guard

For a clean platform regeneration without deleting canonical snapshots, add:

```text
-Dschemaforge.snapshot.ddl.cleanOutput=true
```

The runner deletes only the selected platform output directories beneath `schemaforge.snapshot.ddl.outputDir` before writing new timestamped SQL files. It refuses to clean an output directory that could contain the snapshot input directory.

Before a long PostgreSQL database execution, run the fast regression guard:

```text
mvnw.cmd -Dtest=PostgreSqlIndexQualificationRegressionTest test
```

The PostgreSQL static validator also reports `POSTGRESQL_SCHEMA_QUALIFIED_INDEX_NAME` whenever a generated statement has the invalid shape `CREATE INDEX schema.index_name ...`.
