# Glossary inline SQL column hints - V4.14.3-H1

## Requirement and scope

In newly generated CREATE TABLE DDL, a field whose constituent atomic vocabulary
contains a term absent from the authoritative `01_Atomic_Terms` glossary must
carry an inline, non-executable SQL `/* ... */` hint on the very same row,
**before** the rendered column name. SQL statements must retain their original
meaning, order, datatype and constraints. These are advisory comments, not
validation blockers.

Example (Oracle):

```sql
CREATE TABLE FIN.CUSTOMER_RECORD
(
  CUSTOMER_ID NUMBER NOT NULL,
  /* GLOSSARY_HINT: MISSING=ACTIVE */ IS_ACTIVE NUMBER NOT NULL,
  /* GLOSSARY_HINT: MISSING=UNKNOWN */ UNKNOWN_UNKNOWN_CODE NUMBER NOT NULL
);
```

A term receives `MISSING` only when the shared `GlossaryScanner` assigns
`NEW`. Approved atomic terms and approved phrases do not create missing hints.
Pending approval (`CANDIDATE`) and rejected (`REJECTED`) entries are **already
in the glossary** and are not labeled `MISSING`; their actual statuses still
appear in `reports/glossary/unknown-terms.csv` and `summary.json` as before.
A phrase may be matched as a single unit by the existing longest-match policy;
there is no guessed abbreviation expansion or automatic approval.

The comment's term list is ordered by occurrence, deduplicated and constrained
to safe uppercase ASCII letters, digits and underscores by the normalizer. No
raw user-entered SQL text is interpolated into the comment. When a field has no
new terms, its rendered SQL line is left byte-for-byte unchanged.

## Entry points

- Standard Word REST generation and single archive
- Legacy Word REST generation
- ZIP batch (each included Word document)
- Enterprise Architect per-table CREATE DDL and integrated RUN ALL CREATE DDL
- Seven platforms: Oracle, PostgreSQL, DB2 z/OS, DB2 LUW, SQL Server, MySQL,
  MariaDB. The source of truth is still the file-backed H2 Glossary 2.4.240.

Enable Glossary in the application process before generating the scripts:

```cmd
cd /d D:\Projects\schema-forge-v4
set "SCHEMAFORGE_GLOSSARY_ENABLED=true"
mvnw.cmd spring-boot:run
```

If the feature is disabled, or an ordinary `DdlGenerator` is used without a
request snapshot, there are **no additional SQL hints**. This is deliberate to
preserve legacy CLI callers and golden contracts. Flyway ALTER/migration and
REST CRUD procedure/package scripts are out of scope for *column declaration
row* comments and remain unchanged.

The real H2 glossary is only read for a request-scoped snapshot. No record,
status, manual approval, source Excel or database schema is changed by this
feature. Existing Artifact Ledger and Manifest report hashes cover the final
SQL files, including hints.

The supplied 19:33 source ZIP included a live `data/schemaforge-glossary.mv.db`.
Both deliverable ZIPs deliberately exclude H2 database binaries to avoid
redistributing or overwriting the local stewardship data. Keep and back up the
existing database file when applying Delta; do not replace or delete it.

## Test gates

First run targeted tests on Windows using the Maven Wrapper:

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=GlossaryDdlHintsTest,GlossaryDdlHintsApiIntegrationTest,GlossaryScannerTest,GlossaryRealSourceAcceptanceTest ^
  test
```

Then run:

```cmd
mvnw.cmd clean test
```

Local isolated verification: Java 21 compiled the actual generator and ran a
standalone test against all seven DBMS dialects and the integrated renderer.
Each hinted SQL text matched the original exactly after removing only the hint
comments. Oracle/MariaDB sanity checkers accepted the final scripts. This does
not replace the full Windows Maven / H2 integration tests.
