# SchemaForge V4 - C9 Test Matrix and Live-Validation Classification

**Stage:** C9  
**Change type:** TEST / DOC  
**Status:** DONE / SOURCE UNCHANGED / MATRIX AUDITED  
**Source baseline:** `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C8.10`  
**Frozen source fingerprint:** `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`  
**C8.10 full regression:** `554 / 0 / 0 / 4`, BUILD SUCCESS, 2026-08-23T06:22:23-07:00  

## 1. Purpose

C9 makes test evidence explicit. A test class existing in the repository is not evidence that it ran, and a green ordinary Maven build is not evidence that a live database pilot ran. This document is the authoritative classification and gate policy for C10/C11 and later V4 maintenance.

The row-level inventory is [`TEST-MATRIX-C9.csv`](TEST-MATRIX-C9.csv). It contains every Java file under `src/test/java` in the C8.10 baseline.

## 2. Source-derived inventory

| Category | Java files/classes | Default `mvnw.cmd clean test` | C8.10 evidence |
|---|---:|---|---|
| Standard unit / contract | 107 | Yes | Executed and passed |
| Standard offline integration | 38 | Yes | Executed and passed |
| Directory execution | 4 | Discovered, configuration-gated | 4 skipped by configuration |
| Opt-in offline `*IT` | 25 | No | Not executed by C8.10 freeze |
| Live DB pilot `*IT` | 9 | No | Not executed by C8.10 freeze |
| Test support / helper | 6 | Not a test | Not applicable |
| **Total `src/test/java` Java files** | **189** |  |  |

Filename/discovery totals:

```text
*Test.java : 149
*IT.java   : 34
support    : 6
```

The 149 default-Surefire classes are classified as 107 unit/contract, 38 offline integration, and 4 configuration-gated directory execution classes. Surefire executed 554 test cases in the C8.10 full run; the four directory execution cases were skipped because their live configuration was absent.

## 3. Evidence vocabulary

Use only the following terms for database-dependent evidence:

| Status | Meaning |
|---|---|
| `LIVE_TEST_AVAILABLE` | A live-capable test exists and its prerequisites are known. This says nothing about execution. |
| `LIVE_TEST_EXECUTED_AND_PASSED` | The exact live test was run against the stated environment and passed; command/date/environment evidence must be recorded. |
| `SKIPPED_BY_CONFIGURATION` | Maven discovered the test but a configuration assumption disabled it. This is **not** a pass. |
| `NOT_EXECUTED_ENVIRONMENT_UNAVAILABLE` | A required live environment or driver was unavailable, so no live claim is allowed. |
| `NOT_EXECUTED_NOT_REQUIRED` | The live test was outside the declared scope of the change. |
| `FAILED` | The live or directory execution test ran and failed. |

`LIVE_TEST_AVAILABLE` and `LIVE_TEST_EXECUTED_AND_PASSED` must never be used interchangeably.

## 4. Default Surefire boundary

The project configures `maven-surefire-plugin` without custom include patterns. Therefore Maven's normal Surefire naming convention applies: `*Test` classes are part of the standard test lane and `*IT` classes are not part of ordinary `mvnw.cmd clean test`.

C8.10 proves the normal lane only:

```text
mvn clean test
Tests run: 554
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
```

It does **not** prove any of the 34 `*IT` classes executed.

## 5. Configuration-gated directory execution tests

These four classes are discovered in the normal suite but intentionally skip when their SQL-root/JDBC prerequisites are absent:

| DBMS | Class | Minimum enablement | Destructive guard |
|---|---|---|---|
| Oracle | `OracleSqlDirectoryExecutionTest` | `oracle.sql.root`, `oracle.jdbc.url`, `oracle.jdbc.user` | `oracle.sql.dropBeforeCreate=true` additionally requires `oracle.sql.confirmDestructive=true` and expected schema |
| PostgreSQL | `PostgreSqlDirectoryExecutionTest` | `postgresql.sql.root`, `postgresql.jdbc.url`, `postgresql.jdbc.user` | `postgresql.sql.dropBeforeCreate=true` additionally requires confirmation and expected schema |
| SQL Server | `SqlServerDirectoryExecutionTest` | `sqlserver.sql.root`, `sqlserver.jdbc.url`, `sqlserver.jdbc.user` | `sqlserver.sql.dropBeforeCreate=true` additionally requires confirmation and expected schema |
| MySQL | `MySqlDirectoryExecutionTest` | `mysql.sql.root`, `mysql.jdbc.url`, `mysql.jdbc.user` | `mysql.sql.dropBeforeCreate=true` additionally requires `mysql.sql.confirmDestructive=true` and expected database |

Current C8.10 state for all four is `SKIPPED_BY_CONFIGURATION`.

Db2 for z/OS has no equivalent default-Surefire directory execution test in this source baseline; Db2 live execution is opt-in through `Db2ZosLiveIT` and the Db2 migration pilot.

## 6. Live DB pilot inventory

Nine `*IT` classes contain real JDBC/live-database execution behavior:

| DBMS | Class | Purpose | Current C8.10 evidence |
|---|---|---|---|
| Db2/zOS | `Db2ZosLiveIT` | DDL execution + probe/offline validation against live Db2 | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| Db2/zOS | `Db2ZosMigrationM2LivePilotIT` | M2 migration live pilot | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| Oracle | `OracleForeignKeyDirectoryExecutionIT` | final-state historical FK live validation | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| Oracle | `OracleMigrationM2LivePilotIT` | M2 migration live pilot | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| PostgreSQL | `PostgreSqlMigrationM2LivePilotIT` | M2 migration live pilot | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| SQL Server | `SqlServerLiveIT` | disposable-schema DDL / metadata / comparison live validation | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| SQL Server | `SqlServerCrudLiveIT` | generated CRUD procedure live validation | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| SQL Server | `SqlServerMigrationM2LivePilotIT` | M2 migration live pilot | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |
| MySQL | `MySqlMigrationM2LivePilotIT` | M2 migration live pilot | `LIVE_TEST_AVAILABLE`; not executed in C8.10 freeze |

### Destructive confirmation requirements

- `Db2ZosLiveIT`: `schemaforge.db2zos.execution.confirm=I_UNDERSTAND_DB2_DDL_MAY_COMMIT`.
- `Db2ZosMigrationM2LivePilotIT`: the same Db2 execution confirmation plus `schemaforge.db2zos.migration.confirmDestructive=true`.
- `SqlServerLiveIT` / `SqlServerCrudLiveIT`: `schemaforge.sqlserver.execution.confirm=I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE`.
- Oracle/PostgreSQL/SQL Server/MySQL M2 live pilots require their DBMS-specific `schemaforge.<dbms>.migration.confirmDestructive=true`.
- Db2 live execution additionally requires an external IBM JCC driver environment; the project does not redistribute JCC.

Exact property names for every live and offline `*IT` are recorded in the CSV matrix.

## 7. Opt-in offline IT inventory

The remaining 25 `*IT` classes are offline/corpus/workflow jobs. They may require external directories, snapshots, sample ZIPs or metadata files, but they do not require a live JDBC database. They are not part of ordinary Surefire.

- `com.behsazan.schemaforge.integration.CanonicalJsonDependencyCoverageIT`
- `com.behsazan.schemaforge.integration.CanonicalJsonDirectoryAllArtifactsIT`
- `com.behsazan.schemaforge.integration.CanonicalJsonDirectoryToDdlIT`
- `com.behsazan.schemaforge.integration.CanonicalJsonDuplicateTableDefinitionAuditIT`
- `com.behsazan.schemaforge.integration.CanonicalJsonForeignKeyAnalysisIT`
- `com.behsazan.schemaforge.integration.CanonicalJsonIntegratedDeploymentPilotIT`
- `com.behsazan.schemaforge.integration.CanonicalJsonMermaidPilotIT`
- `com.behsazan.schemaforge.integration.CanonicalJsonSpecialDependencyPilotIT`
- `com.behsazan.schemaforge.integration.CorpusInventoryIT`
- `com.behsazan.schemaforge.integration.MySqlCrossSchemaReconciliationGenerationIT`
- `com.behsazan.schemaforge.integration.MySqlCrossSourceConflictAuditIT`
- `com.behsazan.schemaforge.integration.MySqlDb2TableReconciliationAuditIT`
- `com.behsazan.schemaforge.integration.MySqlFinalRecoveryGenerationIT`
- `com.behsazan.schemaforge.integration.MySqlHistoricalColumnNameCorroborationAuditIT`
- `com.behsazan.schemaforge.integration.MySqlHistoricalConsensusRecoveryGenerationIT`
- `com.behsazan.schemaforge.integration.MySqlMetadataRecoveryAuditIT`
- `com.behsazan.schemaforge.integration.MySqlMetadataRecoveryGenerationIT`
- `com.behsazan.schemaforge.integration.MySqlRemainingColumnReconciliationAuditIT`
- `com.behsazan.schemaforge.integration.MySqlStrongTableReconciliationGenerationIT`
- `com.behsazan.schemaforge.integration.PhysicalPhase1CorpusAuditIT`
- `com.behsazan.schemaforge.integration.SchemaDocuments3ZipMermaidOutputIT`
- `com.behsazan.schemaforge.integration.WordDirectoryMultiDatabaseGenerationIT`
- `com.behsazan.schemaforge.integration.WordDirectoryOracleGenerationIT`
- `com.behsazan.schemaforge.integration.WordDirectoryToCanonicalJsonIT`
- `com.behsazan.schemaforge.specification.parser.legacy.LegacyWordFailureEvidenceIT`

These tests are required when their corpus/recovery/workflow area changes, not for every normal edit.

## 8. Gate policy

| Change scope | Required gate | Live evidence rule |
|---|---|---|
| Documentation only; `src` byte-for-byte unchanged | Documentation/matrix/link audit; preserve frozen source fingerprint | No live run required |
| Ordinary Java refactor / contract / API / artifact orchestration | Focused tests for changed area, then `mvnw.cmd clean test` | Live test normally `NOT_EXECUTED_NOT_REQUIRED` unless DB execution semantics changed |
| DBMS renderer, type mapper, expression/identifier mapping, offline validator | DBMS-focused unit/offline regression + full clean regression | Run matching directory/live execution before claiming that DBMS is live-verified |
| Directory execution harness or statement splitter | Harness-focused tests + full clean regression | Matching directory execution must run for `LIVE_TEST_EXECUTED_AND_PASSED` claim |
| Migration diff/render/repository behavior | Migration-focused tests + full clean regression | Matching `*MigrationM2LivePilotIT` required before live-migration compatibility is claimed |
| SQL Server CRUD SQL generation/execution | CRUD unit/service tests + full clean regression | `SqlServerCrudLiveIT` required before live CRUD claim |
| Db2/zOS execution-sensitive change | Db2 unit/offline tests + full clean regression | `Db2ZosLiveIT` and/or Db2 migration pilot as scope requires; if JCC/zOS unavailable record environment-unavailable, never pass |
| Corpus/parser recovery behavior | Relevant parser regression + full clean; matching offline `*IT` for large-corpus claims | No JDBC live test unless DB execution is also in scope |
| C11 release freeze | Exact-source `mvnw.cmd clean test` plus all targeted gates accumulated since last freeze | Execute live tests required by affected DBMS scopes; unavailable environments are recorded explicitly and limit claims, not silently converted to pass |

## 9. DBMS live-claim matrix

| Capability claim | Oracle | PostgreSQL | Db2/zOS | SQL Server | MySQL |
|---|---|---|---|---|---|
| Generated directory SQL executes on live DB | `OracleSqlDirectoryExecutionTest` | `PostgreSqlDirectoryExecutionTest` | `Db2ZosLiveIT` | `SqlServerDirectoryExecutionTest` / `SqlServerLiveIT` | `MySqlDirectoryExecutionTest` |
| M2 migration executes live | `OracleMigrationM2LivePilotIT` | `PostgreSqlMigrationM2LivePilotIT` | `Db2ZosMigrationM2LivePilotIT` | `SqlServerMigrationM2LivePilotIT` | `MySqlMigrationM2LivePilotIT` |
| FK historical replay live evidence | `OracleForeignKeyDirectoryExecutionIT` | no dedicated FK-only IT | no dedicated FK-only IT | covered through directory/live paths; no dedicated FK-only IT | no dedicated FK-only IT |
| CRUD live execution | no dedicated Oracle CRUD live IT in current source | feature not implemented | feature not implemented | `SqlServerCrudLiveIT` | feature not implemented |

A missing dedicated live test is a coverage gap, not evidence of failure and not permission to infer a pass.

## 10. C8.10 evidence snapshot used by C9

```text
Targeted C8.10-R1 : 43 / 0 / 0 / 0
Finished          : 2026-08-23T06:15:32-07:00

Full C8.10        : 554 / 0 / 0 / 4
Finished          : 2026-08-23T06:22:23-07:00
Main Java         : 276
Test Java         : 189
Source fingerprint: 03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba
```

C9 changes documentation only. No `src/main` or `src/test` file is modified, so the official source baseline remains C8.10 and the source fingerprint is unchanged.

## 11. C9 completion criteria

- all 189 test-source Java files appear exactly once in the CSV matrix;
- all 34 `*IT` classes are explicitly opt-in;
- 9 DB-connected `*IT` classes are separated from 25 offline/corpus `*IT` classes;
- the 4 default-Surefire directory execution tests are explicitly configuration-gated;
- live-evidence vocabulary distinguishes availability, skipped/not-executed, pass and failure;
- gate rules define normal, DBMS-specific and release-freeze requirements;
- C8.10 source fingerprint remains unchanged.

All criteria are satisfied in this C9 documentation checkpoint.
