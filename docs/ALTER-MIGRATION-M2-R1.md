# ALTER Migration M2-R1

This revision aligns two renderer regression assertions with the established M2 output contract.

1. The rename-safety hint is phase-neutral in M2: `SchemaForge never infers column renames`.
2. SQL Server safe ordinary identifiers are intentionally emitted without brackets by `SqlServerIdentifierRenderer`, so an index drop is rendered as `DROP INDEX IX_CUSTOMER_STATUS ON APP.CUSTOMER;`.

No CREATE/ALTER behavior, risk classification, or structural migration semantics were relaxed. CREATE generation remains independent and always follows the existing SchemaForge flow; ALTER/Flyway output remains an additional artifact when a live table exists and differences are detected.
