# SchemaForge: native TIMESTAMP fallback for timezone-unsupported DBMS

**Date:** 2026-10-20  
**Base:** `schema-forge-v4-2026-10-20-audit-timestamp-persian-names-full.zip`  
**Scope:** Standard Word, Legacy Word, ZIP Batch and EA use the shared DDL mappers.

## Requested policy

For a target DBMS lacking a native equivalent of `TIMESTAMP WITH TIME ZONE` or
`TIMESTAMP WITH LOCAL TIME ZONE`, use **TIMESTAMP** in executable DDL rather than
failing the generation or storing timestamps in a `VARCHAR` envelope.

For the dialects whose previous outputs were unsupported or were adapted to text:

| Target | Canonical `TIMESTAMP_WITH_TIME_ZONE` | Canonical `TIMESTAMP_WITH_LOCAL_TIME_ZONE` |
|---|---|---|
| MySQL | `TIMESTAMP` | `TIMESTAMP` |
| MariaDB | `TIMESTAMP` | `TIMESTAMP` |
| DB2 LUW | `TIMESTAMP` | `TIMESTAMP` |

- When the canonical precision is unspecified (including default audit fields), the
  generated SQL type is the bare token `TIMESTAMP`.
- When an explicit valid fractional-second precision exists, it is preserved as
  `TIMESTAMP(p)` (MySQL/MariaDB 0..6; DB2 LUW up to 12). Invalid precision remains an error.
- `NOT NULL`, column comments, defaults and other independently configured column
  properties retain their normal SQL behavior. Only the physical datatype changes.
- Original canonical column type and `timestampSemantics` request value remain in
  the model and request Manifest; they are **not** silently rewritten.
- Standard `TIMESTAMP` columns in MySQL/MariaDB still follow their pre-existing
  mapping to `DATETIME`; this release only changes timezone-qualified source types.
- Other mappings remain unchanged: Oracle's two variants; PostgreSQL's `WITH TIME ZONE`,
  SQL Server's `DATETIMEOFFSET`, DB2 z/OS's timezone-aware TIMESTAMP.

## Important loss-of-information disclosure

`TIMESTAMP` in these target databases does **not** retain a timezone identifier,
original offset or Oracle session-local timezone semantic. MySQL/MariaDB can perform
session-timezone conversion on TIMESTAMP read/write; DB2 LUW has no stored timezone.
Applications and migration jobs must choose a consistent timezone policy (e.g. UTC)
and convert input values before insertion. This is **not lossless portability**.

The datatype compatibility analyzer therefore produces a `WARNING` for each affected
column (and **not** a blocking ERROR), using these codes:

- `MYSQL_TIMEZONE_TIMESTAMP_FALLBACK`
- `MARIADB_TIMEZONE_TIMESTAMP_FALLBACK`
- `DB2_LUW_TIMEZONE_TIMESTAMP_FALLBACK`

Warnings appear in the generated SQL issue annotations. This release does not promise
an additional Manifest-level warning aggregation contract; review SQL warnings as
part of deployment acceptance.

No `VARCHAR(128)` timezone envelope or stale `TSTZ-TEXT` inline-comment code remains.

## Verify on Windows

Apply the delta over the specified base release. For a targeted regression:

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=MySqlTimezoneAwareTimestampAdaptationTest,MariaDbTimezoneAwareTimestampFallbackTest,Db2LuwTimezoneTimestampFallbackTest,Db2LuwTypeMapperTest,MySqlDatatypeCompatibilityAnalyzerTest,MariaDbDatatypeCompatibilityAnalyzerTest,AuditTimestampPersianDdlTest,EaGenerationOrchestratorTest ^
  test
```

Then run the full regression:

```cmd
mvnw.cmd clean test
```

Repeat the actual REST/Swagger request for both `with-time-zone` and
`with-local-time-zone` with `platform=mysql`, `platform=mariadb`, `platform=db2luw` and
`platform=all`. For each relevant column expect `TIMESTAMP`, a compatibility warning,
valid Persian/English audit comments and no timezone-rejection error for these types.
Other unrelated model validation or DBMS incompatibilities may still block output.

## Validation status

The production type mappers and real DDL generator compiled with Java 21 using a
minimal Spring annotation stub; six dialect/semantic combinations passed an isolated
Java DDL smoke test. The six focused JUnit-sourced test classes (18 test methods)
were run with a minimal locally supplied assertion runner. These checks are **not**
a substitute for actual Maven/Spring Boot acceptance on the user's Windows system.
