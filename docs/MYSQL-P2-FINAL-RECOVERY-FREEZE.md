# MySQL P2-FINAL — Evidence-backed Recovery Freeze

This stage materializes one cumulative MySQL DDL corpus from all evidence-backed P2 recovery layers while leaving persisted canonical JSON unchanged.

Applied recovery sources:

1. exact DB2 `SYSCOLUMNS` exact-numeric metadata;
2. unanimous historical canonical precision/scale evidence;
3. P2-R7 confirmed same-schema table reconciliation;
4. P2-R8 confirmed cross-schema exact-table evidence;
5. P2-R10 confirmed historical column-name corroboration.

No fuzzy, ambiguous, conflicting, over-precision, or ROWID mapping is invented. Any residual blocking issue is frozen into `mysql-final-hard-blockers_*.csv` with a hard-blocker classification. The `generated/` directory is cumulative and is the input for the final MySQL live validation stage.
