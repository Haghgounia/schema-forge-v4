# MySQL P2-R3 - Evidence Overlay Generation

P2-R3 measures the practical effect of exact DB2 `SYSIBM.SYSCOLUMNS` evidence before any recovery policy is promoted into production.

## Safety boundary

- Persisted canonical JSON is never modified.
- Recovery is MySQL-only and in-memory.
- Only canonical `NUMBER` / `NUMERIC` / `DECIMAL` / `DEC` columns with **missing precision** are eligible.
- Recovery requires an exact case-insensitive `schema + table + column` catalog match.
- Metadata must itself be an exact numeric type and must map losslessly through `MySqlTypeMapper`.
- Explicit canonical precision conflicts, missing tables/columns, ambiguous/incomplete metadata, ROWID, and unsupported metadata remain blocked.

## Output

The runner writes generated MySQL DDL plus reports for applied recoveries, remaining blockers, generation failures, and net newly-unblocked snapshots.
