
## Final metadata validation markers

| Marker | Validation code | Meaning |
|---|---|---|
| W:SCHEMA | SCHEMA_NOT_FOUND | Document schema does not exist in metadata. |
| W:TBL-SCHEMA | TABLE_IN_DIFFERENT_SCHEMA | Table exists under another schema. |
| W:FK-TABLE | FK_TABLE_NOT_FOUND | Referenced table does not exist. |
| W:FK-SCHEMA | FK_SCHEMA_RESOLVED | Referenced table schema was resolved from metadata. |
| W:FK-AMB | FK_SCHEMA_AMBIGUOUS | Referenced table exists in multiple schemas. |
| W:SINGULAR | PLURAL_COLUMN_COMPONENT | A plural component was detected in a column name. |

| W:TYPE-MISSING | COLUMN_DATATYPE_MISSING | Column row exists but datatype is missing. |
| W:DESC-MISSING | COLUMN_DESCRIPTION_MISSING | Column row exists but Persian name/description is missing. |
