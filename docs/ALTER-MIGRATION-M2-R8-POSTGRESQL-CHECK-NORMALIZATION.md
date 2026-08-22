# ALTER Migration M2-R8 - PostgreSQL CHECK normalization

The PostgreSQL M2 live pilot executed the generated migration and re-read the table metadata successfully, but the final diff contained one false CHECK replacement:

```text
live-check    : id > 0 AND parent_id > 0
desired-check : (ID > 0) AND (PARENT_ID > 0)
```

PostgreSQL `pg_get_constraintdef(..., true)` can lower-case ordinary unquoted identifiers and omit redundant parentheses around individual boolean predicates. M2-R8 normalizes only those catalog-presentation differences while comparing CHECK constraints.

Safety rules:

- redundant parentheses are removed only around atomic boolean predicates;
- parentheses containing top-level `AND` or `OR` are preserved because they can alter precedence;
- single-quoted literal content/case is preserved;
- double-quoted identifier content/case is preserved;
- CREATE DDL and emitted ALTER SQL are unchanged;
- normalization is PostgreSQL-specific and does not affect Oracle, Db2 for z/OS, SQL Server, or MySQL behavior.
