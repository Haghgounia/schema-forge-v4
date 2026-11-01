# SchemaForge V4 — COL DDL Portability Fix R1 (2026-11-01)

## Scope and baseline

- Source baseline: `schema-forge-v4-2026-11-01-0919.zip`; Maven application `4.0.1`.
- Real regression corpus: `Collateral_EA_Model_Candidate.xml` (EA XMI 1.1 / UML 1.3), schema `COL`.
- No modification to React UI, JDBC configuration, Oracle schema, generated files already in repository, or database contents.
- Extract changed files into the existing repository; **regenerate** the ZIP from SchemaForge rather than reusing the previous output.

## Corrections

1. **EA XMI 1.1 timestamp precision.** EA exports TIMESTAMP(6) as `type=TIMESTAMP,length=6,precision=0`. Parsing now preserves that as canonical temporal precision `6`. Inline `TIMESTAMP(6)` and explicit `precision=6` are still supported. Conflicting length/precision values fail closed.
2. **MySQL / MariaDB timestamp defaults.** For a TIMESTAMP(6) source with `SYSTIMESTAMP`, the DDL now uses `DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)` (unless another explicit source default is present). Unqualified/second-precision sources remain unchanged.
3. **SQL Server legacy REGEXP_LIKE checks.** Known strict ASCII identifier patterns are converted to `PATINDEX` with `Latin1_General_100_BIN2` plus a byte-accurate length gate using `DATALENGTH(CONVERT(NVARCHAR(MAX), ...))`, including trailing spaces. These patterns do not require SQL Server 2025 / compatibility level 170. Unknown regex syntax/patterns fail closed and are **not** silently emitted.
4. **PostgreSQL regex compatibility.** The same reviewed source patterns are converted to POSIX `~` with `COLLATE "C"` so `REGEXP_LIKE` is not required; unknown patterns fail closed.

Approved source regex patterns:

- `^[A-Z]{3}$`
- `^[0-9]{3}$`
- `^[A-Z][A-Z0-9_]*$`
- `^[A-Z0-9_\\-]{1,30}$` (also accepts equivalent hyphen literal spelling without backslash)

## Off-line verification performed in the authoring container

- Java 21 compiled the changed production source graph (using one isolated Spring `@ConfigurationProperties` annotation stub because Maven dependencies were unavailable). **This is not a full Maven build.**
- The seven new regression cases executed using a minimal local JUnit-compatible assertion runner: **7 PASS / 0 FAIL**. A formal `mvnw.cmd ... test` run on the user's machine remains a release gate.
- Parsed the entire original Collateral XMI: **26 tables, 373 columns, 58 TIMESTAMP(6)**.
- Generated SQL for Oracle, PostgreSQL, SQL Server, MySQL, MariaDB, Db2 LUW, Db2 z/OS (in-memory, no DB): each produced **26 CREATE TABLE / 161 CHECK / 41 FK**.
- MySQL: **58 DATETIME(6)**, MariaDB: **58 DATETIME(6)**; `CURRENT_TIMESTAMP(6)` source defaults present.
- SQL Server: **17 PATINDEX** checks and **0 raw REGEXP_LIKE**; PostgreSQL: **17 POSIX ~** checks and **0 raw REGEXP_LIKE**.
- Confirmed unknown regex rejects instead of emitting unsupported DDL.
- Compatibility smoke: IAM XMI 2.1 **37 tables / 300 columns**, FCE XMI **11 tables / 166 columns**.

## Remaining gates and limitations

- Formal Windows Maven suite not run in authoring environment (Maven binary/wrapper download unavailable).
- No live execution of DDL on any DBMS was performed. `REGEXP_LIKE` conversion is regression-tested against reviewed ASCII patterns, not against a live SQL Server or PostgreSQL instance.
- `TRIM(x) IS NOT NULL` semantic parity, duplicate independent index suppression evidence, and glossary warning count consistency are **not** changed in this R1 patch.
- This patch does not alter approved-schema gating or provide a database-target version configuration UI.
- The Collateral model is marked `Proposed` and still requires governance approval before production deployment.
- Database deploys and migration writes must not be authorized merely on the basis of static PASS results.

## Install and local acceptance (Windows 10)

1. Back up / commit local changes as appropriate.
2. Extract `schemaforge-v4-collateral-ddl-portability-r1-20261101-delta.zip` into `D:\Projects\schema-forge-v4` with overwrite enabled; existing UI files remain untouched.
3. In Command Prompt run once:
   ```cmd
   cd /d D:\Projects\schema-forge-v4
   call scripts\accept-collateral-portability-20261101.cmd
   ```
4. Restart the backend, regenerate Schema `COL` for all targets, and share the new EA ZIP for final structural acceptance.

## Official reference for target behavior

- MySQL 8.4 datetime precision: https://dev.mysql.com/doc/refman/8.4/en/date-and-time-type-syntax.html
- MariaDB DATETIME fractional precision: https://mariadb.com/docs/server/reference/data-types/date-and-time-data-types/datetime
- SQL Server 2025 `REGEXP_LIKE` compatibility level 170: https://learn.microsoft.com/en-us/sql/t-sql/functions/regexp-like-transact-sql
