# Phase 3 to Phase 4 Migration Manifest

این فهرست بر اساس کلاس‌ها و تست‌هایی است که در خروجی‌های Build فاز سه مشاهده شده‌اند. انتقال باید Patch-by-Patch و همراه تست انجام شود، نه با Copy کامل پروژه.

## Patch 00 - Bootstrap and infrastructure

| Phase 3 item | Phase 4 destination | Action |
|---|---|---|
| `SchemaForgeApplication` | root | Move with minimal change |
| `pom.xml` dependencies and plugin configuration | `pom.xml` | Reconcile, do not rewrite blindly |
| `application.yml/properties` | resources | Carry forward only active settings |
| `SystemController` | `api` | Move |
| Smoke test | root test package | Move |

## Patch 01 - Canonical model and parsing

| Phase 3 item | Phase 4 destination | Action |
|---|---|---|
| DOCX parser classes under `specification.adapter.docx` | `parser/docx` | Move and rename package |
| DOCX corpus/worst-case tests | `parser/docx` tests | Move after fixture paths are cleaned |
| Scattered normalizers | `parser/SchemaNormalizer` | Consolidate |
| Database-specific normalization | corresponding `dialect` | Consolidate |

## Patch 02 - Dialect

| Phase 3 item | Phase 4 destination | Action |
|---|---|---|
| `OracleDialect` and useful Oracle rules/mappers | `dialect/oracle` | Preserve behavior, reduce class fragmentation |
| PostgreSQL dialect implementation | `dialect/postgresql` | Preserve behavior |
| Dialect registry | `dialect/DialectRegistry` | Move |
| Identifier/data-type/naming/syntax policies | inside dialect package | Merge only where safe |

## Patch 03 - Oracle generation

| Phase 3 item | Phase 4 destination | Action |
|---|---|---|
| `OracleDdlGenerator` | `generation/oracle` | Move and remove cache dependency |
| `OracleTableGenerator` | `generation/oracle` | Move |
| `OracleHintBuilder` | `generation/oracle` | Move |
| `OracleDdlGeneratorTest` | matching test package | Move |
| `OracleHintBuilderTest` | matching test package | Move |
| `OracleMetadataAwareDdlGenerationTest` | integration tests | Rewrite to fetch fresh metadata |

## Patch 04 - PostgreSQL generation

| Phase 3 item | Phase 4 destination | Action |
|---|---|---|
| `PostgreSqlTableScriptGenerator` | `generation/postgresql` | Move |
| `PostgreSqlSequenceGenerator` | `generation/postgresql` | Move |
| corresponding tests | matching test package | Move |

## Patch 05 - Database metadata

| Phase 3 item | Phase 4 destination | Action |
|---|---|---|
| `JdbcOracleMetadataRepository` behavior | `repository/oracle/OracleRepository` | Merge into the single Oracle repository |
| old `OracleMetadataRepository` abstraction | removed | Do not transfer |
| `OracleDictionaryCache` | removed | Do not transfer; no application cache |
| PostgreSQL metadata readers | `repository/postgresql/PostgreSqlRepository` | Merge into one repository |

## Patch 06 - Validation and comparison

| Phase 3 item | Phase 4 destination | Action |
|---|---|---|
| validation rules that are truly reusable | `validation` or dialect | Move by responsibility |
| `OracleDataTypeValidationRuleTest` | matching validation tests | Move after API cleanup |
| comparison engine/classes | `comparison` | Move |
| `SchemaCompareExcelWriter` | `comparison` | Keep as output writer near comparison; no `reporting` root package |
| `SchemaCompareExcelWriterTest` | matching test package | Move |

## Explicitly not transferred

- `OracleDictionaryCache` and its tests.
- Deprecated repository interfaces retained only for backward compatibility.
- Thin application services that merely delegate one call.
- Root packages `reporting`, `packaging`, and `shared`.
- Duplicate or scattered normalizer classes.

## Important limitation

The full phase-three source archive was not available inside this generated artifact. Therefore this manifest identifies migration candidates from the observed build output and prior architecture discussion; exact source-level migration must be performed when the current v3 source tree is supplied.
