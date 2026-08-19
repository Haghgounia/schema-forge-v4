SchemaForge V4 - Oracle FK R2 + PostgreSQL Resume Green + MySQL Logical DDL P1
================================================================================

Baseline
--------
This patch is based on schema-forge-v4-2026-08-19-1600-fk-r2.

Verified live state before MySQL P1
-----------------------------------
Oracle FK R2:
  FK attempted       : 242
  FK succeeded       : 242
  FK failed          : 0
  Structural blocked : 142
  Dependency skipped : 169
  Cleanup failed     : 0

PostgreSQL historical resume from file 5210:
  Files selected       : 112
  Statements executed  : 2910
  Statements succeeded : 2910
  Statements failed    : 0
  Cleanup failed       : 0

SQL Server historical replay remains independent and can resume from file 1273.

MySQL Logical DDL P1
--------------------
1) MYSQL is now registered in DatabasePlatform and DialectFactory.
2) MySQL DDL is emitted by the normal CLI/API/ZIP/EA generation paths.
3) Canonical schema is rendered as a MySQL database with CREATE DATABASE IF NOT EXISTS.
4) Logical IDENTITY is rendered as AUTO_INCREMENT. The parser-generated identity backing
   sequence is suppressed only when it is used exclusively by identity columns.
5) Genuine standalone/non-identity sequences remain unsupported and fail explicitly.
6) AUTO_INCREMENT is validated as integer and leftmost-indexed. Exact decimal identities
   are mapped to signed BIGINT only when precision <= 18 and scale = 0.
7) Table/column comments are rendered inline. Generated columns and functional indexes
   use the common generator through MySQL dialect hooks.
8) Cross-DBMS TABLESPACE evidence is not translated into MySQL physical DDL in P1.
9) MySQL JDBC metadata, physical tuning, live execution, metadata CRUD and a dedicated
   offline SQL validator are intentionally deferred.

Focused regression
------------------
mvnw.cmd -Dtest=MySqlTypeMapperTest,MySqlIdentifierRendererTest,MySqlDialectFoundationTest,MySqlDdlGeneratorTest,ApplicationDialectSelectionTest,OutputFileNamerTest test

Full regression
---------------
mvnw.cmd clean test

The prior patch expected approximately 415 ordinary tests / 3 skipped. MySQL P1 adds four
ordinary @Test cases net (one new foundation case and three DDL-generator cases), so the
expected total is approximately 419 tests / 3 skipped. The user-side Maven run is authoritative.

Build-environment validation performed
--------------------------------------
- Changed MySQL/core production classes: javac --release 21 OK.
- Standalone MySQL P1 generation probe: OK.
- Full Maven could not be executed in this environment because the Maven Wrapper distribution
  is not locally available and the environment cannot fetch it.

Detailed scope: docs/dialects/MYSQL-DIALECT-P1.md
