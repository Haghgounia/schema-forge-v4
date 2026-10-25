# SchemaForge V4 — Database Auditing P1: Selected Table Cohort (Oracle FEE3)

## Purpose

This patch **extends the existing** read-only `SchemaConformanceAuditService`, selected JDBC workflow, and Conformance UI. It does not introduce a parallel audit engine and does not alter Oracle or SQL Server DDL generation, JSON constraints, Glossary hints (`GH`) or column usage counts.

The first operational cohort is the 11-table FCE model from `schemaforge-ea-20261025-114149-741.zip`, targeting `FEE3`. The actual Oracle database has **not been queried** by this patch authoring environment.

## User workflow

1. Apply the delta ZIP into the root of the **current** `D:\Projects\schema-forge-v4` repository (keep local H2 files and connection settings unchanged).
2. Execute `call scripts\accept-dbaudit-cohort-v060.cmd`, then `mvnw.cmd clean test`. Both are required to close backend acceptance.
3. In `ui`, execute `npm ci`, `npm test`, then `npm run build` (or `npm run dev` for the development interface). React UI requires a rebuild; the delta contains source, not a built `ui/dist` bundle.
4. Start the Java application using `mvnw.cmd spring-boot:run`.
5. In the **Connections** screen, select an Oracle JDBC session with read-only access to data dictionary views and the correct database service; inspect connection identity and ensure schema `FEE3` is visible.
6. In **ممیزی دیتابیس**, select **چند Table مشخص (Cohort)**, enter `FEE3`, and paste the names from `fce-fee3-cohort-tables.txt` into the list input. Confirm database target / account / eight rule families and start read-only auditing.
7. Download `schemaforge-selected-audit-tables.json` and supply it for acceptance review. Never share JDBC passwords or session tokens.

Expected table cohort:

```text
FCE_CALC_ITEM
FCE_TARIFF_VERSION
FCE_VERSION_SCOPE
FCE_CALC_RULE
FCE_TEST_CASE
FCE_AUDIT_EVENT
FCE_CHANGE_REQUEST
FCE_REF_OPERATION
FCE_REF_CODE
FCE_REF_FORMULA
FCE_REF_SOURCE
```

## REST contract

```
POST /api/v1/conformance/selected/tables
X-SchemaForge-Metadata-Connection: <ephemeral-session-token>
Content-Type: application/json

{"schema":"FEE3","tables":["FCE_CALC_ITEM","FCE_CHANGE_REQUEST", ...]}
```

The server resolves only the user-selected JDBC metadata repository; it never falls back to startup JDBC configuration. It requires 1–100 names, de-duplicates exact repeats and rejects case-ambiguous names, verifies live schema existence when authoritative, and rejects missing/inaccessible/misidentified tables rather than silently reducing the cohort. HTTP POST is used to carry a bounded JSON list; the database operations themselves are **read-only dictionary queries**.

The response has `reportContract = schemaforge-schema-conformance-selected-cohort/v1`, non-secret `target`, `auditedAt`, the exact `requestedTables`, and the ordinary `SchemaConformanceReport` with `scope=TABLES`, summary, eight families and findings. The UI independently rejects a response if the cohort size or names do not match.

## Validation gates

- **Maven targeted**: existing Conformance tests plus new cohort service/controller tests.
- **Maven complete**: clean test suite.
- **Node UI**: existing selected audit tests and cohort API/format tests (and full `npm test`).
- **Live Oracle**: confirm selected DB, `FEE3`, and `report.summary.tablesScanned = 11` and `columnsScanned = 166`; inspect **all findings**. A difference from 166 is a *finding requiring investigation*, not grounds to invent changes.
- **Evidence**: preserve downloaded JSON, timestamp, target identity, name list, and any errors. Report no more than validated.

## Scope boundary — do not mislabel the result

This phase audits **live objects against eight existing rule families**. It is **not** a complete desired-EA-vs-live physical-diff proof. The supplied `fce-fee3-canonical-inventory.json` contains counts from the **EA source model**, not live observed facts. Even zero errors/warnings in the audit result would not prove exact equality of check expressions, sequence state, detailed index options, privileges, enabled/validated constraint states or data values. Separate P2 is planned for canonical-to-catalog drift and richer Oracle dictionary evidence.

The generated Oracle `run-all.sql` must **not** be executed on the already-existing `FEE3` objects for this audit.

## Packaging / environment

Delta applies on top of `schema-forge-v4-2026-10-25-0723-2.zip` plus the accepted Glossary trailing-hints delta and its test hotfix. Baseline `data/schemaforge-glossary.mv.db`, `.mvn`, `pom.xml`, `ui/dist` and connection secrets are not included or changed. Java 21 parser verification and nine Node tests passed in the authoring environment; Maven project version 4.0.1 acceptance / full React TypeScript build could not run here because Maven dependencies / Vite client types are not locally installed. These are explicit Windows acceptance gates.
