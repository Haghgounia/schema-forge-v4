# SchemaForge V4 - Timestamped Output Files (Step 7)

Every file created by one application pipeline run now receives the same Gregorian date/time suffix.

## Naming pattern

```text
<input-base-name>_yyyyMMdd_HHmmss_SSS.json
<input-base-name>_yyyyMMdd_HHmmss_SSS.sql
```

Example:

```text
MCB.BIM.TBL.CONTINENTS.V1.0_20260725_120945_123.json
MCB.BIM.TBL.CONTINENTS.V1.0_20260725_120945_123.sql
```

The timestamp is generated once per run using the application system time zone. Both JSON and SQL therefore remain identifiable as one output set. Milliseconds are included to reduce accidental overwrites during rapid repeated executions.

## Design

- `OutputFileNamer` owns the naming policy.
- `SchemaGenerationService` orchestrates generation and does not construct filenames directly.
- `Clock` can be injected for deterministic tests.
- No DBMS-specific behavior is introduced into naming.
