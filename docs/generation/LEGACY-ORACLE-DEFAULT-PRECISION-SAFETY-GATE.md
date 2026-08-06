# Legacy Oracle Default and Precision Safety Gate

## Purpose

This correction prevents legacy Word annotations from leaking into executable Oracle DDL and makes Oracle precision limits enforceable in the real generation pipeline.

## Effective pipeline

```text
Legacy/standard Word extraction
        -> LegacyDefaultValueNormalizer
        -> canonical Column.defaultValue
        -> OracleDialect precision bounding
        -> DdlGenerator
        -> OracleDdlSanityChecker
        -> Files.writeString
```

The final safety gate is deliberately placed immediately before the write operation. It is a defensive structural check, not a substitute for executing the script against an Oracle validation environment.

## Default-value policy

| Input category | Result | Recovery code |
|---|---|---|
| Numeric value plus explanation, e.g. `0 1- دائم 2- موقت` | Keep the numeric literal only | `LEGACY_DEFAULT_NORMALIZED` |
| Legacy current-time token, e.g. `CURRENT TIMESTAMP` | Convert to `CURRENT_TIMESTAMP` | `LEGACY_DEFAULT_NORMALIZED` |
| Typographic quoted literal | Convert to SQL single quotes | `LEGACY_DEFAULT_NORMALIZED` |
| Unresolved natural-language or structurally unsafe value | Remove the executable default | `LEGACY_DEFAULT_DROPPED` |
| Conservative SQL literal/function/sequence expression | Preserve | no change or normalized warning |

Quoted Persian string literals such as `N'فعال'` remain valid. Persian/Arabic text is rejected only when it appears outside a SQL quoted literal.

## Oracle precision policy

| Type | Oracle output rule |
|---|---|
| `NUMBER(p)` | `p` is bounded to 38 |
| `NUMBER(p,s)` | `p` is bounded to 38 and `s` to 127 |
| `TIMESTAMP(p)` | `p` is bounded to 9 |

The canonical model remains DBMS-neutral; the Oracle-specific bounds are applied by `OracleDialect` during rendering.

## Pre-write enforcement points

- `SchemaForgeApiService.writeAllDatabaseOutputs`
- EA per-table Oracle generation in `SchemaForgeApiService`
- `SchemaGenerationService`
- `WordDirectoryOracleGenerationIT` recursive Legacy Word batch path

A failed check raises `IllegalStateException` before the target SQL file is written. The batch runner records the document as failed and proceeds with the remaining documents.

## Verification performed on 2026-08-05

| Check | Before | After |
|---|---:|---:|
| SQL files | 4,766 | 4,766 |
| Files containing `CREATE TABLE` | 4,766 | 4,766 |
| Safety-gate affected files | 1,877 | 0 |
| Safety-gate findings | 11,889 | 0 |
| `NUMBER` precision above 38 | 22 occurrences / 21 files | 0 |
| `TIMESTAMP` precision above 9 | 362 occurrences / 308 files | 0 |
| `DEFAULT 0 1- دائم 2- موقت` | 19 files | 0 |
| `DEFAULT 1 1- فعال 0- غیرفعال` | 19 files | 0 |
| `DEFAULT 0 CTShahabInquiry` | 21 files | 0 |

The repaired output audit contains 6,745 transformations: 3,751 default normalizations, 2,610 dropped unsafe defaults, 22 `NUMBER` bounds, and 362 `TIMESTAMP` bounds.

## Test status

The changed core classes, Oracle dialect, legacy parser adapter, and safety checker were compiled with Java 21. A parser-to-canonical-model-to-`DdlGenerator` smoke test produced the expected corrected `JTMSCUSTOMERS` definitions and passed the safety gate. The complete Maven suite was not executed in the isolated build environment because the Maven Wrapper distribution was unavailable there; this limitation is environmental and is not represented as a successful full-suite run.
