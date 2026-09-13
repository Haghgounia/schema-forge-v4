# M11 - MariaDB External Database Auditing / Validation Qualification

## Purpose

M11 qualifies the existing production `SchemaConformanceAuditService` against a real MariaDB server for the roadmap requirement that SchemaForge can validate tables or schemas that were created outside SchemaForge (for example directly by a DBA).

The live audit itself is read-only. The integration fixture is created directly with JDBC and deliberately bypasses SchemaForge DDL generation so the test proves that the service is not dependent on SchemaForge ownership/provenance.

## Scope decision

Legacy documentation errors, especially malformed historical FK declarations, are not MariaDB capability blockers. The MariaDB FK capability is considered qualified when a correctly specified physical FK is generated/created, read back from MariaDB metadata, and compared without semantic drift. Documentation-only FK inconsistencies remain reportable evidence but do not require exhaustive repair.

M11 therefore moves forward to external-database auditing instead of resolving every historical FK conflict.

## Production path under test

- `SchemaConformanceAuditService`
- `SchemaConformanceController`
- `MetadataRepositoryResolver`
- `JdbcMariaDbMetadataRepository`
- existing validation rule families:
  - `STRUCTURAL`
  - `METADATA_CONVENTION`
  - `DATATYPE_COMPATIBILITY`
  - `CONSTRAINT_REFERENCES`
  - `KEY_CONSTRAINTS`
  - `REFERENTIAL_INTEGRITY`
  - `INDEX_COVERAGE`
  - `PHYSICAL_NAMING`

M11 also closes a MariaDB-specific gap in `DatatypeCompatibilityAnalyzer`: because `MariaDbDialect` is intentionally independent from `MySqlDialect`, MariaDB datatype validation must have its own branch and limits.

## Live fixture

The M11 integration test creates an isolated database whose name must start with `SFORGE_M11_`.

The fixture is created by direct JDBC statements, not SchemaForge:

1. `M11_PARENT` - PK + UNIQUE key.
2. `M11_CHILD` - PK + one valid physical FK to `M11_PARENT`.
3. `M11_NO_PK` - intentionally lacks a PK and contains `MEDIUMINT`, which MariaDB accepts but is outside the current SchemaForge lossless MariaDB logical type coverage.

The test then:

1. Reads the FK through `JdbcMariaDbMetadataRepository`.
2. Audits `M11_CHILD` as a single table.
3. Audits the complete isolated schema.
4. Verifies the valid FK produces no referential-integrity error.
5. Verifies the audit finds `TABLE_PRIMARY_KEY_MISSING`.
6. Verifies the audit finds `MARIADB_DATATYPE_UNSUPPORTED` for the external `MEDIUMINT` column.
7. Verifies the table set and valid FK are unchanged by the audit.
8. Drops the isolated fixture database when cleanup is enabled.

## Run

With `MARIADB_JDBC_PASSWORD` already set:

```cmd
cd /d D:\Projects\schema-forge-v4

mvnw.cmd ^
  -Dtest=MariaDbDatatypeCompatibilityAnalyzerTest,MariaDbExternalDatabaseAuditM11IT ^
  -Dschemaforge.m11.mariadb.jdbc.url="jdbc:mariadb://10.1.60.51:1521/" ^
  -Dschemaforge.m11.mariadb.jdbc.user=root ^
  -Dschemaforge.m11.mariadb.audit.schema=SFORGE_M11_AUDIT ^
  -Dschemaforge.m11.mariadb.audit.outputDir="D:\Sample-Docs-Scripts\SchemaForge-MariaDB-M11-Audit" ^
  -Dschemaforge.m11.mariadb.audit.confirmDestructive=true ^
  -Dschemaforge.m11.mariadb.audit.cleanup=true ^
  test
```

## Acceptance

Expected invariants:

```text
Fixture creation path       : DIRECT_JDBC_OUTSIDE_SCHEMAFORGE
Tables before audit         : 3
Tables after audit          : 3
Table set unchanged         : true
Live valid foreign keys     : 1
Child audit executed        : true
Schema audit executed       : true
Schema tables scanned       : 3
Detected missing PK         : true
Detected unsupported type   : true
Valid FK integrity errors   : 0
Audit mutation              : NONE_EXPECTED
Cleanup succeeded           : true
BUILD SUCCESS
```

The exact warning/info count is intentionally not frozen because metadata-convention and naming findings may evolve independently. The qualification contract is behavioral: external objects are readable, validation families execute, valid FKs are accepted, detectable issues are reported, and the audit itself does not mutate the database.
