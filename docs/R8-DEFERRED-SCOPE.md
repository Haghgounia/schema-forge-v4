# R8 Deferred Scope

R8 freezes the current supported operational contract. The following work is intentionally deferred until after the R8 final baseline.

| ID | Deferred capability | Rationale for deferral |
|---|---|---|
| F1 | MySQL Physical Contract / physical comparison | CLOSED in MySQL Physical Closure: table ENGINE/COLLATION/ROW_FORMAT/TABLESPACE, index access method, JDBC metadata and physical comparison |
| F2 | Migration M3 | incoming foreign keys, dependency-wide destructive ordering, and physical ALTER require a new migration phase |
| F3 | Advanced Physical Model | partitioning, compression, advanced storage/index tuning should not destabilize the operational freeze |
| F4 | CRUD Parity | Oracle and SQL Server CRUD exist; PostgreSQL/Db2/MySQL parity is feature expansion |
| F5 | React / TypeScript Front-end | UI is independent of backend operational baseline acceptance |

## Change-control rule

Until R8.2 is frozen, these deferred items must not be introduced into the R8.1 RC. Any required corrective change discovered by regression or Db2 live testing must be narrowly scoped, re-tested, and produce a new RC identifier/hash.
