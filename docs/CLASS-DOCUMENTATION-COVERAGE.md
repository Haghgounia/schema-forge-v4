# Class-level JavaDoc coverage

## Scope

This documentation pass completes class-level JavaDoc for every top-level Java type in the
production and test source sets. It documents each type's responsibility, architectural boundary,
important inputs and outputs, and the regression behavior protected by test classes.

Method-level JavaDoc is not part of this pass. Public API methods, configuration properties and
extension-point interfaces should be handled as a separate documentation phase so method contracts
can be reviewed together rather than filled with repetitive comments.

## Coverage result

| Source set | Top-level types | Documented | Missing |
|---|---:|---:|---:|
| `src/main/java` | 165 | 165 | 0 |
| `src/test/java` | 85 | 85 | 0 |
| **Total** | **250** | **250** | **0** |

The following nested legacy-parser types were also documented because they form the intermediate
extraction contract:

- `ExtractionModels.Status`
- `ExtractionModels.Severity`
- `ExtractionModels.WordFormat`
- `ExtractionModels.Metadata`
- `ExtractionModels.ExtractionWarning`
- `ExtractionModels.ColumnDefinition`
- `ExtractionModels.FileResult`
- `ExtractionModels.RunSummary`
- `FieldSupplementParser.Supplement`
- `LengthValueParser.ParsedLength`

## Production types completed in this pass

- `OracleCrudController`
- `SqlServerCrudController`
- `DocTableExtractor`
- `DocxXmlTextExtractor`
- `ExtractionModels`
- `FieldSupplementParser`
- `LengthValueParser`
- `StackTraces`
- `TextNormalizer`
- `WordFileDetector`

## Test types completed in this pass

- `NumericTypeOptimizationServiceTest`
- `SchemaForgeLegacyWordApiServiceTest`
- `OracleCrudControllerTest`
- `SqlServerCrudControllerTest`
- `OracleCrudGenerationServiceTest`
- `SqlServerCrudGenerationServiceTest`
- `OracleCrudPackageGeneratorTest`
- `SqlServerCrudProcedureGeneratorTest`
- `EnterpriseArchitectPartyProbeTest`
- `FinalMetadataAcceptanceRegressionTest`
- `LegacyDataTypeNormalizerTest`
- `LegacyDefaultValueNormalizerTest`
- `LegacyDocRawMetadataScannerRegressionTest`
- `LegacyOracleGenerationPipelineTest`
- `LegacyWordSpecificationParserMappingTest`
- `LengthValueParserTest`
- `SchemaForgeWordTableParserApiTest`
- `TextNormalizerTest`
- `Db2ZosConnectionProbeServiceTest`
- `Db2ZosOfflineDdlValidatorTest`
- `OracleDdlSanityCheckerTest`
- `SqlServerConnectionProbeServiceTest`
- `SqlServerOfflineDdlValidatorTest`

## Documentation rules applied

1. JavaDoc describes responsibility and architectural boundary instead of restating the class name.
2. Parser documentation distinguishes source evidence from the canonical domain model.
3. Controller documentation makes delegation and error-mapping responsibilities explicit.
4. Test documentation records the regression boundary protected by the suite.
5. No runtime behavior, public API, SQL generation rule or package structure was changed.

## New multi-database types

The PostgreSQL/SQL Server batch-generation extension added three documented top-level types:

- `PostgreSqlDdlSanityChecker`
- `WordDirectoryMultiDatabaseGenerationIT`
- `PostgreSqlDdlSanityCheckerTest`

All three follow the same class-level JavaDoc rule, so coverage remains complete after the extension.

## Verification

A source scan was run after the changes and found zero top-level types without class-level JavaDoc.
The Maven wrapper was also invoked, but Maven 3.9.9 could not be downloaded from Maven Central in
the execution environment. Since this pass changes comments only, no Java executable statement or
signature was modified; nevertheless, a complete `mvn clean verify` should be run in the project
environment before release.
