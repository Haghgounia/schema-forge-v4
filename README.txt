SchemaForge V4 - Logical Foreign Key and PostgreSQL UTF-8 Fix

Changes:
- /Y references generate executable foreign-key constraints.
- /N references generate [LOGICAL FOREIGN KEY] hints only.
- PostgreSQL scripts start with \encoding UTF8 and \set ON_ERROR_STOP on.
- Object summaries distinguish physical and logical foreign keys.
- SQL Server identifiers remain deterministic UPPER_SNAKE_CASE.

Verification performed in this environment:
- Java 21 compilation of DdlGenerator, PostgreSqlDialect and SqlServerDialect.
- Smoke generation for PostgreSQL and SQL Server.
- Verified logical FK is not executable.
- Verified physical FK remains executable.
- Verified SQL Server schema, table and column names are uppercase.

Run the complete Maven regression suite in the project environment before release.
