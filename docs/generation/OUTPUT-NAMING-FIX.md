# Latest Output Naming Fix

Applied directly to `schema-forge-v4-2026-07-25-1205`.

All generated artifacts now use the centralized `OutputFileNamer` policy:

`<base-name>_yyyyMMdd_HHmmss_SSS.<extension>`

Covered outputs:
- Application JSON output
- Application SQL output
- Word integration-test JSON/SQL output
- Phase-1 pipeline JSON output
- Oracle generator SQL output
- Word regression JSON/SQL output
- Word regression CSV summary

JSON and SQL produced in the same generation run share one timestamp.
