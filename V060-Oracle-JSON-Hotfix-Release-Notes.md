# SchemaForge V0.6.0 EA Oracle / SQL Server JSON Hotfix

**Base:** `schema-forge-v4-2026-10-24-1851.zip` (2026-10-24). No DB schema migration, UI update, H2 reset, or change to the other DBMS mappings.

## Changes

1. `Dialect.integratedDeploymentGuard()` has a nonbreaking default (empty), and `OracleDialect` supplies SQL*Plus/SQLcl fail-fast directives `WHENEVER OSERROR EXIT FAILURE ROLLBACK` and `WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK` without trailing semicolons.
2. `EaGenerationOrchestrator.writeEaRunAll()` prepends the dialect guard before the first DDL of the integrated EA `run-all.sql`. Per-table SQL and the four deployment phases keep their previous ordering.
3. `JsonCheckPredicate.affirmativeJsonColumn()` identifies simple positive `<column> IS JSON` checks. Negative `IS NOT JSON` checks do not trigger JSON document storage.
4. `SqlServerDialect.sqlType(Table, Column)` selects Unicode `NVARCHAR` / `NVARCHAR(MAX)` for JSON-validated text columns, even if the source physical type was Oracle `CLOB`. Ordinary CLOB remains `VARCHAR(MAX)` to preserve the existing non-JSON mapping policy. Mapping is table-aware so migration/schema-diff paths that use the table-aware dialect hook remain consistent.
5. Regression assertions were added to the EA FCE fixture and orchestration test; `scripts/accept-ea-json-oracle-v060.cmd` is provided for Windows acceptance.
6. A **read-only** Oracle deployment prerequisite questionnaire/script is provided as `scripts/oracle/ea-fce-preflight.sql` for authorized DBAs. It is not executed by the application and is specific to FCE (`FEE3`, `TS_FEE3`, `ITS_FEE3`).

## Static verification

- The actual 11-table / 166-column / 69-CHECK FCE source fixture was parsed and regenerated using Java 21 with a standalone, dependency-limited smoke harness.
- Seven DBMS paths rendered all 11 tables. Enforceable CHECK counts remained 69 for Oracle/PostgreSQL/SQL Server/MySQL/MariaDB and 53 for Db2 LUW / Db2 z/OS. Each Db2 retained 16 `CHECK-JSON-001` unsupported notices.
- SQL Server changed exactly sixteen `VARCHAR(MAX)` storage definitions to `NVARCHAR(MAX)` for JSON columns; the single ordinary `CLOB` remained `VARCHAR(MAX)`. All six other DBMS integrated SQL bodies were byte-for-byte unchanged before and after the patch.
- Oracle integrated SQL body was unchanged. Only the outer EA run-all wrapper gains the fail-fast guard, which precedes the first SQL statement.
- The bodies of 17 relevant source test methods (FCE EA, SQL Server type mapping, and integrated SQL rendering) also passed using an offline Java 21 test runner and a temporary assertion shim; **this is not a JUnit/Maven invocation**.

## Limitations / remaining gates

- Maven/JUnit suite has NOT been run in this environment because Maven and required offline dependency cache are unavailable. `javac` smoke checks and the 17 source test methods run through a temporary assertion shim are not a substitute for Maven.
- No actual Oracle, SQL Server, PostgreSQL, MySQL, MariaDB, Db2 LUW, or Db2 z/OS execution has been performed. A production readiness claim would be premature.
- Native JSON storage and strictness differences between engines are unchanged; in particular SQL Server `ISJSON(col) = 1` accepts object/array roots by default, Oracle `IS JSON` defaults to LAX, and Db2 validity checks are not emitted.
- Oracle run-all still presupposes a provisioned user/schema and tablespaces and grants; it deliberately does not auto-provision infrastructure. SQL*Plus rollback cannot roll back already committed Oracle DDL.

## Windows installation and verification

1. Stop SchemaForge backend/UI. Back up the entire project and H2/data as required by the project's rollback policy.
2. Apply the DELTA ZIP *only* on the exact base version mentioned above, or extract the FULL ZIP into a new directory and configure it as appropriate.
3. Run `call scripts\accept-ea-json-oracle-v060.cmd` from the project root. If it fails, stop and investigate before doing any deployment.
4. Ask a DBA to review results of `scripts\oracle\ea-fce-preflight.sql` using an authorized account; ensure `COMPATIBLE >= 12.2`, schema FEE3, both tablespaces, appropriate quotas and privileges.
5. Re-run the EA import and generate fresh `run-all.sql`. Verify Oracle begins with the two `WHENEVER` lines and SQL Server JSON columns are `NVARCHAR(MAX)`.
6. Run live smoke tests against disposable schemas on the intended versions of all seven DBMSs, including Persian JSON round-trip and deliberate invalid JSON input.

**Important:** Do not treat this source patch as a previously tested build or as an automatically executed deployment.
