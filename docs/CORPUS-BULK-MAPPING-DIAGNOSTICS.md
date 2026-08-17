# Canonical JSON bulk datatype-mapping diagnostics

Datatype compatibility is independent from Physical Phase 1. The canonical source value remains unchanged; the target dialect may either render a documented target form with a visible warning or block generation when a lossless target cannot be chosen without inventing semantics.

## Production analyzer

`DatatypeCompatibilityAnalyzer` is the single rule source used by both production DDL generation and the canonical JSON bulk runner.

Generated SQL carries non-blocking datatype findings in the normal SchemaForge validation header and as compact inline markers on the affected column. The bulk runner records the same findings in `canonical-json-ddl-issues_*.csv`.

## Current mapping findings

- Oracle: reports NUMBER precision/scale bounding, TIMESTAMP precision bounding, and conservative character/RAW to LOB fallback already performed by the Oracle dialect. These are visible review findings; this phase does not silently hide the source condition.
- PostgreSQL: preserves explicit TIMESTAMP precision in the supported 0..6 range and reports only precision above 6, which the current renderer bounds to 6.
- SQL Server: reports DECIMAL precision above 38, temporal precision above 7, and exact NUMBER/NUMERIC/DECIMAL/DEC values with no explicit precision. The current unbounded-number mapping remains `DECIMAL(38,0)` in this phase, but it is now explicitly marked for review because precision/scale semantics are unresolved.
- Db2 for z/OS: blocks exact NUMBER/NUMERIC/DECIMAL/DEC when precision is missing or above 31; no numeric precision is invented. TIMESTAMP precision above 12 is also blocked rather than emitted as invalid Db2 DDL.

## Policy

1. Do not modify the canonical source datatype during dialect mapping.
2. Do not invent Db2 numeric precision for unbounded NUMBER.
3. A bounded or datatype-class-changing target mapping must be visible as a finding in the SQL output.
4. A target hard limit that cannot be satisfied losslessly is blocking unless an explicit project policy is approved later.
5. Physical parameters remain frozen and are not part of this workstream.
