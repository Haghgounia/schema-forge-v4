# C11 - Final Consolidation Regression and Baseline Freeze

**Stage:** C11  
**Status:** VERIFICATION CANDIDATE / PENDING USER REGRESSION  
**Change type:** TEST / DOC / BASELINE  
**Runtime/source change:** none

## 1. Objective

Freeze the final post-contract SchemaForge V4 consolidation baseline without adding features or refactoring production code.

The verification candidate uses the exact C8.10-R1 source already verified at targeted `43 / 0 / 0 / 0` and full `554 / 0 / 0 / 4`. C9 and C10 changed documentation/test-governance only.

## 2. Exact source identity before C11 verification

```text
Main Java : 276
Test Java : 189
src fingerprint:
03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
```

The current official source baseline remains:

```text
SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.10
```

until both C11 gates pass and the final C11 freeze is packaged.

## 3. Targeted consolidation gate

This gate covers the artifact contract, ledger/naming/manifest contracts, REST contract, all C8 producers/support/orchestrators, Word/Legacy/ZIP/EA API paths, MySQL EA identity compatibility, grants, and the Word regression corpus.

```bat
mvnw.cmd -Dtest=ArtifactContractTest,ArtifactLedgerTest,ArtifactNamingPolicyTest,ArtifactManifestWriterTest,SchemaForgeArtifactTrackingTest,SchemaForgeManifestContractTest,RestErrorResponseTest,SchemaForgeRequestCorrelationFilterTest,SchemaForgeRestContractMvcTest,SchemaForgeRestExceptionHandlerTest,DiagramArtifactProducerTest,MigrationArtifactProducerTest,ComparisonArtifactProducerTest,CrudArtifactProducerTest,BatchArchiveSupportTest,ArtifactPackageBuilderTest,DocumentGenerationOrchestratorTest,BatchGenerationOrchestratorTest,EaGenerationOrchestratorTest,ArtifactGenerationServiceTest,SchemaForgeApiServiceRegressionTest,SchemaForgeApiZipBatchTest,SchemaForgeEaPerTableOutputTest,SchemaForgeLegacyWordApiServiceTest,SchemaForgeApiComparisonExcelTest,WordSpecificationRegressionTest,MySqlEnterpriseArchitectIdentityCompatibilityTest,GrantSchemaEnricherTest test
```

Expected from current source inventory:

```text
Tests run: 95
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Maven output remains authoritative if the discovered count differs.

## 4. Full exact-source gate

```bat
mvnw.cmd clean test
```

Expected:

```text
Tests run: 554
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
```

The four normal-suite skips are the configuration-gated directory execution tests for Oracle, PostgreSQL, SQL Server and MySQL when their JDBC/SQL-root settings are not supplied.

`SchemaForgeRestExceptionHandlerTest` deliberately logs an internal exception while verifying that sensitive internal text is not exposed in the REST response. A logged `ERROR` from that test is not a Maven test error when Surefire reports `Errors: 0`.

## 5. Live-validation evidence rule

C11 does not convert test availability into a live-pass claim. Live evidence is recorded using the C9 vocabulary:

- `LIVE_TEST_AVAILABLE`
- `LIVE_TEST_EXECUTED_AND_PASSED`
- `SKIPPED_BY_CONFIGURATION`
- `NOT_EXECUTED_ENVIRONMENT_UNAVAILABLE`
- `NOT_EXECUTED_NOT_REQUIRED`
- `FAILED`

The C11 source-freeze claim requires the targeted and full exact-source gates above. DBMS live pilots are additionally required only for a live-execution claim or when a change affects the corresponding DBMS execution behavior. C9/C10/C11 introduce no DBMS SQL/execution behavior change.

## 6. Exit criteria

C11 is DONE only when:

1. targeted consolidation gate is green;
2. full `clean test` is green on the exact same source;
3. source fingerprint is rechecked and unchanged unless a separately documented repair was required;
4. live-status wording is evidence-accurate;
5. current baseline/reference/roadmap/changelog are updated;
6. final V4 ZIP is packaged and integrity-checked.

Planned final baseline ID after successful verification:

```text
SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C11
```
