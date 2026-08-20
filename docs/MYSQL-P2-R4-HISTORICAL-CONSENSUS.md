# MySQL P2-R4 - Historical Canonical Consensus Audit

Purpose: measure whether missing MySQL exact-numeric precision/scale can be recovered from explicit definitions of the exact same schema/table/column in other persisted canonical snapshots.

Safety rules:
- persisted canonical JSON is never modified;
- DB2 SYSCOLUMNS exact evidence remains the first overlay;
- historical recovery applies only to NUMBER/NUMERIC/DECIMAL/DEC columns that still have no precision after DB2 recovery;
- every explicit historical precision/scale for the exact normalized schema/table/column must agree;
- conflicting historical definitions remain blocked;
- MySQL-unsupported consensus values remain blocked;
- no fuzzy table/column matching and no invented precision/scale.

Default minimum evidence is 1 explicit sibling snapshot. It can be raised with `-Dschemaforge.mysql.consensus.minEvidence=2` for a stricter audit.
