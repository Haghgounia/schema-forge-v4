# PostgreSQL Identifier Policy - Step 9

This step centralizes PostgreSQL identifier rendering in `PostgreSqlIdentifierRenderer`.

## Rules

- Ordinary canonical identifiers are emitted unquoted in lower case.
- PostgreSQL reserved words are emitted as double-quoted lower-case identifiers.
- Identifiers containing characters unsafe for an unquoted PostgreSQL identifier, such as `#`, are double quoted.
- Schema, table, column, constraint, sequence and index identifiers all use the same `Dialect.quote(...)` path.

## Examples

| Canonical identifier | PostgreSQL output |
|---|---|
| `CUSTOMER_ID` | `customer_id` |
| `USER` | `"user"` |
| `ACCOUNT#NO` | `"account#no"` |

## Verification

- Main `domain`, `dialect` and `generation` packages compile with Java 21.
- A standalone smoke test verifies ordinary, reserved and unsafe-character identifiers.
- `PostgreSqlIdentifierRendererTest` adds three JUnit tests to the project test suite.
