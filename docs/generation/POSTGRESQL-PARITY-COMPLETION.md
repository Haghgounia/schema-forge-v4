# Step 13 - PostgreSQL parity completion

This step focuses exclusively on bringing PostgreSQL DDL generation to feature parity with the existing Oracle offline generator.

Completed:
- PostgreSQL expression rendering is now applied to CHECK constraints as well as defaults and generated columns.
- PostgreSQL table tablespace syntax is rendered as `TABLESPACE <name>`.
- PostgreSQL PK and unique constraint backing-index tablespaces are rendered as `USING INDEX TABLESPACE <name>`.
- Explicit indexes continue to use PostgreSQL `TABLESPACE <name>` syntax.
- Additional Oracle-oriented type aliases are mapped: LONG_RAW, UROWID, ROWID, TIMESTAMP_WITH_TIME_ZONE and TIMESTAMP_WITH_LOCAL_TIME_ZONE.
- Tests added for CHECK expression conversion, table tablespace, PK/UK index tablespace and type aliases.

Verification:
- Java 21 compilation of domain, dialect and generator packages succeeded.
- Standalone PostgreSQL parity smoke test succeeded.
