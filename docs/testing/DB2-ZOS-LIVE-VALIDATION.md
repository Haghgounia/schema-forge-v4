# Db2 for z/OS validation without a permanent local connection

SchemaForge keeps normal Db2 generation fully offline. This document describes the staged validation path to use later on a workstation or CI agent that can reach an approved Db2 for z/OS subsystem.

## What is now covered offline

The normal test suite validates:

- Db2 datatype, identifier, expression and DDL rendering
- explicit unique enforcing indexes for every primary key and unique constraint
- rejection of Oracle and PostgreSQL client syntax in Db2 scripts
- rejection of `ON UPDATE` clauses
- `DECIMAL`/`NUMERIC` precision above 31 and invalid scale
- balanced SQL string literals and parentheses
- the presence of only statement families emitted by SchemaForge
- strategy-aware `SAFE` and `OPTIMIZED` numeric comparison
- mocked Db2 catalog metadata mapping

The offline validator intentionally does not claim that a Db2 subsystem has prepared the SQL.

## Important change: enforcing indexes

Db2 for z/OS can leave a table definition incomplete when a primary key or unique constraint has no matching unique enforcing index. SchemaForge now emits a deterministic index after each such constraint:

```sql
CREATE UNIQUE INDEX DPS.PK_PROVINCES_IX
  ON DPS.PROVINCES(PROVINCE_ID);

CREATE UNIQUE INDEX DPS.UK_PROVINCES_U1_IX
  ON DPS.PROVINCES(PROVINCE_CODE);
```

The static validator reports `REQUIRED_ENFORCING_INDEX_MISSING` if a generated script contains a primary/unique constraint without a matching explicit unique index.

## IBM JCC driver

The driver is not bundled or redistributed. Obtain the organization-approved IBM Data Server Driver for JDBC and SQLJ and point Maven at the local JAR:

```bat
-Ddb2zos.jcc.path=D:/drivers/db2jcc4.jar
```

The optional Maven profile `db2zos-live` is inactive unless that property is provided.

## Stage 1: generate and statically validate only

No database connection is used:

```bat
mvn -DskipTests compile
mvn -Pdb2zos-live ^
  -Ddb2zos.jcc.path=D:/drivers/db2jcc4.jar ^
  -Dexec.mainClass=com.behsazan.schemaforge.validation.db2zos.Db2ZosValidationRunner ^
  -Dexec.args="generate docs/samples/word target/db2zos-validation" ^
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java
```

Outputs include the Db2 SQL/JSON files and:

```text
db2zos-offline-validation-report_<timestamp>.csv
```

## Stage 2: read-only connection and catalog probe

The probe checks:

- JCC loading
- JDBC connection
- product and driver versions
- `CURRENT SERVER`
- `CURRENT SCHEMA`
- `CURRENT SQLID`
- read access and expected column projections for all Db2 catalogs used by the metadata adapter, plus `SYSIBM.SYSSEQUENCES`

Windows example:

```bat
mvn -Pdb2zos-live ^
  -Ddb2zos.jcc.path=D:/drivers/db2jcc4.jar ^
  -Dschemaforge.db2zos.url=jdbc:db2://host:446/LOCATION ^
  -Dschemaforge.db2zos.user=SCHEMAFORGE ^
  -Dschemaforge.db2zos.password=change-me ^
  -Dexec.mainClass=com.behsazan.schemaforge.validation.db2zos.Db2ZosValidationRunner ^
  -Dexec.args="probe" ^
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java
```

Equivalent environment variables:

```text
SCHEMAFORGE_DB2ZOS_URL
SCHEMAFORGE_DB2ZOS_USERNAME
SCHEMAFORGE_DB2ZOS_PASSWORD
SCHEMAFORGE_DB2ZOS_DRIVER
```

## Stage 3: disposable live integration test

`Db2ZosLiveIT` is intentionally excluded from normal `mvn test` execution by its `*IT` suffix. It creates a uniquely named sequence and table, verifies them through the catalog, and removes them in a `finally` block.

Use only an approved disposable qualifier with the required privileges:

```bat
mvn -Pdb2zos-live ^
  -Ddb2zos.jcc.path=D:/drivers/db2jcc4.jar ^
  -Dtest=Db2ZosLiveIT ^
  -Dschemaforge.db2zos.url=jdbc:db2://host:446/LOCATION ^
  -Dschemaforge.db2zos.user=SCHEMAFORGE ^
  -Dschemaforge.db2zos.password=change-me ^
  -Dschemaforge.db2zos.test.schema=SFTEST ^
  -Dschemaforge.db2zos.execution.confirm=I_UNDERSTAND_DB2_DDL_MAY_COMMIT ^
  test
```

The live test covers:

- sequence creation
- table creation
- sequence-backed default
- primary key and enforcing unique index
- unique constraint and enforcing unique index
- check constraint
- comments
- catalog verification
- cleanup

## Stage 4: execute generated Word scripts

This mode executes every generated statement and is deliberately blocked unless the exact confirmation value is supplied. Do not run it against production schemas.

```bat
mvn -Pdb2zos-live ^
  -Ddb2zos.jcc.path=D:/drivers/db2jcc4.jar ^
  -Dschemaforge.db2zos.url=jdbc:db2://host:446/LOCATION ^
  -Dschemaforge.db2zos.user=SCHEMAFORGE ^
  -Dschemaforge.db2zos.password=change-me ^
  -Dschemaforge.db2zos.execution.confirm=I_UNDERSTAND_DB2_DDL_MAY_COMMIT ^
  -Dexec.mainClass=com.behsazan.schemaforge.validation.db2zos.Db2ZosValidationRunner ^
  -Dexec.args="execute docs/samples/word target/db2zos-live-execution" ^
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java
```

This final mode is suitable only after reviewing generated schema names, databases/table spaces, referenced parent tables and grants. It writes both offline and execution CSV reports.

## Minimum catalog access for comparison and probe

The metadata adapter queries these catalog tables:

- `SYSIBM.SYSTABLES`
- `SYSIBM.SYSCOLUMNS`
- `SYSIBM.SYSTABCONST`
- `SYSIBM.SYSKEYS`
- `SYSIBM.SYSRELS`
- `SYSIBM.SYSFOREIGNKEYS`
- `SYSIBM.SYSCHECKS`
- `SYSIBM.SYSINDEXES`

The disposable integration test also reads `SYSIBM.SYSSEQUENCES`.

## Items that still require a real subsystem

The following cannot be proven offline:

- local authorization rules and trusted-context behavior
- package/collection configuration for the installed JCC version
- subsystem function level and APPLCOMPAT restrictions
- physical database/table-space naming and privileges
- site-specific CCSID, buffer-pool, storage-group and security standards
- actual preparation/execution of every emitted DDL variant
