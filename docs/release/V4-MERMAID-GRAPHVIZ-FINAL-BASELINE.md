# SchemaForge V4 + Mermaid + Graphviz Final Baseline

Baseline ID: `SCHEMAFORGE-V4-MERMAID-GRAPHVIZ-FINAL-20260815`

Status: **FROZEN / VALIDATED**

## Scope

This baseline preserves the validated SchemaForge V4 core and includes the completed diagram export capability for Mermaid and Graphviz DOT.

### Database and canonical outputs

- Oracle SQL
- PostgreSQL SQL
- Microsoft SQL Server SQL
- Db2 for z/OS SQL
- Canonical JSON
- Comparison Excel when live metadata is enabled

### Mermaid

- Per-table ER `.mmd`
- Batch ER `.mmd`
- Batch dependency `.mmd`
- Strict duplicate-table handling with no automatic historical version selection
- Unresolved FK reporting

### Graphviz

- Per-table `.graphviz.dot`
- Batch flat dependency `schema-dependency.dot`
- Batch full clustered `schema-clustered.dot`
- Batch compact `schema-compact.dot`
- Batch overview `schema-overview.dot`
- Schema clustering
- Optional disconnected-table filtering
- Optional FK labels
- DOT only; no Graphviz executable is invoked by SchemaForge

## Final regression evidence

Full Maven regression supplied from the validated project workspace:

```text
Tests run : 313
Failures  : 0
Errors    : 0
Skipped   : 3
Result    : BUILD SUCCESS
```

Word regression:

```text
Documents : 9
Passed    : 9
Failed    : 0
Tables    : 9
Columns   : 117
```

## Real ZIP pipeline evidence

Input:

`D:\Sample-Docs\Word\SchemaDocuments_3.zip`

Validated result:

```text
Source DOCX          : 75
Successful documents : 75
Oracle SQL           : 75
PostgreSQL SQL       : 75
SQL Server SQL       : 75
Db2 z/OS SQL         : 75
Canonical JSON       : 75
Mermaid ER           : 75
Graphviz DOT         : 75
Result               : PASS
```

Batch relationship metrics:

```text
Table definitions       : 75
Distinct table names    : 72
Duplicate table names   : 3
Exported unique tables  : 69
Connected tables        : 43
Physical FKs (exported) : 58
Resolved physical FKs   : 48
Issues                   : 13
```

Duplicate policy:

`EXCLUDE_ALL_DUPLICATE_DEFINITIONS_NO_AUTO_SELECTION`

Graphviz profiles:

```text
FULL     : disconnected=true,  labels=true,  clusterBySchema=true
COMPACT  : disconnected=false, labels=true,  clusterBySchema=true
OVERVIEW : disconnected=false, labels=false, clusterBySchema=true
```

## Freeze rule

No new feature, parser behavior, canonical-domain behavior, DDL dialect behavior, or output contract should be added to this baseline. Future capabilities should branch from this baseline and receive a new baseline identifier after regression and real-pipeline validation.
