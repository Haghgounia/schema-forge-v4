# MySQL P2-R9 - Remaining blocker / column reconciliation audit

P2-R9 is evidence-only. It reconstructs the exact post-P2-R8 blocked snapshot set and isolates residual `METADATA_COLUMN_NOT_FOUND` cases.

For those cases it searches only the exact DB2 schema/table and ranks unused MySQL-mappable exact-numeric DB2 columns by conservative name evidence:

- `STRONG_NORMALIZED_NAME_EXACT_NUMERIC`: normalized names are identical; audit candidate only.
- `REVIEW_PREFIX_NAME_EXACT_NUMERIC`: strict prefix relationship; manual/additional evidence required.
- `REVIEW_EDIT_DISTANCE_1_EXACT_NUMERIC` / `..._2_...`: typo-like candidate; review only.
- `AMBIGUOUS_*`: multiple equally ranked candidates; remains blocked.
- `NO_COLUMN_CANDIDATE`: no safe candidate from the current DB2 metadata source.

No canonical JSON and no production mapper is modified in P2-R9. The purpose is to decide whether a small, strict column-name overlay is justified for the next step, while separately reporting the full remaining blocker composition after P2-R8.
