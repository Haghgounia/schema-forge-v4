# Oracle FK Live Validation R2

This test validates foreign keys from the final historical definition of each table without treating corpus incompleteness or unresolved parent-key metadata as an Oracle dialect failure.

## Classification

1. `Dependency skipped` - the source or referenced table is outside the expected schema or is not present in the replay database.
2. `Structural blocked` - both tables exist, but the FK cannot be valid structurally because a source column is missing, a referenced column is missing, or the referenced column list does not match an enabled Oracle PRIMARY KEY / UNIQUE constraint.
3. `FK attempted` - the FK passed dependency and structural preflight and is executed against Oracle.
4. `FK failed` - a preflight-valid FK still failed in Oracle. These are the actionable live SQL/dialect execution errors.

The test drops every successfully created FK immediately after validation.

## Reports

- `oracle-fk-validation-summary.txt`
- `oracle-fk-validation-errors.csv`
- `oracle-fk-validation-blockers.csv`
- `oracle-fk-validation-skipped.csv`

Both error and blocker reports include source and referenced FK column lists.

## Safety and failure gates

- `oracle.fk.failOnErrors=true` fails the test only for preflight-valid FKs that still fail live Oracle execution.
- `oracle.fk.failOnBlockers=false` is the default because historical source ambiguity and missing parent-key evidence are corpus/model blockers rather than Oracle renderer failures.
- Set `oracle.fk.failOnBlockers=true` only for a curated integrated schema expected to have fully reconciled FK targets.

## Historical corpus rule

This test does not infer missing parent-key columns. If a Legacy Word reference does not carry enough target-key evidence, the resulting structural blocker remains visible for reconciliation instead of being reclassified as a dialect error.
