# MySQL P3 - Live Historical Execution

Purpose: execute already evidence-safe `.mysql.sql` artifacts through JDBC against a disposable MySQL validation server.

## Safety and scope

- Default execution mode is `HISTORICAL`.
- Physical cross-table foreign keys and `GRANT` statements are skipped in historical mode.
- `CREATE TABLE`, PK, UK, CHECK, indexes, generated columns, defaults and `AUTO_INCREMENT` are executed.
- `dropBeforeCreate=true` requires `confirmDestructive=true` and an exact `expectedDatabase`.
- The runner refuses to drop an unqualified table or a table outside the expected database.

## Reports

`target/mysql-sql-execution-report/<timestamp>/`

- `mysql-sql-execution-summary.txt`
- `mysql-sql-execution-files.csv`
- `mysql-sql-execution-errors.csv`

## JDBC dependency

The project uses the official Maven artifact `com.mysql:mysql-connector-j`, with version managed by the Spring Boot parent.
