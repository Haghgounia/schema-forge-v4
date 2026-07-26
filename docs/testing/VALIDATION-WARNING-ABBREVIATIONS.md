# Validation Warning Abbreviations

SchemaForge places short warning markers beside affected SQL column definitions. The full warning details are also written in the validation findings block at the beginning of the SQL file and in the JSON `validation.issues` array.

| Marker | Expansion | Meaning | Status |
|---|---|---|---|
| `W:DUP` | Warning: Duplicate | The column was defined more than once in the source document. The first executable definition is retained. | Active |
| `W:TYPE` | Warning: Type | The document datatype does not match the database metadata datatype after canonical comparison. | Active |
| `W:SPELL` | Warning: Spelling | A possible spelling error was detected in the identifier. The original identifier is preserved. | Active |
| `W:META` | Warning: Metadata | General metadata warning not covered by a more specific marker. | Reserved |
| `W:NAME` | Warning: Naming | Naming-convention warning. | Reserved |
| `W:NULL` | Warning: Nullability | Document and database metadata nullability differ. | Reserved |
| `W:DEF` | Warning: Default | Document and database metadata default values differ. | Reserved |
| `W:TYPE-MISSING` | Warning: Missing datatype | The column row is retained, but its datatype is missing in the Word document. | Active |
| `W:DESC-MISSING` | Warning: Missing description | The column row is retained, but its Persian name/description is missing. | Active |

## Example

```sql
/*   5*/  IS_ACTIVE NUMBER(1) DEFAULT 1 NOT NULL, -- W:DUP
```

The example means that `IS_ACTIVE` appeared more than once in the source document. SchemaForge keeps the first definition executable and reports the duplicate definition as a warning.
