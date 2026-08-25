# Bulk Corpus Validation

SchemaForge has two independent production input corpora:

1. **New-format Word** documents (roughly 700-800 files). These must use the standard Word parser.
2. **Legacy canonical JSON** snapshots extracted from older Word documents (roughly 2500 files). These are read directly; old Word files are not reopened during DDL iteration.

Both sources converge only after a DBMS-neutral `DatabaseSchema` has been obtained. Oracle, PostgreSQL, SQL Server, Db2 for z/OS and MySQL are then rendered from that same canonical model.

## 1. Preflight inventory

Run this before a long batch so the JSON contract/version distribution is known:

```bat
mvnw.cmd -Dtest=CorpusInventoryIT test ^
  -Dschemaforge.corpus.word.inputDir="D:\SchemaForge\NewWord" ^
  -Dschemaforge.corpus.json.inputDir="D:\SchemaForge\LegacyJson" ^
  -Dschemaforge.corpus.outputDir="D:\SchemaForge\CorpusReports"
```

The inventory does **not** parse Word and does **not** generate DDL. It writes:

- `corpus-inventory-summary_<timestamp>.txt`
- `corpus-json-inventory_<timestamp>.csv`
- `corpus-json-version-summary_<timestamp>.csv`

The inventory reports two distinct compatibility concepts:

- **Contract compatible**: `snapshotVersion` and `modelVersion` match the current canonical JSON/domain contract. A persisted JSON corpus in this state is eligible for JSON-to-DDL generation.
- **Cache compatible**: contract compatible **and** produced by the current `parserVersion`. This stricter check is used only when deciding whether a Word-derived cache can replace reparsing the Word source.

An older `parserVersion` is therefore reported as `STALE_PARSER`, not as a broken JSON contract. For a persisted legacy JSON corpus this is a provenance/semantic-freshness warning. It does not by itself make the JSON unreadable. Known parser corrections made after the snapshot was created are not retroactively present in that JSON unless a separate migration/reconciliation step is applied.

## 2. New-format Word corpus

Use `parserMode=standard` so an invalid new-format document is not silently sent to the legacy parser:

```bat
mvnw.cmd -Dtest=WordDirectoryMultiDatabaseGenerationIT test ^
  -Dschemaforge.word.inputDir="D:\SchemaForge\NewWord" ^
  -Dschemaforge.word.outputDir="D:\SchemaForge\NewWordSql" ^
  -Dschemaforge.word.parserMode=standard ^
  -Dschemaforge.word.platforms=oracle,postgresql,sqlserver,db2zos,mysql ^
  -Dschemaforge.word.failOnErrors=false
```

`schemaforge.word.parserMode` accepts:

- `standard` - new-format DOCX only; no legacy fallback.
- `legacy` - legacy parser only; `schemaforge.word.legacySchema` is required.
- `auto` - previous behavior: standard first and legacy fallback; this remains the default for backward compatibility.

## 3. Legacy canonical JSON corpus

After the inventory confirms **contract compatibility**, use `CanonicalJsonDirectoryToDdlIT` as the fast corpus gate. It never reopens Word files. R6.8 makes all five DBMS the default and records source-canonical statistics, numeric-mapping strategy, canonical validation counts, DBMS mapping/static validation, duplicate snapshots and output collisions.

Before a release/acceptance run, count the corpus so an accidentally wrong or partial directory cannot produce a false green result:

```bat
for /f %%N in ('dir /s /b "D:\SchemaForge\LegacyJson\*.schema.json" ^| find /c /v ""') do set "LEGACY_JSON_COUNT=%%N"
echo Legacy canonical snapshots: %LEGACY_JSON_COUNT%
```

### 3.1 SAFE pass

```bat
set "SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY=SAFE"

mvnw.cmd ^
  -Dtest=CanonicalJsonDirectoryToDdlIT ^
  -Dschemaforge.snapshot.ddl.inputDir="D:\SchemaForge\LegacyJson" ^
  -Dschemaforge.snapshot.ddl.outputDir="D:\SchemaForge\LegacyJsonSql-SAFE" ^
  -Dschemaforge.snapshot.ddl.platforms=oracle,postgresql,db2zos,sqlserver,mysql ^
  -Dschemaforge.snapshot.ddl.expectedMinSnapshots=%LEGACY_JSON_COUNT% ^
  -Dschemaforge.snapshot.ddl.failOnErrors=true ^
  -Dschemaforge.snapshot.ddl.failOnWarnings=false ^
  -Dschemaforge.snapshot.ddl.cleanOutput=true ^
  test
```

### 3.2 OPTIMIZED pass

Run the same historical corpus again with lossless native-integer narrowing enabled:

```bat
set "SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY=OPTIMIZED"

mvnw.cmd ^
  -Dtest=CanonicalJsonDirectoryToDdlIT ^
  -Dschemaforge.snapshot.ddl.inputDir="D:\SchemaForge\LegacyJson" ^
  -Dschemaforge.snapshot.ddl.outputDir="D:\SchemaForge\LegacyJsonSql-OPTIMIZED" ^
  -Dschemaforge.snapshot.ddl.platforms=oracle,postgresql,db2zos,sqlserver,mysql ^
  -Dschemaforge.snapshot.ddl.expectedMinSnapshots=%LEGACY_JSON_COUNT% ^
  -Dschemaforge.snapshot.ddl.failOnErrors=true ^
  -Dschemaforge.snapshot.ddl.failOnWarnings=false ^
  -Dschemaforge.snapshot.ddl.cleanOutput=true ^
  test
```

`SAFE` and `OPTIMIZED` use the same persisted canonical JSON and differ only in exact-numeric target rendering. Oracle remains Oracle-native in both modes. PostgreSQL, Db2 for z/OS, SQL Server and MySQL may use `SMALLINT`/`INTEGER`/`BIGINT` (or platform spelling) in `OPTIMIZED` when the full `NUMBER(p,0)` range fits losslessly.

The text summary now reports:

- snapshots discovered / selected / exact duplicates / failures;
- parser-provenance freshness;
- active numeric mapping strategy;
- canonical warnings and errors;
- aggregate tables, columns, PK, FK, UK, indexes, checks, sequences, identities and defaults;
- generated / warning-bearing / error-bearing / mapping-blocked / failed SQL counts for every DBMS.

The issue CSV includes an explicit severity. `failOnErrors=true` blocks only canonical errors, fatal mapping errors, static SQL validation errors, and generation failures. Accepted warnings remain fully reported but do not fail the run unless `failOnWarnings=true` is also requested.

The JSON runner accepts canonical `*.schema.json` snapshots only. A JSON file with another contract is reported by the inventory and must not be silently interpreted as canonical metadata.

## 4. Discovery before gating

For a first exploratory run on an unknown corpus, `failOnErrors=false` can still be used to measure every defect without failing the Maven test. For the formal acceptance run use `failOnErrors=true`; warnings remain visible but non-blocking unless `failOnWarnings=true` is explicitly enabled.

The normal `mvn clean test` suite remains independent from these large-corpus runners; all three runners are explicitly invoked integration utilities.
