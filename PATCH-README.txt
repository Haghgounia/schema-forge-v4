SchemaForge V4 - Oracle FK R2 + PostgreSQL Green + SQL Server Cleanup R2 + MySQL P1 API Packaging R3
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

SQL Server historical resume from file 1273 completed, then R2 sparse verification passed:
  Original files selected       : 2551
  Original statements executed  : 64300
  Original statements failed    : 13
  Sparse files selected         : 13
  Sparse statements executed    : 398
  Sparse statements succeeded   : 398
  Sparse statements failed      : 0
  Incoming FK cleanup attempted : 4
  Incoming FK cleanup succeeded : 4
  Cleanup failed                : 0

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


MySQL P1 API Packaging R3
--------------------------
The pre-R2 full regression at 07:48-07:49 reported 419 tests with two failures. Both were
root-caused after MySQL registration:
1) SchemaForgeApiServiceRegressionTest expected an unquoted logical FK name even though the
   MySQL identifier renderer correctly emits backticks. The regression expectation is fixed.
2) SchemaForgeApiZipBatchTest exposed a real packaging omission: generated .mysql.sql files
   were not routed under mysql/. Batch packaging now derives platform folders and SQL suffixes
   from DatabasePlatform.values(), avoiding another hard-coded platform list.
The Phase1 CLI usage text and the large ZIP integration test were updated for MySQL as well.

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

The pre-R2 full run observed 419 tests / 3 skipped and exposed two MySQL API regression issues.
SQL Server Cleanup R2 adds two additional focused tests, so R3 expects 421 tests / 3 skipped.
The user-side Maven run is authoritative.

Build-environment validation performed
--------------------------------------
- Changed MySQL/core production classes: javac --release 21 OK.
- Standalone MySQL P1 generation probe: OK.
- Full Maven could not be executed in this environment because the Maven Wrapper distribution
  is not locally available and the environment cannot fetch it.

Detailed scope: docs/dialects/MYSQL-DIALECT-P1.md
