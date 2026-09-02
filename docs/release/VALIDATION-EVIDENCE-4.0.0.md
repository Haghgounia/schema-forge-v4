# SchemaForge V4 4.0.0 - Validation Evidence

Date: 2026-09-02

## Pre-GA RC1 evidence

### Full regression

```text
Tests    : 743
Failures : 0
Errors   : 0
Skipped  : 9
Result   : BUILD SUCCESS
```

### Release candidate build

```text
Artifact : target\schema-forge-v4-4.0.0-RC1.jar
Build    : SUCCESS
SHA-256  : 38c6b0e4fef76cdf71b2535cdf92e866365a32a322f572659ef1bec9b4bed8f5
```

### Live SQL Server Schema Conformance Audit

```text
Platform       : SQLSERVER
Scope          : SCHEMA
Schema         : TSTSHMA
Report contract: schemaforge-schema-conformance/v3
Tables scanned : 2560
Columns scanned: 52937
Errors         : 0
Warnings       : 2861
```

The non-compliant result is caused by warnings under the frozen conformance contract, not by execution failure.

### RC1 API smoke

```text
/v3/api-docs                                 : PASS
rc1-api-docs.json                            : 8,694 bytes
/api/v1/conformance/schema (SQL Server)      : PASS
schemaforge-schema-conformance/v3            : PASS
```

## GA acceptance gate

The GA binary must be built from version `4.0.0` with no production Java change from the validated RC1 baseline.

Required final evidence:

```text
mvnw.cmd clean package                        -> BUILD SUCCESS
Tests                                          -> 743, 0 failures, 0 errors
Target artifact                                -> schema-forge-v4-4.0.0.jar
GA JAR SHA-256                                 -> record after build
/v3/api-docs                                   -> PASS
Schema Conformance SQL Server smoke            -> PASS
Report contract                                -> schemaforge-schema-conformance/v3
```
