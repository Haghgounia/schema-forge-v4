# DB2 LUW FK Model Resolution P7.1 / P7.2

`Db2LuwFkModelResolutionP7IT` is an evidence-only audit. It does not modify parser output,
canonical snapshots, or generated SQL.

Evidence inputs:

- P6 structural-audit CSV
- DB2 LUW generated SQL corpus/history
- recovered canonical snapshot corpus
- offline `SYSIBM.SYSCOLUMNS` export

P7.1 classifications:

- `CONFIRMED_RENAME`
- `POSSIBLE_ALIAS`
- `UNRESOLVED`

P7.2 classifications:

- `CANONICAL_PRESENT_GENERATION_BLOCKED`
- `EXTERNAL_OR_SHARED_DEPENDENCY`
- `STALE_LEGACY_REFERENCE`
- `CANONICAL_ABSENT`

No classification causes an automatic FK rewrite.
