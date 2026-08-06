# Oracle execution root-cause fix

This revision is based on the real Oracle 26ai execution report for 4,766 generated files.

## Corrected in generation

- Oracle reserved table/column names are rendered with a deterministic `SF_` prefix.
- Invalid defaults are removed before DDL emission when they conflict with the target datatype, precision, scale, or character length.
- Oversized character/binary types fall back to LOB types under `MAX_STRING_SIZE=STANDARD`.
- Duplicate standalone index signatures and repeated columns inside an index are suppressed.

## Corrected in the execution test

`HISTORICAL` mode is the default and is intended for all historical versions:

- `DROP TABLE` remains guarded by explicit destructive confirmation.
- `ALTER ... FOREIGN KEY` and `GRANT` are skipped.
- after a failed `CREATE TABLE`, dependent statements in that file are skipped.
- cleanup attempts/success/failure are reported separately.

`FULL` mode executes every statement and is intended only for a curated integrated schema with one canonical version of each logical table and a dependency-aware execution order.

## Deliberate limitation

The revision does not invent missing parent-key metadata. Ambiguous Legacy Word foreign keys whose source documents do not identify the referenced PK/UK column still require canonical schema reconciliation before a `FULL` integrated run.
