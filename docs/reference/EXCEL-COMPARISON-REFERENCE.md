# Excel Comparison Workbook Reference

## 1. Workbook purpose

The comparison workbook presents the expected document/specification model next to the actual database metadata model. It is produced only when metadata is enabled and the relevant database table can be resolved.

At the current R4 baseline, the frozen P8-D workbook contract can contain nine sheets.

## 2. Sheet inventory

| Sheet | Purpose |
|---|---|
| `<TABLE_NAME>` | historical 22-column document/database column comparison |
| `TABLE_METADATA` | technical/Persian table naming, descriptions/comments |
| `TABLE_PHYSICAL_COMPARE` | expected vs actual table physical state |
| `INDEX_PHYSICAL_COMPARE` | expected vs actual index/PK/UK backing-index physical state |
| `COLUMN_PHYSICAL_COMPARE` | expected vs actual column physical state; currently PostgreSQL-specific rows |
| `PRIMARY_KEY_COMPARE` | logical primary-key object comparison |
| `FOREIGN_KEYS_COMPARE` | logical/physical FK object comparison |
| `INDEXES_COMPARE` | ordinary non-unique index object comparison |
| `UNIQUE_INDEXES_COMPARE` | unique constraints and unique-index object comparison |

Sheet names are allocated safely if a preferred name conflicts with another workbook sheet.

## 3. Historical 22-column table sheet

The primary table sheet uses these columns:

```text
COLUMN_USAGE
COLUMN_ID
COLUMN_NAME
COMMENTS
DATA_TYPE
PRIMARY/FOREIGN KEY
UNIQUE
INDEX
REQUIRED
DEFAULT
RANGE
COLUMN_ID
COLUMN_NAME
DATA_TYPE
NULLABLE
DATA_DEFAULT
COMMENTS
INDEX
UNIQUE_INDEX
FOREIGN KEY
CHECK CONSTRAINT
DIFF
```

The left side represents the document/design column. The right side represents the database column. `DIFF` summarizes detected differences.

## 4. Object comparison sheets

`PRIMARY_KEY_COMPARE`, `FOREIGN_KEYS_COMPARE`, `INDEXES_COMPARE`, and `UNIQUE_INDEXES_COMPARE` use:

```text
OBJECT_TYPE
DOCUMENT_NAME
DOCUMENT_DEFINITION
DATABASE_NAME
DATABASE_DEFINITION
STATUS
DIFF
```

Object statuses are:

- `SAME`;
- `ADD` - object exists in document/design but not in database metadata;
- `DROP` - object exists in database metadata but not in document/design;
- `MODIFY` - paired objects differ.

Pairing prefers exact names and then uses the supported structural key. Renames are not silently treated as proven design intent.

## 5. `TABLE_METADATA`

Columns:

```text
TECHNICAL_NAME
PERSIAN_NAME
DOCUMENT_DESCRIPTION
DATABASE_COMMENT
COMMENT_STATUS
```

This sheet compares table identity/description metadata and database comments.

## 6. `TABLE_PHYSICAL_COMPARE`

Columns:

```text
OBJECT
PROPERTY
EXPECTED
ACTUAL
STATUS
NOTE
```

Supported for all four databases.

## 7. `INDEX_PHYSICAL_COMPARE`

Columns:

```text
SCOPE
OBJECT
PROPERTY
EXPECTED
ACTUAL
STATUS
NOTE
```

`SCOPE` is one of:

```text
INDEX
PRIMARY_KEY
UNIQUE_KEY
```

Supported for all four databases.

## 8. `COLUMN_PHYSICAL_COMPARE`

Columns:

```text
COLUMN
PROPERTY
EXPECTED
ACTUAL
STATUS
NOTE
```

At the current frozen baseline, vendor-specific rows are generated for PostgreSQL `STORAGE` and `COMPRESSION` only.

## 9. Physical comparison statuses

The three physical sheets share:

- `MATCH`;
- `MISMATCH`;
- `NOT_SPECIFIED`;
- `NOT_AVAILABLE`;
- `REVIEW`.

See [Physical metadata comparison](PHYSICAL-METADATA-COMPARISON.md) for exact semantics.


## 10. Oracle logical identity equivalence

For the historical column comparison sheet, an EA logical identity is considered equivalent to an Oracle sequence-backed default only when the database default uses the deterministic SchemaForge sequence expected for that exact table/column. In that narrow case, `IDENTITY_MODE` and `DATA_DEFAULT` are not reported as differences.

An arbitrary or unrelated `sequence.NEXTVAL` remains a mismatch. This is a comparison rule only; it does not infer or rewrite design intent.

## 11. Reporting boundary

The workbook is a report, not a migration-plan generator. A `MISMATCH` means the expected and actual comparable values differ; it does not automatically authorize an ALTER/REBUILD operation.
