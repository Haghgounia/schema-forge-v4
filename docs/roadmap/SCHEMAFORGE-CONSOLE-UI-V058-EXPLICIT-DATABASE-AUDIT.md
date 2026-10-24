# SchemaForge Console V0.5.8 — Explicit Target Database Auditing

Date: 2026-10-24. Baseline: **V0.5.7 FULL**. Gate: **UI-C12 — Auditing target and rule transparency**.

## User problem

The previous `/#/conformance` screen offered `Schema`, `Table` and one or more DBMSs without
showing the actual JDBC server/catalog, login account and the rules executed. With no active
browser metadata profile the screen defaulted to Oracle; the previous API could select
startup-configured JDBC repositories. In multi-DBMS environments, this was not a sufficiently
explicit target-selection contract.

## Intended workflow

1. In `/#/connections`, choose exactly one of Oracle, PostgreSQL, SQL Server, MySQL, MariaDB,
   DB2 LUW or DB2 z/OS. Supply the JDBC URL, username and password and **activate only after
   metadata probing succeeds**. The selected connection is per-browser, opaque-token backed,
   valid for up to 30 minutes, and is never installed globally. The password is not persisted.
2. In `/#/conformance`, with **no selected connection**, the page **does not audit anything**:
   an explicit connection-selection action is shown instead. There is **no default Oracle**.
3. The view calls `GET /api/v1/conformance/selected/plan` with
   `X-SchemaForge-Metadata-Connection`. The server validates the token and returns the
   selected DBMS, sanitized JDBC target (URL query/properties removed), JDBC catalog when
   reported, database product/version, JDBC username, previously verified/default schema,
   expiry and **the eight actual rule families**.
4. Choose a table or a complete schema. Inspect the final JDBC target, selected schema/table,
   and rules, then explicitly acknowledge the target before running.
5. Run `GET /api/v1/conformance/selected/table?schema=...&table=...` or
   `GET /api/v1/conformance/selected/schema?schema=...`. **No platform query is accepted or
   needed**; the DBMS and repository are determined exclusively by the selected server token.
   The selected repository is passed explicitly into the audit service; the configured default
   resolver cannot be a fallback. If the token is missing/expired, audit fails closed.
6. Receive a read-only report wrapping the existing v3 conformance findings **together with the
   identity of the selected JDBC target and audit timestamp**. JSON download preserves both.
7. A DBA-created object can be audited if its table/catalog metadata is visible to the JDBC
   account. No Word/EA/previous SchemaForge generation is required.

## Audit scope: eight existing, executed rule families

| Family | Checks currently implemented |
| --- | --- |
| STRUCTURAL | `SpecificationValidator` checks on live-catalog-derived Canonical objects |
| METADATA_CONVENTION | Existing `MetadataComparisonValidator` structural/metadata rules |
| DATATYPE_COMPATIBILITY | Vendor dialect's datatype compatibility rules |
| CONSTRAINT_REFERENCES | CHECK constraints referring to unknown table columns |
| KEY_CONSTRAINTS | Missing primary keys and reported nullable PK columns |
| REFERENTIAL_INTEGRITY | Referenced FK columns exist; referenced keys are unique |
| INDEX_COVERAGE | Whether FK columns have suitable leading-index coverage (INFO) |
| PHYSICAL_NAMING | DBMS-aware physical object name validation |

This is **structure and catalog metadata auditing**, not row-level validation, data-quality
profiling, security/permissions assessment, vulnerability scanning, data mutation, DB tuning,
or execution of DDL. The audit is read-only, but requires SELECT privilege on relevant catalog
views. Results cover only metadata visible to the account. A successful connection probe is
**not** proof that the account can read every table in a schema.

## REST and compatibility

New optional endpoints:

- `GET /api/v1/conformance/selected/plan` — target/rules; no table/schema SQL yet.
- `GET /api/v1/conformance/selected/table?schema=S&table=T` — one live table.
- `GET /api/v1/conformance/selected/schema?schema=S` — visible tables in live schema.

All three require `X-SchemaForge-Metadata-Connection` (no query-string password/token);
missing token returns 400, expired/invalid 401. Results never include passwords or full JDBC
parameters. Former `/api/v1/conformance/table` and `/schema` routes keep their response contracts
for programmatic backward compatibility; **the React UI no longer calls them**. Future RBAC may
separately address programmatic access to startup-configured connections.

The existing `/api/v1/connections/activate`/`active` response gets **additive** nonsecret
`jdbcTarget`, `databaseName`, `username`, `databaseProduct`, `databaseVersion` fields.
`databaseName` may be empty where a driver does not support `Connection.getCatalog()`; the
sanitized JDBC URL/service identifier is then the displayed target identity. For SQL Server,
`databaseName=...` is also safely extracted from JDBC URL properties as a fallback without
returning arbitrary connection properties or secrets.

No changes to Canonical Model, DDL generators, seven vendor-specific repository SQL classes,
H2 Glossary schema, or Legacy abbreviation logic. Batch-level JDBC failure caching/preflight
is a **separate open performance topic** and is not changed by this release.

## Acceptance gates

| Gate | Requirement | Current status |
| --- | --- | --- |
| UI-C12-1 | React cannot audit without explicitly selected connection | Node test PASS (56 locally executable tests) |
| UI-C12-2 | Plan shows DBMS, safe JDBC target, catalog/login, expiration and 8 families | Code + Node test PASS; Windows visual review OPEN |
| UI-C12-3 | Server-selected audit cannot fall back to global configured repository | Java regression added; Windows Maven OPEN |
| UI-C12-4 | Missing/invalid token rejected, selected platform cannot be overridden | Java + Node regressions added; Windows Maven OPEN |
| UI-C12-5 | Report includes selected target/timestamp and original findings | Java + Node regressions added; Windows review OPEN |
| UI-C12-6 | All prior Java/Frontend regressions, seven DBMS DDL, Vite lazy budget | Windows acceptance OPEN |
| UI-C12-7 | Live one-table and schema audit with a known read-only test account | OPEN; do not run against a production database for acceptance |

## Windows CMD acceptance

```cmd
cd /d D:\Projects\schema-forge-v4
call scripts\accept-database-audit-v058.cmd
cd ui
npm run dev
```

In Chrome: connect to a dedicated **read-only test** DBMS with a verified schema, then open
`/#/conformance`. Verify the target is exact, the eight families are displayed, confirmation
is required, and a table/schema audit returns a JSON report containing the same selected
target. Disconnect and ensure no audit can run, even though startup JDBC configuration may
be present. Test with another DBMS and verify there is no cross-connection fallback.

**Current local evidence:** 56/56 Node tests that do not require npm dependencies PASS;
11 changed Java files parse with Java 21 and 5 changed TypeScript files parse with the
TypeScript compiler. The remaining `jszip`-dependent Node tests, Maven suite and real Vite
build cannot run here because npm dependencies and Maven are absent; their Windows
acceptance remains OPEN.
