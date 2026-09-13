# SQL Server SS-P9.1 - Catalog Semantic Normalization

## Evidence

SS-P9 read the persistent 1,908-table SQL Server cohort and reported:

- Missing tables: 0
- Extra tables: 0
- Columns: 28,690 / 28,690
- Primary keys: 1,584 / 1,584
- Foreign keys: 19 / 19
- Residual object changes: 0
- Residual column changes: 1,159

The residual CSV was exhaustively classified:

- 1,060 `ALTER_NULLABILITY` rows. Every one of the 1,060 columns is a PRIMARY KEY column in the generated integrated SQL.
- 99 `ALTER_DEFAULT` rows:
  - 90 `GETDATE()` vs `CURRENT_TIMESTAMP` catalog/canonical representations.
  - 9 equivalent unquoted numeric literal representations (`0`, `00`, `-0`, redundant parentheses, or trailing decimal point).

No `ALTER_TYPE`, identity, generated-expression, FK, PK, index, check, or other object drift was present.

## Change

`SchemaDiffEngine` now treats these SQL Server catalog representations as semantic equivalence:

1. PRIMARY KEY implied NOT NULL.
2. `GETDATE()` and `CURRENT_TIMESTAMP` defaults.
3. Equivalent unquoted numeric default literals after SQL Server catalog canonicalization.

This is comparison-only normalization. It does not rewrite canonical input or generated SQL.

## Verification

The production class compiles with Java 21 and an independent smoke program covering all three normalization families passes. The Maven wrapper could not execute in the build sandbox because Maven 3.9.9 was not locally cached and network download is unavailable; the project-side Maven regression gate should therefore be run after applying the delta.
