# R8 Known Limitations and Accepted Blockers

This document distinguishes accepted source/evidence/DBMS blockers from implementation regressions. No entry authorizes guessed canonical metadata.

## New Word corpus blockers

1. **Unresolved source datatype — 3 documents**  
   `MISSING_DATA_TYPE` is intentionally fail-closed on all five DBMS. The source document must provide an exact datatype before executable DDL is generated.

2. **`NUMBER(510)` — 1 document**  
   The canonical source says precision 510. Oracle, Db2 z/OS, SQL Server, and MySQL reject this target mapping. PostgreSQL can render it. SchemaForge does not clamp or guess a corrected precision.

3. **Multiple identity columns — 9 documents, MySQL blocker**  
   MySQL permits only one `AUTO_INCREMENT` column per table. SchemaForge does not silently discard or reinterpret identity markers.

4. **Non-identity `SEQ_*.NEXTVAL` default — 2 documents, MySQL blocker**  
   MySQL has no equivalent sequence-nextval default in this contract. SchemaForge does not auto-promote the column to `AUTO_INCREMENT` without canonical identity evidence.

5. **Identity `NUMBER(20)` — 1 document, MySQL blocker**  
   No currently accepted MySQL integer identity mapping preserves the complete declared range losslessly. SchemaForge blocks instead of narrowing.

The strict R7.3 baseline contains 31 target failures across these known categories. A generic or unclassified generation failure is not accepted.

## Legacy evidence blockers

R7.1 leaves 617 canonical snapshots hard-blocked because exact evidence is insufficient or the declared physical/logical construct is unsupported. The dominant residuals are exact numeric precision gaps. Original canonical snapshots are not mutated to improve coverage.

```text
MYSQL_EXACT_NUMERIC_PRECISION_REQUIRED : 2448 occurrences
MYSQL_DECIMAL_PRECISION_UNSUPPORTED    : 24 occurrences
MYSQL_ROWID_UNSUPPORTED                : 1 occurrence
```

Hard-blocked snapshots remain evidence gaps, not parser facts to infer.

## Live-validation limitation

Db2 z/OS live acceptance has not been executed because a live Db2 z/OS environment is not currently available. This is recorded as `PENDING_ENVIRONMENT`, not PASS and not FAIL.

## Explicitly non-blocking deferred capabilities

The following are outside the R8 operational freeze scope and do not convert the accepted R7 evidence into failures:

- MySQL physical contract/comparison;
- Migration M3 incoming-FK and advanced physical ALTER behavior;
- advanced physical model features;
- CRUD parity for PostgreSQL, Db2 z/OS, and MySQL;
- React/TypeScript front-end.
