# MySQL Logical Foundation P0

> Historical foundation note. P0 has now been superseded by the activated logical DDL P1 described in [`../dialects/MYSQL-DIALECT-P1.md`](../dialects/MYSQL-DIALECT-P1.md).

Status: FOUNDATION ONLY - NOT YET REGISTERED AS A SchemaForge DatabasePlatform.

This phase intentionally adds the MySQL dialect primitives before wiring MySQL into REST, metadata comparison, artifact audit, or multi-database loops.

Implemented:
- `MySqlIdentifierRenderer`: backtick-delimited identifiers.
- `MySqlExpressionMapper`: evidence-safe mappings for common Oracle-oriented expressions (`NVL`, `SYSDATE`, `SYSTIMESTAMP`, `CURRENT_DATE()`).
- `MySqlTypeMapper`: logical mappings with explicit no-guess rejection for unsupported/lossy cases.
- `MySqlDialect`: logical identity as `AUTO_INCREMENT`, generated columns, FK action guardrails, unqualified index names.

Deliberately not implemented in P0:
- `DatabasePlatform.MYSQL` registration.
- REST/API output folders.
- MySQL `MetadataRepository` and Excel actual-vs-design comparison.
- Physical options (`ENGINE`, `ROW_FORMAT`, tablespace, charset/collation policy, etc.).
- Live MySQL directory replay.
- Logical ALTER/Flyway migration rendering.

No-guess rules in this phase:
- exact numeric values without explicit precision are rejected rather than silently mapped to MySQL's default DECIMAL definition;
- timezone-aware timestamp types are rejected because no lossless MySQL logical equivalent is established;
- Oracle `ROWID`/`UROWID` are rejected;
- sequence `NEXTVAL` is rejected except when it is attached to a canonical identity column, where the logical identity intent is rendered as `AUTO_INCREMENT`;
- `AUTO_INCREMENT` is accepted only for integer target types.

The next MySQL phase should register the platform only after this foundation regression is green, then add metadata/Excel and corpus-wide DDL validation incrementally.
