# Oracle Offline DDL Completion

This revision closes the single-Word Oracle script phase.

Implemented:

- one Word specification produces exactly one complete `.sql` file
- JSON remains available as the structured companion output
- no database/JDBC lookup is used
- sequence output includes `START WITH`, `INCREMENT BY`, min/max, cache, cycle, and `NOORDER`
- Word `IDENTITY` columns use the generated sequence `NEXTVAL` default when the parser supplies it, avoiding an unused sequence
- native Oracle identity remains supported when no sequence default exists
- executable SQL*Plus header and fail-fast error handling
- table, constraints, indexes, comments, grants, warnings, summary, and footer remain in the same SQL file
- seven real Word documents are included in the regression resources
- integration and regression tests now also create and verify `.sql` outputs

The core DDL classes were compiled locally with Java 21 and a generated schema was rendered successfully. Full Maven tests could not run in this environment because Maven Wrapper download access was unavailable.
