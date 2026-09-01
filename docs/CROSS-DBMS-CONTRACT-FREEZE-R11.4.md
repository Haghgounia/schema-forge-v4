# R11.4 Cross-DBMS Semantic Contract Freeze

## Purpose

R11.4 freezes the DBMS-neutral semantic contract after the six-platform generation tracks reached their accepted closure state and the R11.3.2 full regression returned GREEN.

This is a **contract-only** release step. It does not change production DDL generation, canonical parsing, datatype recovery, physical mapping, migration rendering, or database state.

## Registered platform order

The deterministic platform order is frozen as:

1. `oracle`
2. `postgresql`
3. `db2zos`
4. `db2luw`
5. `sqlserver`
6. `mysql`

`DialectFactory` must continue to resolve every registered platform to a production dialect.

## Frozen semantic invariants

The `CrossDbmsContractFreezeTest` protects these invariants for every registered platform:

- canonical tables remain executable DDL inputs without changing the canonical model;
- PK, UK, CHECK, FK, FK target reference, standalone index, comments and grants remain represented;
- required columns with defaults retain both `NOT NULL` and `DEFAULT`; clause ordering remains a dialect responsibility;
- generation is deterministic when canonical input and clock are fixed;
- `MISSING_DATA_TYPE` is invalid and DDL generation fails closed on all six platforms;
- generated/supporting object names are deterministic and stay within the target DBMS identifier limit;
- identical live/desired M2 input produces zero residual diff;
- a possible column rename is never guessed: it remains ADD + destructive DROP;
- destructive migration SQL remains commented/review-only under `MigrationRenderOptions.safeDefaults()`.

## Dialect boundaries

Vendor syntax remains owned by the dialect layer. The freeze deliberately does not force identical SQL text across DBMSs. Differences such as identity implementation, sequence support, identifier quoting, referential-action support, tablespace/filegroup syntax, generated-column grammar and physical clauses remain dialect-specific.

MySQL remains the only registered dialect without standalone sequence support; identity semantics remain supported.

## No-Guess / Fail-Closed policy

The freeze does not introduce fallback datatypes, synthetic PK/UK/FK objects, inferred column renames, or guessed physical infrastructure. Missing evidence must remain visible as a blocker/review item rather than being silently repaired.

## Baseline entering R11.4

The accepted R11.3.2 full regression is:

- Tests run: `681`
- Failures: `0`
- Errors: `0`
- Skipped: `9`
- Result: `BUILD SUCCESS`

DBMS closure state:

- Oracle: `CLOSED BASELINE`
- PostgreSQL: `CLOSED BASELINE`
- DB2 LUW: `CLOSED BASELINE`
- SQL Server: `CLOSED BASELINE`
- MySQL: `CLOSED BASELINE`
- DB2 z/OS: `OFFLINE CLOSED / LIVE DEFERRED`

## Deferred / not part of R11.4

R11.4 does not claim evidence that does not exist. The following remain outside this freeze:

- DB2 z/OS live execution;
- bare `WITH DEFAULT` semantic recovery;
- `FOR BIT DATA` recovery;
- source/catalog enforcing PK/UK index-name reuse;
- Artifact/API/ZIP contract freeze.

The next project step is **Artifact / API / ZIP Contract Freeze**.
