# MySQL Final Closure Baseline

**Closure gate:** `MySqlFinalClosureTest`  
**Status after user-side gate PASS:** `CLOSED BASELINE`

## Frozen evidence

- R7.2 canonical corpus: 5,321 snapshots processed; MySQL 4,704 generated, 617 evidence-blocked, 0 generation failures.
- Current MySQL 8.4.11 historical live replay: 4,704/4,704 files selected; 12,354/12,354 executable statements succeeded; 0 statement failures; 0 actionable failures; 4,704/4,704 cleanup succeeded.
- Historical mode skipped 1,295 cross-table FK statements by design so independent historical table revisions do not require a synthetic dependency-complete schema.
- Retained M2 live pilot on MySQL 8.4.11: 14 statements; 6 column changes; 6 structural object changes; residual diff 0; data preserved; cleanup true.
- The M2 live pilot explicitly exercises PK/FK/UK/CHECK/INDEX replacement against `information_schema` metadata.

## No-guess boundary

The 617 hard blockers remain evidence blockers. This closure does not mutate recovered canonical snapshots, invent numeric precision, synthesize keys, or reinterpret missing source evidence to increase generation coverage.

## Non-mutating gate

`MySqlFinalClosureTest` reads retained evidence resources only. It does not connect to MySQL, reparse Legacy Word, regenerate canonical JSON, regenerate DDL, or modify any database state.
