# Legacy Word Parser Integration

## REST endpoint

```text
POST /api/v1/generate/legacy-word?schema=DPS
Content-Type: multipart/form-data
file=<legacy .doc or .docx>
```

The `schema` parameter is required because legacy documents do not declare it. After parsing, the same canonical model and the same generation pipeline used by `/api/v1/generate/word` are used, so the archive layout and generated artifacts do not have a legacy-specific format.


## Recursive multi-database directory generation

Use this runner when the same Legacy Word corpus must be rendered for Oracle, PostgreSQL, Microsoft SQL Server, and Db2 for z/OS from one canonical parse. Each document is parsed and prepared once; every selected dialect receives the same prepared model.

Windows:

```bat
mvnw.cmd -Dtest=WordDirectoryMultiDatabaseGenerationIT ^
  -Dschemaforge.word.inputDir=D:\LegacyDocs ^
  -Dschemaforge.word.outputDir=D:\LegacySql ^
  -Dschemaforge.word.legacySchema=TSTSHMA ^
  -Dschemaforge.word.platforms=oracle,postgresql,sqlserver,db2zos ^
  -Dschemaforge.word.failOnErrors=false test
```

Linux/macOS:

```bash
./mvnw -Dtest=WordDirectoryMultiDatabaseGenerationIT \
  -Dschemaforge.word.inputDir=/data/legacy-docs \
  -Dschemaforge.word.outputDir=/data/legacy-sql \
  -Dschemaforge.word.legacySchema=TSTSHMA \
  -Dschemaforge.word.platforms=oracle,postgresql,sqlserver,db2zos \
  -Dschemaforge.word.failOnErrors=false test
```

The default platform list is `oracle,postgresql,sqlserver,db2zos`, so the `schemaforge.word.platforms` property may be omitted when all four are required. Output is separated by DBMS:

```text
LegacySql/
  oracle/
  postgresql/
  sqlserver/
  db2zos/
  reports/
    word-multidb-generation-summary_<timestamp>.csv
    word-multidb-generation-issues_<timestamp>.csv
    word-multidb-generation-summary_<timestamp>.txt
```

`GENERATED_WITH_ISSUES` means the SQL file was still written but the DBMS-specific static validator found a potential problem. `GENERATION_FAILED` means the dialect could not render the canonical model, for example because a source type cannot be represented safely. Keep `schemaforge.word.failOnErrors=false` during discovery so the complete corpus is processed; set it to `true` only when the reports are clean and the runner is being used as a regression gate.

Oracle output is checked by `OracleDdlSanityChecker`, PostgreSQL output by `PostgreSqlDdlSanityChecker`, SQL Server output by `SqlServerOfflineDdlValidator`, and Db2 for z/OS output by `Db2ZosOfflineDdlValidator`. These checks are pre-execution safety nets; final compatibility must still be proven by executing the generated scripts on the intended database versions.

## Recursive Oracle-only directory test

Windows:

```bat
mvnw.cmd -Dtest=WordDirectoryOracleGenerationIT ^
  -Dschemaforge.word.inputDir=D:\LegacyDocs ^
  -Dschemaforge.word.outputDir=D:\LegacyOracleSql ^
  -Dschemaforge.word.legacySchema=DPS test
```

Linux/macOS:

```bash
./mvnw -Dtest=WordDirectoryOracleGenerationIT \
  -Dschemaforge.word.inputDir=/data/legacy-docs \
  -Dschemaforge.word.outputDir=/data/oracle-sql \
  -Dschemaforge.word.legacySchema=DPS test
```

The test recursively scans `.doc` and `.docx` files. Current SchemaForge DOCX documents are parsed by `WordSpecificationParser`; old DOC/DOCX documents fall back to `LegacyWordSpecificationParser`. It mirrors the input directory structure and creates one Oracle DDL file for every accepted table document.
