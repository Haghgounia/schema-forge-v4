SchemaForge V4 R11.4.2 changes-only patch

Trigger:
- real Legacy Word output dropped explicit `Value = 0` defaults;
- recovery default findings were not rendered in SQL;
- SKIPPED manifest entries had no outcome reason.

Fixes:
1. Legacy `Value = ...` / `VALUE: ...` labels are conservatively normalized.
2. LEGACY_DEFAULT_DROPPED/NORMALIZED findings are rendered in SQL.
3. Manifest artifacts expose optional outcomeReason; comparison/migration/CRUD skips provide precise reasons.

No-Guess/Fail-Closed remains active. No DBMS closure is reopened.
