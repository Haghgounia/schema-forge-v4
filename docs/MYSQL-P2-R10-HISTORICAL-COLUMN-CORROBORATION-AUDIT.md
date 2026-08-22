# MySQL P2-R10 - Historical column-name corroboration audit

P2-R10 follows the P2-R9 result where no normalized-name candidate was strong enough for automatic recovery.
It evaluates only the P2-R9 `REVIEW_*` typo/prefix candidates and requires an independent historical canonical
snapshot before any future recovery can be considered.

A review candidate is classified as `CONFIRMED_HISTORICAL_CANDIDATE_NAME` only when:

- the exact DB2 candidate column name appears in another snapshot of the same canonical schema/table;
- the historical candidate stays in the exact-numeric datatype family;
- the requested/misspelled name does not have a competing historical occurrence; and
- the requested and candidate names never coexist historically.

Similarity alone is never accepted. Historical rename ambiguity, coexistence, and type-family conflicts remain blocked.
P2-R10 is audit-only: it does not mutate canonical JSON, production MySQL mapping, or generated DDL.
