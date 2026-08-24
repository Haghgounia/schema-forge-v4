# SchemaForge V4 Architecture

## 1. Purpose

SchemaForge converts heterogeneous schema specifications into a DBMS-neutral canonical schema, validates and enriches that schema, and renders database-specific DDL for five supported logical-DDL platforms:

- Oracle
- PostgreSQL
- Db2 for z/OS
- Microsoft SQL Server
- MySQL

The same canonical model also drives JSON snapshots, diagrams, metadata validation, and Excel document-to-database comparison.

## 2. Architectural rule

The central rule is separation of concerns:

```text
Source specification
      |
      v
Parser / recovery
      |
      v
Canonical model
      |
      v
Normalize -> enrich -> validate
      |
      +-------------------------+
      |                         |
      v                         v
DDL generation            JSON / diagrams
      |
      v
Selected dialect
```

When database metadata is enabled, the database side remains separate:

```text
Design/specification                    Existing database
       |                                      |
       v                                      v
 documentTable                         JDBC MetadataRepository
       |                                      |
       |                                      v
       |                                databaseTable
       |                                      |
       +---------------+  +-------------------+
                       v  v
              comparison/reporting
                       |
                       v
                    Excel
```

`databaseTable` is evidence of current database state. It is not promoted into design intent and is not used to repair or regenerate design DDL.

## 3. Main layers and packages

| Layer | Package | Responsibility |
|---|---|---|
| API | `com.behsazan.schemaforge.api` | REST orchestration and ZIP responses |
| Application | `com.behsazan.schemaforge.application` | platform selection, preparation, output naming, generation services |
| Specification | `com.behsazan.schemaforge.specification` | parsing, legacy recovery, EA import, normalization, validation, JSON export |
| Canonical domain | `com.behsazan.schemaforge.domain` | DBMS-neutral schema model and value objects |
| Dialects | `com.behsazan.schemaforge.dialect` | DBMS-specific datatype and SQL rendering behavior |
| Physical rendering | `com.behsazan.schemaforge.physical` | validated physical DDL/review blocks per vendor |
| DDL generation | `com.behsazan.schemaforge.generation` | DBMS-neutral orchestration over the selected dialect |
| Metadata | `com.behsazan.schemaforge.metadata.repository` | live/catalog metadata acquisition |
| Metadata comparison | `com.behsazan.schemaforge.metadata.validation` | design-vs-database physical comparison |
| Reporting | `com.behsazan.schemaforge.reporting` | Excel comparison workbook |
| Snapshot | `com.behsazan.schemaforge.snapshot` | versioned canonical JSON snapshots/cache |
| Validation | `com.behsazan.schemaforge.validation` | DBMS-specific compatibility and DDL validation |
| Diagram | `com.behsazan.schemaforge.diagram` | Mermaid and Graphviz textual diagram export |

## 4. Preparation pipeline

`SchemaPreparationService` is the DBMS-independent preparation pipeline:

```text
parsed schema
    |
    v
SpecificationNormalizer
    |
    v
SchemaEnricher(s)
    |- AuditColumnSchemaEnricher
    `- GrantSchemaEnricher
    |
    v
SpecificationValidator
    |
    v
PreparedSchema(schema, validationReport)
```

All SQL dialects receive the same prepared canonical schema.

## 5. DDL generation boundary

`DdlGenerator` is orchestration only. Vendor syntax belongs in the selected `Dialect` or physical renderer.

`DialectFactory` currently creates:

- `OracleDialect`
- `PostgreSqlDialect`
- `Db2ZosDialect`
- `SqlServerDialect`
- `MySqlDialect`

The generator must not contain vendor-specific branches that leak one DBMS syntax into another dialect.

## 6. Metadata comparison boundary

Metadata repositories reconstruct canonical objects from a live database for comparison purposes. P8 adds persistent physical state to those database-side objects.

`PhysicalMetadataComparator` compares:

- table physical options;
- ordinary index physical options;
- primary-key backing-index physical options;
- unique-key backing-index physical options;
- PostgreSQL column `STORAGE` / `COMPRESSION`.

The comparator is read-only and does not mutate either input.

## 7. Offline versus connected behavior

Offline DDL generation does not require a database connection.

Database connections are optional and are used for workflows such as:

- metadata validation;
- document-to-database comparison workbooks;
- metadata-based Oracle CRUD package generation;
- metadata-based SQL Server CRUD procedure generation;
- environment-dependent execution validation tests.

## 8. Frozen architecture contracts

At the current baseline:

- parser/recovery and physical DDL work are frozen;
- P8 physical metadata comparison is frozen;
- actual database state never becomes design intent;
- `Index.buildOptions` remains independent from persistent `physicalOptions`;
- unsupported LOB/partition/recovery semantics are not flattened into generic maps;
- C8 application-service decomposition is complete;
- C9 test/live-evidence governance is complete;
- documentation finalization changes no Java source.
