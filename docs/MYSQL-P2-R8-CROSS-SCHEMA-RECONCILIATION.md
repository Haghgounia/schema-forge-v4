# MySQL P2-R8 Cross-Schema Reconciliation

P2-R8 is an evidence-only generation pass for the P2-R6
`REVIEW_EXACT_NAME_OTHER_SCHEMA` candidates.

The DB2 table in the other schema is treated only as an evidence source. P2-R8 never changes the
canonical schema or table identity and never mutates persisted canonical JSON.

A cross-schema candidate is accepted only when all of these gates pass:

- P2-R6 classified exactly one candidate as `REVIEW_EXACT_NAME_OTHER_SCHEMA`.
- The candidate table name is exactly the canonical table name after identifier normalization.
- The DB2 candidate schema is different from the canonical schema.
- Canonical-to-candidate column coverage is at least the configured threshold (default `0.90`).
- Candidate-to-canonical column coverage is at least the configured threshold (default `0.75`).
- At least the configured number of non-blocked shared columns independently corroborate datatype families (default `3`).
- No corroborating shared column has a datatype-family conflict.
- A blocker column is recovered only when the canonical type is an exact numeric type with missing precision and the DB2 evidence type is a MySQL-supported exact numeric type.

If any gate fails, the candidate remains blocked. Ambiguous cross-schema candidates are not considered by
this pass. A confirmed table can also remain blocked when another unresolved MySQL blocker remains after
numeric recovery.

P2-R8 starts from the P2-R4 DB2 + historical-consensus overlay and uses the verified P2-R7 output only to
calculate cumulative projected coverage. P2-R7 and P2-R8 candidate classifications are mutually exclusive.

Outputs:

- `mysql-cross-schema-reconciliation-details_<timestamp>.csv`
- `mysql-cross-schema-reconciliation-applied_<timestamp>.csv`
- `mysql-cross-schema-reconciliation-remaining_<timestamp>.csv`
- `mysql-cross-schema-reconciliation-summary_<timestamp>.txt`
- `generated-new/**.mysql.sql` for newly unblocked P2-R8 snapshots only

Recommended properties:

- `schemaforge.mysql.crossschema.snapshotDir`
- `schemaforge.mysql.crossschema.db2SysColumnsFile`
- `schemaforge.mysql.crossschema.p2r4Dir`
- `schemaforge.mysql.crossschema.p2r6Dir`
- `schemaforge.mysql.crossschema.p2r7Dir`
- `schemaforge.mysql.crossschema.outputDir`
- `schemaforge.mysql.crossschema.minEvidence` (default `1`)
- `schemaforge.mysql.crossschema.minTypeCorroboration` (default `3`)
- `schemaforge.mysql.crossschema.minCanonicalCoverage` (default `0.90`)
- `schemaforge.mysql.crossschema.minCandidateCoverage` (default `0.75`)
- `schemaforge.mysql.crossschema.cleanOutput` (default `true`)
- `schemaforge.mysql.crossschema.failOnGenerationErrors` (default `false`)

Example Windows CMD execution:

```bat
mvnw.cmd clean ^
  -Dtest=MySqlCrossSchemaReconciliationGenerationIT ^
  -Dschemaforge.mysql.crossschema.snapshotDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson-20260818" ^
  -Dschemaforge.mysql.crossschema.db2SysColumnsFile="D:\SYSCOLUMNS-050511.zip" ^
  -Dschemaforge.mysql.crossschema.p2r4Dir="D:\Sample-Docs-Scripts\SchemaForge-MySQL-P2-R4" ^
  -Dschemaforge.mysql.crossschema.p2r6Dir="D:\Sample-Docs-Scripts\SchemaForge-MySQL-P2-R6" ^
  -Dschemaforge.mysql.crossschema.p2r7Dir="D:\Sample-Docs-Scripts\SchemaForge-MySQL-P2-R7" ^
  -Dschemaforge.mysql.crossschema.outputDir="D:\Sample-Docs-Scripts\SchemaForge-MySQL-P2-R8" ^
  -Dschemaforge.mysql.crossschema.minEvidence=1 ^
  -Dschemaforge.mysql.crossschema.minTypeCorroboration=3 ^
  -Dschemaforge.mysql.crossschema.minCanonicalCoverage=0.90 ^
  -Dschemaforge.mysql.crossschema.minCandidateCoverage=0.75 ^
  -Dschemaforge.mysql.crossschema.cleanOutput=true ^
  -Dschemaforge.mysql.crossschema.failOnGenerationErrors=false ^
  test
```
