SchemaForge EA run-all regression-test contract hotfix
=====================================================
This delta changes tests only. Production code is unchanged.

Reason:
EA run-all was intentionally changed from per-table execution (:r/include/source) to integrated SQL.
SchemaForgeEaPerTableOutputTest still asserted the old SQL Server ':r' directive.

New assertion:
- per-table SQL files remain listed as reference-only comments
- SQL Server run-all must NOT execute them via ':r'
- ordering evidence remains checked
