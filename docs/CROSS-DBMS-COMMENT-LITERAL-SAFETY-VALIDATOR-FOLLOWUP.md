# Cross-DBMS Comment Literal Safety - Validator Follow-up

## Trigger
The full regression suite after the comment-literal-safety hotfix exposed a contract mismatch: MariaDB DDL generation intentionally emits three scoped `sql_mode` guard statements around comment-bearing statements, while `MariaDbDdlSanityChecker` still rejected every `SET` statement as outside the allowed subset.

This caused cascading generation, archive, manifest and EA tests to fail before their own assertions were reached.

A separate PostgreSQL regression assertion still expected the previous standard string literal for table comments even though the new safety contract deliberately renders deterministic `E'...'` literals.

## Fix
- `MariaDbDdlSanityChecker` now recognizes only the three exact SchemaForge guard shapes:
  1. capture current `@@SESSION.sql_mode`
  2. remove `NO_BACKSLASH_ESCAPES` for the guarded comment statement
  3. restore the captured mode
- Arbitrary `SET` statements remain invalid and fail closed.
- `PostgreSqlDdlGeneratorTest` now expects the intentional `E'...'` table-comment literal.
- Adds regression coverage proving exact guard acceptance and arbitrary SET rejection.

## Non-changes
No production comment text, escaping behavior, DBMS mapping, FK logic, canonical selection, or migration safety behavior is changed by this follow-up.
