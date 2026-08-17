# Developer Guide

## 1. Development objective

Keep SchemaForge V4 small, layered, evidence-driven, and regression-safe. Add a new abstraction only when the existing model cannot represent a real supported requirement safely.

## 2. Where changes belong

| Change | Primary package/class area |
|---|---|
| Word/legacy parsing | `specification.parser` / `specification.parser.legacy` |
| EA import | `specification.parser.ea` |
| normalization | `specification.normalization` |
| canonical schema shape | `domain.model` / `domain.valueobject` |
| schema preparation | `application.SchemaPreparationService` |
| DBMS selection | `application.DatabasePlatform`, `DialectFactory` |
| datatype/SQL rendering | `dialect.<vendor>` |
| physical DDL validation/rendering | `physical.<vendor>` plus vendor dialect integration |
| live metadata acquisition | `metadata.repository` |
| expected-vs-actual physical comparison | `metadata.validation.PhysicalMetadataComparator` |
| Excel workbook | `reporting.SchemaCompareExcelWriter` |
| snapshots/cache | `snapshot` |
| REST orchestration | `api` |
| DDL orchestration | `generation.DdlGenerator` |

## 3. Non-negotiable boundaries

### Keep `DdlGenerator` vendor-neutral

Vendor-specific SQL belongs in the selected dialect/renderer.

### Keep reporting JDBC-free

`SchemaCompareExcelWriter` receives canonical `documentTable` and `databaseTable`. It must not query the database directly.

### Keep metadata actual separate from design

Repository-acquired physical state may populate the database-side canonical object for comparison. It must not overwrite the specification-side model.

### Keep build options separate

Do not merge `Index.buildOptions` into persistent `physicalOptions`.

### Do not reopen the legacy parser for unrelated work

Physical, metadata, reporting, or dialect-only changes should not change parser versions or force the large historical Word corpus to be reparsed.

## 4. Recipe: add a datatype mapping

1. Identify the canonical datatype semantics.
2. Change only the target vendor type mapper/dialect unless the canonical model truly lacks required semantics.
3. Add unit tests for valid and invalid bounds.
4. Add cross-dialect regression coverage where portability is affected.
5. Do not silently clamp an unsupported precision/scale unless the frozen dialect contract explicitly defines a bounded-review behavior.

## 5. Recipe: add a persistent physical option

1. Determine the correct object scope: table, column, index, PK backing index, UK backing index, partition, LOB, or another dedicated object.
2. If the current domain model already represents that scope, reuse it.
3. Add source/profile validation in the vendor physical renderer.
4. Emit executable syntax only when safe; otherwise emit issue/review text.
5. If the option is persistent and can be read reliably from the vendor catalog, extend metadata acquisition for the **actual** side.
6. Add comparator normalization/equivalence only where semantics require it.
7. Extend the workbook only if a new comparison shape is required.
8. Add tests at renderer, repository, comparator, and report layers as applicable.

Do not put partition-, LOB-, recovery-, or operation-scoped semantics into a generic map merely to avoid creating the correct model.

## 6. Recipe: add an index build option

1. Add it to `Index.buildOptions`, not `physicalOptions`.
2. Validate dependencies/conflicts in the target dialect.
3. Keep it explicit; do not invent a default beyond documented frozen behavior.
4. Do not reverse-engineer it from current database state unless the future catalog semantics prove exact equivalence.
5. Ensure snapshot round-trip if the canonical build-option model changes.

## 7. Recipe: extend physical metadata comparison

1. Extend the appropriate `Jdbc*MetadataRepository` query.
2. Map only persistent, comparable current state.
3. Put actual values on the database-side canonical object.
4. Extend `PhysicalMetadataComparator` with vendor-aware equivalence/review rules.
5. Reuse existing physical sheets where possible.
6. Keep mixed/ambiguous values as `REVIEW`.
7. Never mutate document/design intent.

## 8. Recipe: add a new database platform

At minimum evaluate:

- `DatabasePlatform` aliases;
- `DialectFactory` registration;
- dialect implementation;
- identifier rendering;
- datatype mapper;
- expression mapping;
- physical renderer/resolver integration;
- metadata repository/resolver integration;
- output file naming;
- validation;
- logical and physical comparison behavior;
- unit/integration regression coverage;
- documentation matrix.

A new platform is not complete merely because `CREATE TABLE` renders.

## 9. Regression discipline

Current green baseline:

```text
399 tests
0 failures
0 errors
3 skipped
```

For source changes:

```bash
mvn clean test
```

Do not claim the full suite is green until the full Maven result is available.

For documentation-only packaging, verify that the `src` tree hash is unchanged from the last user-verified source baseline.

## 10. Scope discipline

Prefer a narrow phase with a clear contract and regression gate. Avoid broad enterprise abstractions unless a real source/model requirement demands them.
