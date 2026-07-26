# Bulk generation diagnostic

Run `DirectoryDualDatabaseGenerationRunner` with two program arguments:

```text
<input-directory> <output-directory>
```

The input directory is scanned recursively. Temporary Word files beginning with `~$` are ignored.
The runner continues after an individual document failure so all input documents are inspected.

Generated diagnostic files:

- `batch-generation-summary.csv`: one row per Word document, including status, validity, object counts,
  generated script paths, elapsed time and a short error.
- `batch-index-details.csv`: one row per unique key, index and unique index, including ordered columns,
  index type, include columns and predicate.
- `batch-generation-errors.log`: full stack traces for failed documents.

The normal Oracle and PostgreSQL SQL files are still generated in the selected output directory.
