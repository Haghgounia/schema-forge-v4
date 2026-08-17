# Inputs, Outputs, and Processing Pipeline

## 1. Supported specification inputs

### Standard Word

The normal Word parser path uses `WordSpecificationParser` and the DBMS-neutral specification pipeline.

### Legacy Word

Legacy heterogeneous Word documents use `LegacyWordSpecificationParser` and the legacy recovery package. Recovery is evidence-driven; unresolved ambiguity remains a parser/recovery finding rather than a guessed schema fact.

### Enterprise Architect XML

`EnterpriseArchitectXmlParser` imports supported EA XML/XMI structures into the same canonical model used by Word input.

### Canonical JSON snapshots

Versioned canonical JSON snapshots are used as a cache/bulk-regression input so dialect and DDL work can run without reopening large Word corpora. This is an integration/bulk workflow; the default offline CLI still starts from a Word input file.

### Database metadata

Optional JDBC metadata adapters reconstruct the current database-side `Table` for validation and comparison. Metadata is not a design source.

## 2. Core processing path

```text
Input
  |
  v
Parser
  |
  v
DatabaseSchema
  |
  v
SchemaPreparationService
  |- normalize
  |- audit/grant enrichment
  `- validate
  |
  v
PreparedSchema
  |
  +-------------------------------+
  |               |               |
  v               v               v
JSON          DDL/Dialect      Diagrams
                                  
Optional database metadata
  |
  v
databaseTable
  |
  +------ documentTable
             |
             v
         Excel compare
```

## 3. Offline CLI output

The offline generation service writes:

- canonical JSON;
- SQL for the selected database platform.

Typical platform names are:

```text
oracle
postgresql
db2zos
sqlserver
```

The CLI does not require JDBC connectivity.

## 4. REST and batch output

REST/ZIP workflows can generate the registered database outputs from one prepared canonical schema without reparsing the source for every dialect.

Where target metadata is enabled and the table exists, the response may additionally contain a database comparison workbook.

## 5. SQL artifact contract

For a renderable table specification, the SQL file is intended to be self-contained for DBA review and includes the applicable combination of:

- validation and datatype findings;
- DBMS preamble;
- schema bootstrap/provisioning fragment;
- sequences;
- table/columns;
- PK/check/unique constraints;
- standalone indexes;
- physical foreign keys;
- logical FK hints;
- table/column comments;
- grants;
- physical review/issues;
- generation summary/footer.

A blocking datatype mapping does not produce guessed DDL.

## 6. Comparison workbook output

When a live table is available, `SchemaCompareExcelWriter` receives both sides independently:

```text
documentTable  = expected/design
 databaseTable = actual/current DB
```

The workbook contains logical/object comparison plus the frozen P8 physical comparison sheets. See [Excel comparison workbook reference](EXCEL-COMPARISON-REFERENCE.md).

## 7. Diagram output

The prepared canonical model can also feed Mermaid and Graphviz textual diagram generation. Diagram generation is independent from SQL dialect rendering.

## 8. Metadata-based CRUD output

Current dedicated CRUD generation exists for:

- Oracle: metadata-based PL/SQL CRUD package;
- SQL Server: metadata-based CRUD stored procedures.

These workflows operate from live database metadata and are separate from Word/EA DDL generation.
