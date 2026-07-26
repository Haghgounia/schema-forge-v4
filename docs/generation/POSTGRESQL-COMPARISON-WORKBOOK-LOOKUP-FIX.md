# PostgreSQL comparison workbook lookup fix

The REST generation flow now resolves PostgreSQL schema and table names without relying on the document's letter case.

- Exact identifier matches are preferred.
- Lowercase PostgreSQL identifiers are matched for unquoted objects.
- Case-insensitive fallback is retained for legacy metadata.
- The actual schema and table names returned by PostgreSQL metadata are preserved in the canonical database table.
- REST logs now show whether each comparison table was resolved, skipped, and written.

Expected REST output for a table existing in both databases:

- `<schema>.<table>_compare_<timestamp>.oracle.xlsx`
- `<schema>.<table>_compare_<timestamp>.postgresql.xlsx`
