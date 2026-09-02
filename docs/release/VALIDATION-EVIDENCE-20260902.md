# SchemaForge V4 RC1 - Validation Evidence

Date: 2026-09-02

## Final cross-contract regression

Accepted final regression evidence before RC version promotion:

```text
Tests     : 743
Failures  : 0
Errors    : 0
Skipped   : 9
Result    : BUILD SUCCESS
```

The suite covers API contracts, artifact contracts, schema conformance, metadata repositories, batching, migration/schema-diff behavior, Word/Legacy/EA parsing, diagrams, CRUD generation, object naming, NUM-001, and all six DDL dialects.

## Final executable build

Accepted package evidence before RC version promotion:

```text
Tests     : 743
Failures  : 0
Errors    : 0
Skipped   : 9
Result    : BUILD SUCCESS
Artifact  : schema-forge-v4-4.0.0-SNAPSHOT.jar
```

RC1 changes only the Maven project version from `4.0.0-SNAPSHOT` to `4.0.0-RC1` and adds release documentation. Production Java behavior is unchanged by the version promotion.

## Final E2E evidence

The executable JAR was started and queried through the real HTTP/JDBC path. Captured outputs:

```text
/v3/api-docs                         generated
Schema Conformance JSON             generated
Conformance contract                schemaforge-schema-conformance/v3
Platform                            SQLSERVER
Scope                               SCHEMA
Schema                              TSTSHMA
Tables scanned                      2560
Columns scanned                     52937
Errors                              0
Warnings                            2861
Info                                0
Findings                            2861
```

Warning breakdown from the accepted live run:

```text
METADATA_CONVENTION                 2486
KEY_CONSTRAINTS                      375
TOTAL                               2861
```

`compliant=false` is expected for this schema because the frozen contract treats warnings as non-compliant findings. The run itself completed successfully with zero ERROR findings.

## SQL Server large-schema metadata batching

A live SQL Server schema audit previously exceeded SQL Server's 2100-parameter statement limit while loading column profiles. The metadata-comparison layer now batches column-profile requests in groups of 500. The focused batching regression and the subsequent 2560-table live schema audit both passed.

## DDL regression evidence

The latest audited batch generated 72 logical tables for all six DBMSs:

```text
Oracle       72
PostgreSQL   72
Db2 z/OS     72
Db2 LUW      72
SQL Server   72
MySQL        72
Total DDL   432
```

No DDL regression was detected after the recent V4 changes.
