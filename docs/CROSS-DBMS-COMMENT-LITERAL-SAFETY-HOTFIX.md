# Cross-DBMS Comment Literal Safety Hotfix

Date: 2026-09-14

## Scope

This hotfix makes table/column descriptions safe when rendered as executable SQL comments across all seven supported DBMSs without changing canonical text.

Covered characters and cases include:
- single quote (`'`)
- ampersand (`&`)
- backslash (`\\`)
- CR/LF and multiline text
- tab
- semicolon inside the comment text
- SQL-looking text such as `--` and `/* ... */`
- Persian/Unicode text
- percent/underscore and ordinary punctuation

NUL and unsupported C0 control characters fail closed. They are not silently removed or normalized.

## Dialect behavior

- Oracle: SQL*Plus/SQLcl client guard uses `SET DEFINE OFF` and `SET SQLBLANKLINES ON`. This prevents `&` substitution and preserves multiline literals in generated DDL/run-all/migration scripts. SchemaForge's JDBC statement parser strips these client commands before JDBC execution.
- PostgreSQL: comment text uses deterministic `E'...'` literals with explicit backslash preservation.
- SQL Server: comment text uses Unicode `N'...'` literals for `MS_Description`.
- MySQL/MariaDB: comment literals escape backslashes and quotes. Statements containing inline comments temporarily remove `NO_BACKSLASH_ESCAPES` from the session and restore the original `sql_mode` immediately afterwards.
- DB2 LUW / DB2 z/OS: SQL-standard quote doubling is used; backslash is preserved literally.

## Multiline DDL header safety

Readable table-description headers now prefix every physical line with `-- `. A multiline description can no longer leak a second line into executable SQL before `CREATE TABLE`.

## Flyway note for Oracle

Oracle migration files remain versioned/Flyway-style SQL. The emitted SQL*Plus client commands require an execution path that understands SQL*Plus commands (for Flyway this means enabling Oracle SQL*Plus support) or SchemaForge's JDBC execution path, which strips the client-only lines before JDBC execution.

## No-Guess / No-Lossy policy

This hotfix never rewrites comment meaning. Unsupported control characters fail closed rather than being guessed, replaced, or discarded.
