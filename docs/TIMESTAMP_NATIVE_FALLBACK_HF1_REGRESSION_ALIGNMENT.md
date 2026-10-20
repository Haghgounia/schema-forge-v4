# Native TIMESTAMP fallback — HF1 regression contract alignment

**Date:** 2026-10-20  
**Base:** `schema-forge-v4-2026-10-20-native-timestamp-fallback-full.zip`  
**Patch scope:** two outdated *test* classes, documentation and changelog. No production source, Maven dependencies, DB schema, runtime settings or SQL mappers changed.

## Evidence from the user's Windows Maven run

- Targeted Timestamp suite: **31 tests; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS**.
- Full Maven regression: **1,001 tests; 2 failures; 0 errors; 11 skipped; BUILD FAILURE**.
- The only two failures were:
  - `MariaDbTypeMapperTest.shouldRejectMappingsThatNeedASeparateMariaDbPolicy`
  - `MySqlTypeMapperTest.shouldUseNonBlockingFallbackForUnspecifiedNumberAndRejectUnsupportedMappings`
- Both tests expected `IllegalArgumentException` for `TIMESTAMP_WITH_TIME_ZONE`, which contradicts the newly implemented user-selected fallback to `TIMESTAMP` for MySQL/MariaDB.

## Change

For both legacy Mapper tests, replace the stale timezone-rejection assertion with explicit `TIMESTAMP` assertions for `TIMESTAMP_WITH_TIME_ZONE` and `TIMESTAMP_WITH_LOCAL_TIME_ZONE`. Add `TIMESTAMP(6)` precision coverage. Preserve the rejection of `NUMBER(66,0)` in MySQL and `ROWID` in MariaDB. Existing specialized tests continue to verify SQL warnings, incompatible timezone loss, precision boundaries and absence of the older `VARCHAR(128)` adaptation.

## Acceptance gate — Windows CMD

```cmd
cd /d D:\Projects\schema-forge-v4

mvnw.cmd clean ^
  -Dtest=MySqlTypeMapperTest,MariaDbTypeMapperTest,MySqlTimezoneAwareTimestampAdaptationTest,MariaDbTimezoneAwareTimestampFallbackTest,Db2LuwTimezoneTimestampFallbackTest ^
  test

mvnw.cmd clean test
```

Expected: no failures in the two Mapper tests; all other regression outcomes should remain unchanged. The **11 skipped tests are not evidence of successful live DBMS execution**. Only promote this patch to a tested baseline after the user's complete Maven run reports `BUILD SUCCESS`.

## Independent checks available at patch creation

Java 21 classes and tests were compiled against minimal local JUnit assertions and existing compiled production classes; a small reflection-based runner executed the relevant Mapper tests. This independent check is not equivalent to full Maven/Spring execution.
