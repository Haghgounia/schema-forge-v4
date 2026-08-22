# ALTER/Migration M2-R7 - PostgreSQL live pilot

This cumulative V4 stage adds real PostgreSQL validation for ALTER/Migration M2.

The pilot verifies:

- complete CREATE DDL is generated even when the live table already exists;
- column changes plus PK/FK/UK/CHECK/INDEX changes are detected;
- safe output blocks destructive operations by default;
- explicitly confirmed migration SQL executes against PostgreSQL;
- existing test data survives the migration;
- re-read PostgreSQL metadata has zero residual M2 differences;
- fixed pilot tables are removed during cleanup.

PostgreSQL `pg_index` also exposes indexes that physically back PRIMARY KEY and UNIQUE constraints. Those enforcement indexes are now excluded from the standalone canonical index list because PK/UK are already represented as constraints with their backing-index physical metadata.
