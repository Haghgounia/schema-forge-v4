# SQL Server SS-P7/P9 parity qualification

This track does not add a SchemaForge feature. It raises SQL Server to the same end-to-end qualification level already demonstrated by MariaDB and PostgreSQL.

## Existing SQL Server evidence retained

- 5,321 canonical snapshots inventoried.
- 4,703 SQL Server scripts generated and live replayed successfully; 618 mappings remained deliberately fail-closed.
- Integrated FULL FK pilot already passed.
- M2 live migration already converged with residual drift 0 and preserved data.

## SS-P7 - dependency-closed integrated cohort

`CanonicalJsonSqlServerClosedSubsetP7IT` reuses the DBMS-neutral historical selection manifest. Only `AUTO_SELECTED_UNIQUE` and `AUTO_SELECTED_EXACT_DUPLICATE` definitions enter the candidate set.

The runner removes only:

1. tables whose local SQL Server DDL cannot render or pass the existing offline validator; and
2. FK owner tables whose remaining physical FK is structurally invalid or whose rendered SQL Server child/parent datatypes are incompatible.

The runner never repairs a historical FK, invents a target, changes a datatype, or chooses a winner among unresolved historical conflicts.

Output SQL:

`sqlserver/integrated-closed-safe-subset.sqlserver.sql`

## SS-P8 - persistent live deployment

No new runner is introduced. Use the existing `SqlServerDirectoryExecutionTest` with `executionMode=FULL`, `dropBeforeCreate=true`, and explicit destructive confirmation. Cleanup occurs before CREATE; the integrated cohort remains deployed after the test for SS-P9.

## SS-P9 - catalog convergence

`SqlServerPersistentCatalogConvergenceP9IT` is read-only. It loads the exact SS-P7 selected snapshots, reads the persistent SQL Server catalog through `JdbcSqlServerMetadataRepository`, then compares live versus desired tables with `SchemaDiffEngine`.

It reports table-set drift, object counts, and every residual column/object change. The target is:

- missing expected tables = 0
- residual column changes = 0
- residual object changes = 0
- cohort converged = true

Extra unrelated tables in a shared validation schema are reported separately and are non-blocking when `failOnExtraTables=false`.

## Next gate

After SS-P9 reaches residual 0, SQL Server external DBA-created table/schema auditing is qualified with the existing `SchemaConformanceAuditService`. No new product capability is planned in this track.
