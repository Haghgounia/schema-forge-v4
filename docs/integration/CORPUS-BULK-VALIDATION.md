# Bulk Corpus Validation

SchemaForge has two independent production input corpora:

1. **New-format Word** documents (roughly 700-800 files). These must use the standard Word parser.
2. **Legacy canonical JSON** snapshots extracted from older Word documents (roughly 2500 files). These are read directly; old Word files are not reopened during DDL iteration.

Both sources converge only after a DBMS-neutral `DatabaseSchema` has been obtained. Oracle, PostgreSQL, SQL Server and Db2 for z/OS are then rendered from that same canonical model.

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
  -Dschemaforge.word.platforms=oracle,postgresql,sqlserver,db2zos ^
  -Dschemaforge.word.failOnErrors=false
```

`schemaforge.word.parserMode` accepts:

- `standard` - new-format DOCX only; no legacy fallback.
- `legacy` - legacy parser only; `schemaforge.word.legacySchema` is required.
- `auto` - previous behavior: standard first and legacy fallback; this remains the default for backward compatibility.

## 3. Legacy canonical JSON corpus

After the inventory confirms **contract compatibility**, generate all four dialects without reopening Word. The runner accepts contract-compatible persisted JSON even when its parser provenance is stale; the text summary reports the stale-parser count:

```bat
mvnw.cmd -Dtest=CanonicalJsonDirectoryToDdlIT test ^
  -Dschemaforge.snapshot.ddl.inputDir="D:\SchemaForge\LegacyJson" ^
  -Dschemaforge.snapshot.ddl.outputDir="D:\SchemaForge\LegacyJsonSql" ^
  -Dschemaforge.snapshot.ddl.platforms=oracle,postgresql,sqlserver,db2zos ^
  -Dschemaforge.snapshot.ddl.failOnErrors=false ^
  -Dschemaforge.snapshot.ddl.cleanOutput=true
```

The JSON runner accepts canonical `*.schema.json` snapshots only. A JSON file with another contract is reported by the inventory and must not be silently interpreted as canonical metadata.

## 4. Discovery before gating

For the first complete run keep `failOnErrors=false`. The purpose is to measure the entire corpus, not stop at the first defect. Review the generated CSV reports, classify recurring errors, fix root causes, then repeat. Only when the reports are clean should `failOnErrors=true` be used as a regression gate.

The normal `mvn clean test` suite remains independent from these large-corpus runners; all three runners are explicitly invoked integration utilities.
