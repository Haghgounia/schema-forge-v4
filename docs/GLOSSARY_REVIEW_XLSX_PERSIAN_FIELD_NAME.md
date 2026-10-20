# Standard Word Glossary Audit: Review XLSX with Persian field labels

## Scope

This change targets the **six-sheet** human review workbook previously called
`schemaforge-glossary-word-audit-review-20261019.xlsx`, rather than a new,
unrelated Excel format. The original six worksheets and their original fields
are preserved. Two new lookup fields are added:

- `Identifiers`: insert column E, `نام فارسی فیلد`, alongside `Column` and `Identifier`.
- `Term Review Queue`: insert `Example Field Persian Name` immediately after
  `Example Source Document`, before the two manual reviewer fields.

The workbook now comes from the same `GlossaryStandardWordZipAuditTest` class
that reads the standard DOCX ZIP and writes the machine-readable CSVs. It is
not inferred from an English-to-Persian dictionary and it is not generated
from the filename. Only the `WordSpecificationParser`'s
`Column.description().value()` for that exact column is considered. If there
is no Persian writing in the source description, the Persian name is empty.
Table rows have no *field* Persian name.

## Six sheets (same order as the original manually reviewed workbook)

1. `Overview`: report counts, source evidence, additional count of Persian names
2. `Term Review Queue`: review counts, DOCX/identifier example, Persian example,
   manual decision and notes (left empty)
3. `Plural Review Hints`: heuristic inflection matches against CANDIDATE atomic terms
4. `Source Documents`: per-DOCX parser result and counts
5. `Identifiers`: one row per table and column, with source Persian field name
6. `Short and Acronym Hints`: conservative manual screening suggestions

There is **no automatic approval**, translation, abbreviation expansion, or
modification of the H2 database. `unknown-terms.csv`, `identifiers.csv`,
`terms-summary.csv`, `files.csv`, `errors.csv` and `summary.json` retain their
existing contracts. The XLSX path, length and SHA-256 are recorded in
`audit-evidence-manifest.json`.

## Windows usage

Close SchemaForge before running the ZIP audit to avoid H2 file locking:

```cmd
cd /d D:\Projects\schema-forge-v4
call distribution\bin\audit-standard-word-zip.cmd ^
  "D:\Sample-Docs\Word\SchemaDocuments_2.zip" ^
  "D:\Sample-Docs\Word\Glossary-Reports"
```

The generated report is under the timestamped output directory:

`reports/glossary/schemaforge-glossary-word-audit-review.xlsx`

For a focused test of the fixture and its actual Persian `COUNTRY_ID` label:

```cmd
mvnw.cmd clean -Dtest=GlossaryStandardWordZipAuditTest test
```

The focused test includes three deterministic scenarios, while the external
ZIP test runs when the ZIP property is supplied. The test requires the
project's Java 21, Apache POI and H2 runtime dependencies.

## Limitations

The old uploaded review workbook contains only names/IDs and glossary
classification, **not** the Persian descriptions from DOCX. Existing copies
cannot be reliably filled without the source ZIP. Run the updated audit
against the same ZIP to populate the new column from source documents.
