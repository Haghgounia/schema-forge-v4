# Cross-DBMS Description / Comment Migration Hotfix

## Contract

SchemaForge treats table and column descriptions as schema state. A live database is not converged when the desired EA/Word description differs from the catalog comment, even when datatype, nullability, defaults, keys, indexes, and constraints are otherwise identical.

Covered states:

- missing live description -> add desired description
- changed live description -> replace/update with desired description
- desired description removed -> remove/clear live description
- newly added column -> persist its description in the same migration

## DBMS rendering

- Oracle: `COMMENT ON TABLE` / `COMMENT ON COLUMN`
- PostgreSQL: `COMMENT ON TABLE` / `COMMENT ON COLUMN`
- DB2 LUW: `COMMENT ON TABLE` / `COMMENT ON COLUMN`
- DB2 z/OS: `COMMENT ON TABLE` / `COMMENT ON COLUMN`
- SQL Server: idempotent `MS_Description` extended-property update/add and drop
- MySQL: `ALTER TABLE ... COMMENT = ...` and full `MODIFY COLUMN ... COMMENT ...`
- MariaDB: `ALTER TABLE ... COMMENT = ...` and full `MODIFY COLUMN ... COMMENT ...`

## CREATE ordering

For dialects whose comments are separate statements, comments are emitted before foreign keys. This preserves documentation even if a later FK statement fails. MySQL and MariaDB keep comments inline in table/column definitions.

## No-Guess policy

This change does not infer missing descriptions and does not rewrite FK or datatype semantics. The desired description must come from the canonical model (EA/Word input); live metadata is used only for comparison.
