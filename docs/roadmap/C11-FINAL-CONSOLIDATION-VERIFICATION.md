# C11 - Final Consolidation Regression and Baseline Freeze

**Stage:** C11  
**Status:** DONE / OFFICIAL / FROZEN  
**Change type:** TEST / DOC / BASELINE  
**Runtime/source change:** none

## 1. Objective

Freeze the final post-contract SchemaForge V4 consolidation baseline without adding features or refactoring production code.

C11 uses the exact C8.10-R1 source. C9 and C10 changed documentation/test-governance only; C11 also introduces no source/test/runtime behavior change.

## 2. Frozen source identity

```text
Main Java : 276
Test Java : 189
src fingerprint:
03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
```

Official C11 baseline:

```text
SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C11
```

The source fingerprint is unchanged from C8.10 because C9, C10 and C11 do not change `src`.

## 3. User-verified targeted consolidation gate

Command:

```bat
mvnw.cmd -Dtest=ArtifactContractTest,ArtifactLedgerTest,ArtifactNamingPolicyTest,ArtifactManifestWriterTest,SchemaForgeArtifactTrackingTest,SchemaForgeManifestContractTest,RestErrorResponseTest,SchemaForgeRequestCorrelationFilterTest,SchemaForgeRestContractMvcTest,SchemaForgeRestExceptionHandlerTest,DiagramArtifactProducerTest,MigrationArtifactProducerTest,ComparisonArtifactProducerTest,CrudArtifactProducerTest,BatchArchiveSupportTest,ArtifactPackageBuilderTest,DocumentGenerationOrchestratorTest,BatchGenerationOrchestratorTest,EaGenerationOrchestratorTest,ArtifactGenerationServiceTest,SchemaForgeApiServiceRegressionTest,SchemaForgeApiZipBatchTest,SchemaForgeEaPerTableOutputTest,SchemaForgeLegacyWordApiServiceTest,SchemaForgeApiComparisonExcelTest,WordSpecificationRegressionTest,MySqlEnterpriseArchitectIdentityCompatibilityTest,GrantSchemaEnricherTest test
```

Verified result:

```text
Tests run: 95
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Finished: 2026-08-24T10:54:16+03:30
```

The included Word regression remained green:

```text
Documents : 9
Passed    : 9
Failed    : 0
Tables    : 9
Columns   : 117
```

## 4. User-verified full exact-source gate

Command:

```bat
mvnw.cmd clean test
```

Verified result:

```text
Tests run: 554
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Finished: 2026-08-24T10:16:01+03:30
```

The four normal-suite skips are the configuration-gated directory execution tests for Oracle, PostgreSQL, SQL Server and MySQL when their JDBC/SQL-root settings are not supplied.

`SchemaForgeRestExceptionHandlerTest` deliberately logs an internal exception while verifying that sensitive internal text is not exposed in the REST response. Its logged `ERROR` is expected test behavior; Surefire reported `Errors: 0`.

## 5. Live-validation evidence rule

C11 does not convert test availability into a live-pass claim. Live evidence remains governed by the C9 vocabulary:

- `LIVE_TEST_AVAILABLE`
- `LIVE_TEST_EXECUTED_AND_PASSED`
- `SKIPPED_BY_CONFIGURATION`
- `NOT_EXECUTED_ENVIRONMENT_UNAVAILABLE`
- `NOT_EXECUTED_NOT_REQUIRED`
- `FAILED`

The C11 source-freeze claim requires the targeted and full exact-source gates above. DBMS live pilots are additionally required only for a live-execution claim or when a change affects the corresponding DBMS execution behavior. C9/C10/C11 introduce no DBMS SQL/execution behavior change.

Db2 for z/OS live execution remains `NOT_EXECUTED_ENVIRONMENT_UNAVAILABLE` unless separate IBM z/OS/JCC evidence is recorded.

## 6. Packaging and integrity rule

The distributable C11 ZIP must be created from the committed Git tree, not from a Windows working-tree copy:

```bat
git archive --format=zip --prefix=schema-forge-v4/ -o <C11-OFFICIAL.zip> HEAD
```

The archive must independently reproduce:

```text
Main Java : 276
Test Java : 189
src fingerprint: 03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
```

A SHA-256 for the final ZIP is recorded alongside the distributed archive. This avoids CRLF/LF working-tree normalization from changing the canonical frozen source identity.

## 7. Exit criteria result

| Exit criterion | Result |
|---|---|
| Targeted consolidation gate green | PASS - `95 / 0 / 0 / 0` |
| Full exact-source `clean test` green | PASS - `554 / 0 / 0 / 4` |
| Source fingerprint unchanged | PASS - `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba` |
| Live-status wording evidence-accurate | PASS - C9 vocabulary retained |
| Current baseline/reference/roadmap/changelog updated | PASS - C11 finalization documentation |
| Final Git-based ZIP packaged and integrity-checked | Required release packaging step; use the C11 freeze script and retain its sidecar evidence |

C11 is the final stage of the C4-C11 consolidation track. No deferred feature is automatically activated by this freeze.
