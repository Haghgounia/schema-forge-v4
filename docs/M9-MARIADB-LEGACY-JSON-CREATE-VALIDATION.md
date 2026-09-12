# M9 - MariaDB CREATE validation from Legacy canonical JSON

## Goal

Validate real MariaDB table creation from the canonical `*.schema.json` snapshots previously extracted
from Legacy Word documents without reopening Word and without mutating the canonical corpus.

The M9 flow intentionally has two separate stages:

1. `CanonicalJsonDirectoryToDdlIT` reads canonical JSON and generates MariaDB DDL.
2. `MariaDbDirectoryExecutionTest` executes the generated `.mariadb.sql` files against a disposable
   MariaDB server and writes durable execution evidence.

## Historical corpus contract

The Legacy corpus is historical and may contain several versions of the same qualified table. It is
therefore **not** a valid integrated final-schema input. SchemaForge does not silently choose the
latest file or a preferred version.

`HISTORICAL` execution mode validates every generated table script independently. It skips physical
cross-table FK statements and GRANT statements because the corpus is not dependency-complete as one
operational version set. When `dropBeforeCreate=true`, the table defined by each script is dropped
before that script is executed. If several historical files define the same table, every definition
is tested and the last tested definition remains on the disposable server.

A final integrated MariaDB schema requires a separately approved one-version-per-table canonical
input and must pass the integrated FK/deployment gates.

## Stage A - generate MariaDB DDL from canonical JSON

Example using the original Legacy canonical snapshots:

```bat
cd /d D:\Projects\schema-forge-v4

mvnw.cmd ^
  -Dtest=CanonicalJsonDirectoryToDdlIT ^
  -Dschemaforge.snapshot.ddl.inputDir="D:\Sample-Docs-Scripts\Legacy\SchemaForgeCanonicalJson-20260818" ^
  -Dschemaforge.snapshot.ddl.outputDir="D:\Sample-Docs-Scripts\SchemaForge-MariaDB-M9-DDL" ^
  -Dschemaforge.snapshot.ddl.platforms=mariadb ^
  -Dschemaforge.snapshot.ddl.expectedMinSnapshots=5321 ^
  -Dschemaforge.snapshot.ddl.failOnErrors=false ^
  -Dschemaforge.snapshot.ddl.failOnWarnings=false ^
  -Dschemaforge.snapshot.ddl.cleanOutput=true ^
  test
```

Use the evidence-recovered canonical corpus instead when that is the intended authoritative input.
The source JSON is never changed by this stage.

## Stage B1 - small live pilot

Run a small subset first. Use only a disposable MariaDB environment. `dropBeforeCreate=true` is
explicitly destructive and therefore also requires `confirmDestructive=true`.

```bat
set "MARIADB_JDBC_PASSWORD=<password>"

mvnw.cmd ^
  -Dtest=MariaDbDirectoryExecutionTest ^
  -Dmariadb.sql.root="D:\Sample-Docs-Scripts\SchemaForge-MariaDB-M9-DDL\mariadb" ^
  -Dmariadb.sql.fileSuffix=".mariadb.sql" ^
  -Dmariadb.jdbc.url="jdbc:mariadb://10.1.60.51:1521/" ^
  -Dmariadb.jdbc.user=root ^
  -Dmariadb.sql.expectedDatabase=TSTSHMA ^
  -Dmariadb.sql.executionMode=HISTORICAL ^
  -Dmariadb.sql.dropBeforeCreate=true ^
  -Dmariadb.sql.confirmDestructive=true ^
  -Dmariadb.sql.maxFiles=50 ^
  -Dmariadb.sql.failOnErrors=true ^
  test
```

## Stage B2 - full generated corpus replay

After the pilot is green, remove the `maxFiles` limit:

```bat
mvnw.cmd ^
  -Dtest=MariaDbDirectoryExecutionTest ^
  -Dmariadb.sql.root="D:\Sample-Docs-Scripts\SchemaForge-MariaDB-M9-DDL\mariadb" ^
  -Dmariadb.sql.fileSuffix=".mariadb.sql" ^
  -Dmariadb.jdbc.url="jdbc:mariadb://10.1.60.51:1521/" ^
  -Dmariadb.jdbc.user=root ^
  -Dmariadb.sql.expectedDatabase=TSTSHMA ^
  -Dmariadb.sql.executionMode=HISTORICAL ^
  -Dmariadb.sql.dropBeforeCreate=true ^
  -Dmariadb.sql.confirmDestructive=true ^
  -Dmariadb.sql.failOnErrors=true ^
  test
```

## Evidence

Each run writes under:

`target/mariadb-sql-execution-report/<run-id>/`

- `mariadb-sql-execution-summary.txt`
- `mariadb-sql-execution-files.csv`
- `mariadb-sql-execution-errors.csv`

The runner also verifies that the JDBC target reports itself as MariaDB, not MySQL.

## Acceptance criteria

M9 historical CREATE validation is green when the intended generated MariaDB script set completes
with:

- `Actionable failures = 0`
- `Cleanup failed = 0`
- Maven `BUILD SUCCESS`

Skipped FK/GRANT statements in HISTORICAL mode are expected and must not be counted as successful
integrated deployment evidence.

## M9.2 bulk-replay finding: fixed CHAR length

The first 500 generated Legacy scripts executed with zero actionable failures. In the next 1,000-file
batch (files 501-1500), MariaDB accepted 2,663 of 2,664 executed statements and rejected one CREATE
with error 1074 / SQLSTATE 42000 because a `CHAR` column length exceeded MariaDB's 255-character
fixed-width limit.

SchemaForge treats this as a generation-contract defect, not a reason to coerce the source type. The
MariaDB mapper now rejects `CHAR/NCHAR/CHARACTER(n)` when `n > 255`. It deliberately does not
automatically substitute `VARCHAR` or `TEXT`, because fixed-width padding semantics would change and
that requires an explicit portability decision. The MariaDB DDL sanity checker independently guards
the same limit with `MARIADB_FIXED_CHAR_LENGTH`.

After this correction, regenerate the canonical JSON -> MariaDB DDL directory before resuming the
live batch, so the invalid script is excluded by the generation eligibility gate rather than failing
at execution time.
