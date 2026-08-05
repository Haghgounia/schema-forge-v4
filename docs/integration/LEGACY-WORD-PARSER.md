# Legacy Word Parser Integration

## REST endpoint

```text
POST /api/v1/generate/legacy-word?schema=DPS
Content-Type: multipart/form-data
file=<legacy .doc or .docx>
```

The `schema` parameter is required because legacy documents do not declare it. After parsing, the same canonical model and the same generation pipeline used by `/api/v1/generate/word` are used, so the archive layout and generated artifacts do not have a legacy-specific format.

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
