# SchemaForge V4.14.3 – Standard Word ZIP Glossary Audit

## Purpose / input selection

This optional external-corpus JUnit test audits a ZIP of **standard `.docx` table
specifications with descriptive English table/column identifiers**, using the
existing `WordSpecificationParser`, `GlossaryScanner`, and the current **H2 Glossary**.
It is independent of DBMS connectivity and does not generate DDL or run a legacy parser.

The archive may contain subfolders; documents are processed in stable path order.
Only `.docx` is parsed. `.doc` (legacy Word) triggers a clear input error; unrelated
non-Word files are explicitly marked `IGNORED_NON_WORD`. Files with invalid ZIP names,
corrupt DOCX or nonstandard specification structure become auditable failures, not
silently successful results. The test does not expand abbreviations, guess meanings,
or approve words: **`APPROVED` is the only passing Glossary status**. Other tokens
are reported as `NEW`, `PENDING_APPROVAL` or `REJECTED`.

## Windows execution

1. Make sure the project's existing `data/schemaforge-glossary.mv.db` exists and has
   been initialized with `01_Atomic_Terms`. Stop the SchemaForge server first to avoid
   H2 file-lock conflicts. **Do not delete/recreate the existing H2 file**.
2. Put the standard Word files in a ZIP. You do not need to unzip them.
3. At the project root, run:

```cmd
cd /d D:\Projects\schema-forge-v4
call distribution\bin\audit-standard-word-zip.cmd "D:\Sample-Docs-Scripts\Standard-Word-Meaningful.zip" "D:\Sample-Docs-Scripts\Glossary-Word-Audit"
```

The first argument is the ZIP to scan. The optional second is the report root;
third is the H2 *base path without `.mv.db`* (default: `data/schemaforge-glossary`).
The script does **not** enable the running Spring service and does not write to H2.

Equivalent direct Maven command (useful for other H2 paths / additional options):

```cmd
mvnw.cmd ^
  -Dschemaforge.glossary.wordZip.file="D:\Sample-Docs-Scripts\Standard-Word-Meaningful.zip" ^
  -Dschemaforge.glossary.wordZip.outputDir="D:\Sample-Docs-Scripts\Glossary-Word-Audit" ^
  -Dschemaforge.glossary.wordZip.h2Base="D:\Projects\schema-forge-v4\data\schemaforge-glossary" ^
  -Dschemaforge.glossary.wordZip.required=true ^
  -Dtest=GlossaryStandardWordZipAuditTest ^
  test
```

**Optional strict Gate:** add `-Dschemaforge.glossary.wordZip.failOnFindings=true` to
fail the JUnit test if *any* unapproved/pending/rejected token is found. Without it,
the test still **fails** when DOCX parsing, ZIP structure or input validation fails,
but otherwise finishes successfully and generates review reports.

The external-input test is automatically **skipped** during ordinary
`mvnw.cmd clean test` when no ZIP path property is supplied. The class also contains
two unconditional tests: one exercises a real bundled standard DOCX fixture
through ZIP parsing and report generation, while the other rejects unsafe ZIP
paths and legacy DOC.

## Reports (UTF-8)

Each run creates a unique directory, e.g.

```
Glossary-Word-Audit/
  audit-YYYYMMDD-HHMMSS-SSS-xxxxxxxx/
    audit-evidence-manifest.json
    reports/glossary/
      summary.json
      files.csv
      identifiers.csv
      unknown-terms.csv
      terms-summary.csv
      errors.csv
      review-import-part-0001.csv  (if needed for large reports)
      review-import-part-0002.csv  (if needed)
```

- `summary.json`: source ZIP SHA-256, H2 engine and vocabulary fingerprint,
  number of standard Word documents, table and column names inspected,
  clean/review-needed/failed counts and candidate/rejected/new occurrences.
- `files.csv`: **one record for each ZIP file**, with parsing status and source
  SHA-256 for successfully parsed DOCX files.
- `identifiers.csv`: one row per actual canonical table/column identifier, indicating
  `APPROVED` or `REVIEW_REQUIRED`, with new/pending/rejected occurrence counts.
- `unknown-terms.csv`: original **eight-column SchemaForge Glossary report format**,
  directly usable with `POST /api/v1/glossary/review/import-report` when within its
  5 MiB / 20,000-row contract.
- `terms-summary.csv`: aggregate frequency and affected document counts for each
  `(review_status, term)` combination.
- `errors.csv`: parsing, unsupported Legacy DOC, invalid path and ZIP errors.
- `audit-evidence-manifest.json`: hashes and byte sizes of report artifacts.
- `review-import-part-0001.csv` etc.: generated **only when** the unified report
  exceeds review API size/row constraints. These are individually validated for
  the existing import API. Import them separately in order if desired.

> These files constitute a **separate audit-evidence report**. The custom
> `audit-evidence-manifest.json` is not the standard DDL Artifact Manifest, because
> this class deliberately does not invoke DDL generation.

## Interpretation and operational safety

When the bundled Atomic baseline was imported but no manual approvals have been
performed, the 804 source terms are `CANDIDATE`, not `APPROVED`. Consequently most
known names can be `PENDING_APPROVAL` rather than passing. This is expected:
**do not bulk-approve them merely to force a green report**. Review their meaning
with the terminology steward first. All decisions are outside this test.

The H2 URL uses `IFEXISTS=TRUE;ACCESS_MODE_DATA=r` and is accessed only through
SQL SELECT; the test does not instantiate `GlossaryRepository` (its constructor
contains DDL), import Excel, seed baseline data or write approval status. If H2
is missing, empty or inaccessible, the audit fails with an actionable diagnostic.
The database file should be backed up through a consistent offline backup, not
by copying an active file. The report root must **not** be the H2 base directory.

Bounds: input ZIP <= 1 GiB; <= 20,000 entries; each decompressed DOCX <= 32 MiB;
combined DOCX payload <= 2 GiB; <= 250,000 findings. Use multiple ZIP runs
if the source corpus exceeds these limits. Unsafe filenames and duplicate source
paths are rejected. All reports are generated from validated ZIP entry streams,
not extracted to the project or arbitrary filesystem locations.

This test checks the **lexical governance** of actual table/column names. It does
not establish natural-language semantic meaningfulness or automatically detect
abbreviations: those require a curated approved vocabulary and human review.
