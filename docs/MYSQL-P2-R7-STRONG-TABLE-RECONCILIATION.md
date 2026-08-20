# MySQL P2-R7 Strong Table Reconciliation

P2-R7 is an evidence-only generation audit layered on top of P2-R4 and P2-R6.

It considers only P2-R6 `STRONG_SAME_SCHEMA_*` candidates. A candidate is not applied merely because
P2-R6 called it strong. R7 adds an independent datatype-family corroboration gate using usable DB2
SYSCOLUMNS rows from non-blocked shared columns. Any observed datatype-family conflict rejects the
candidate. Cross-schema, ambiguous, weak, and no-candidate classifications are never applied.

Accepted mappings are used only in-memory and only for missing-precision exact numeric blocker columns.
The persisted canonical JSON corpus is never changed. The recovered DB2 type must itself be a supported
exact numeric type for MySQL; otherwise the blocker remains.

Outputs include details, applied recoveries, remaining strong candidates, newly generated SQL files, and
a summary with projected corpus coverage.

Recommended properties:

- `schemaforge.mysql.strong.snapshotDir`
- `schemaforge.mysql.strong.db2SysColumnsFile`
- `schemaforge.mysql.strong.p2r4Dir`
- `schemaforge.mysql.strong.p2r6Dir`
- `schemaforge.mysql.strong.outputDir`
- `schemaforge.mysql.strong.minEvidence` (default `1`)
- `schemaforge.mysql.strong.minTypeCorroboration` (default `2`)
- `schemaforge.mysql.strong.cleanOutput` (default `true`)
- `schemaforge.mysql.strong.failOnGenerationErrors` (default `false`)
