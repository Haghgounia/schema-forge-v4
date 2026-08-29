# DB2 LUW FK P7 R7.1

P7.1/P7.2 audit no longer hard-fails solely because a configured timestamped P6 CSV path is stale.

Resolution order:
1. Use `schemaforge.db2luw.p7.p6AuditFile` when it exists.
2. Otherwise use the latest `target/db2luw-fk-structural-audit/*/db2luw-fk-structural-audit.csv`.
3. If no P6 report exists, fail with an actionable message to rebuild P5/P6 evidence.

No parser, DDL, migration, FK rewrite, or canonical snapshot mutation is performed.
