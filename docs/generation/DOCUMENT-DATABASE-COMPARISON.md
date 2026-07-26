# Document-to-Database Comparison Workbook

SchemaForge creates a comparison workbook only when the table declared by the input document already exists in the target database under the same schema and table name.

The metadata is read during the REST request. No comparison metadata is cached between requests.

## Generated files

For each available database, one workbook is added to the REST ZIP response:

```text
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.oracle.xlsx
<SCHEMA>.<TABLE>_compare_<yyyyMMdd_HHmmss_SSS>.postgresql.xlsx
```

If the table does not exist in a target database, no workbook is created for that database. SQL and JSON generation are not blocked.

## Workbook layout

The workbook preserves the established SchemaForge v3 and historical Excel corpus layout. It contains one worksheet named after the table and the following 22 columns:

| # | Column | Source |
|---:|---|---|
| 1 | COLUMN_USAGE | Metadata frequency of the document column name |
| 2-11 | COLUMN_ID through RANGE | Document specification |
| 12-21 | COLUMN_ID through CHECK CONSTRAINT | Live database metadata |
| 22 | DIFF | Comparison result tokens |

The comparison covers:

- column existence;
- possible renamed columns;
- ordinal position;
- canonical datatype, length, precision, and scale;
- nullability;
- default expression;
- comment;
- identity property;
- primary key;
- foreign key;
- uniqueness;
- index participation;
- check constraints.

## Difference tokens

Common values in the `DIFF` column include:

```text
NOT_EXISTS_IN_TABLE
NOT_EXISTS_IN_DOCUMENT
COLUMN_NAME
SIMILARITY
COLUMN ID
DATA_TYPE
NULLABLE
DATA_DEFAULT
COMMENTS
IDENTITY
PRIMARY_KEY
FOREIGN_KEY
UNIQUE
INDEX
CHECK CONSTRAINT
```

## Row highlighting

- light yellow: existing column with one or more differences;
- light red: document column missing from the database;
- light blue: database column missing from the document;
- light orange: possible renamed column.

The workbook does not alter either the document model or the database. It is a read-only comparison artifact.
