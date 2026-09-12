# MariaDB M8 - Live Migration and Convergence

## Objective

M8 closes the MariaDB migration path against a real MariaDB server. The gate validates the complete runtime chain rather than only offline rendering:

`baseline -> JDBC metadata -> SchemaDiffEngine -> safe/confirmed migration -> execute -> JDBC metadata -> zero residual diff`

## Safety boundary

The live gate is opt-in. It refuses execution unless:

- `schemaforge.mariadb.migration.jdbc.url` and `schemaforge.mariadb.migration.jdbc.user` are supplied;
- `schemaforge.mariadb.migration.confirmDestructive=true` is explicit;
- the disposable database name starts with `SCHEMAFORGE_`.

Cleanup is enabled by default.

## Coverage

`MariaDbMigrationM8LiveIT` covers:

- MariaDB JDBC driver/product verification;
- disposable database create/drop;
- live `JdbcMariaDbMetadataRepository` reads;
- CREATE-output independence from live-table existence;
- column add/drop/type/nullability/default drift;
- PK, UK, CHECK, INDEX and FK replacement;
- safe rendering with destructive statements blocked;
- explicitly confirmed migration execution;
- post-execution catalog re-read;
- `SchemaDiffEngine` convergence to zero residual changes;
- preservation of non-dropped row data;
- persisted diagnostic artifacts under `target/mariadb-migration-m8-live`.

## Gate

Example for the project-local MariaDB port used by SchemaForge metadata defaults:

```cmd
set "MARIADB_JDBC_PASSWORD=<password>"

mvnw.cmd ^
  -Dtest=MariaDbMigrationM8LiveIT ^
  -Dschemaforge.mariadb.migration.jdbc.url="jdbc:mariadb://localhost:3308/" ^
  -Dschemaforge.mariadb.migration.jdbc.user=root ^
  -Dschemaforge.mariadb.migration.confirmDestructive=true ^
  test
```

When the password is empty, omit the `set` command. A different port/user/password can be supplied without changing the test.

## Closure criterion

MariaDB M8 is closed only when the live test reports:

- `Residual changes : 0`
- `Data preserved   : true`
- Maven `BUILD SUCCESS`

Any non-zero residual diff remains a production defect or metadata-equivalence defect to fix before MariaDB migration support is declared live-validated.
