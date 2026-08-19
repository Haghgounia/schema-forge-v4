SchemaForge V4 - Oracle FK R2 + PostgreSQL Green + SQL Server Cleanup R2 + MySQL Logical DDL P1
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

SQL Server historical resume from file 1273 completed:
  Files selected       : 2551
  Statements executed  : 64300
  Statements succeeded : 64287
  Statements failed    : 13
  Cleanup failed       : 13

Root cause: the 26 actionable failures are 13 cleanup/create pairs. Each failed DROP
returned SQL Server 3726 because an incoming FK still referenced the target table; the
unchanged table then caused 2714 on CREATE TABLE. SQL Server Cleanup R2 removes incoming
FKs inside the disposable expected schema before historical DROP TABLE. Production DDL is
unchanged.

Sparse verification is available through:
  -Dsqlserver.sql.fileNumbers=1384,1638,1662,1740,2053,2265,2763,2774,2775,3311,3329,3342,3346

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
MySQL P1 focused suite was verified by the user on 2026-08-19:
  Tests run: 19, Failures: 0, Errors: 0, Skipped: 0

mvnw.cmd -Dtest=MySqlTypeMapperTest,MySqlIdentifierRendererTest,MySqlDialectFoundationTest,MySqlDdlGeneratorTest,ApplicationDialectSelectionTest,OutputFileNamerTest test

SQL Server Cleanup R2 regression:
  mvnw.cmd -Dtest=SqlServerDirectoryExecutionCleanupSyntaxTest test

Full regression
---------------
mvnw.cmd clean test

The prior patch expected approximately 415 ordinary tests / 3 skipped. MySQL P1 adds four
ordinary @Test cases net (one new foundation case and three DDL-generator cases), so the
expected total was approximately 419 tests / 3 skipped before SQL Server Cleanup R2. Two focused
cleanup/filter tests were added, so the current expected total is approximately 421 tests / 3 skipped.
The user-side Maven run is authoritative.

Build-environment validation performed
--------------------------------------
- Changed MySQL/core production classes: javac --release 21 OK.
- Standalone MySQL P1 generation probe: OK.
- Full Maven could not be executed in this environment because the Maven Wrapper distribution
  is not locally available and the environment cannot fetch it.

Detailed scope: docs/dialects/MYSQL-DIALECT-P1.md
