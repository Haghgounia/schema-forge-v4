# SchemaForge V4 4.0.0 - Validation Evidence

Release date: 2026-09-02

## Full regression baseline

```text
Tests    : 743
Failures : 0
Errors   : 0
Skipped  : 9
Result   : BUILD SUCCESS
```

## RC1 evidence

```text
Artifact : schema-forge-v4-4.0.0-RC1.jar
SHA-256  : 38c6b0e4fef76cdf71b2535cdf92e866365a32a322f572659ef1bec9b4bed8f5
```

RC1 API docs and live SQL Server Schema Conformance smoke passed before GA promotion.

## GA binary

```text
Artifact : schema-forge-v4-4.0.0.jar
SHA-256  : 78057619993e942f0a43fb799da754b95282f365b4f6bab09210c86233f6db57
```

## GA runtime smoke

```text
/v3/api-docs                              : PASS
/api/v1/conformance/schema                : PASS
reportContract                            : schemaforge-schema-conformance/v3
```

## Live SQL Server Schema Conformance evidence

```text
Platform        : SQLSERVER
Scope           : SCHEMA
Schema          : TSTSHMA
Tables scanned  : 2560
Columns scanned : 52937
Errors          : 0
Warnings        : 2861
Info            : 0
Findings        : 2861
```

Warning breakdown recorded in the final live run:

```text
METADATA_CONVENTION : 2486 warnings
KEY_CONSTRAINTS     : 375 warnings
Other rule families : 0 findings in this schema
```

The audit completed successfully; `compliant=false` reflected warnings under the frozen V3 contract.

## Functional artifact audit evidence

Real Word, Legacy Word, and Enterprise Architect output packages were audited for manifest/path/integrity consistency before release freeze. Latest batch validation after recent changes generated all expected six-DBMS DDL outputs with zero validation errors and no detected DDL regression.

## Deferred evidence

Db2 z/OS live execution remains environment-dependent and deferred. Offline generation/regression remains part of the validated V4 baseline.
