# MySQL P2-R6 - DB2 table reconciliation audit

P2-R5 showed that cross-source datatype conflict recovery is not a major coverage lever: only two conflict occurrences had exact DB2 + historical canonical consensus.

P2-R6 therefore audits the largest remaining evidence gap: blockers for which exact DB2 lookup returns `TABLE_NOT_FOUND`.

This phase is audit-only:

- canonical JSON is not changed;
- MySQL production mapping is not changed;
- no fuzzy candidate is applied automatically;
- DB2 metadata is parsed only to compare table names and column signatures.

The audit classifies candidate relationships conservatively:

- `STRONG_SAME_SCHEMA_NORMALIZED_NAME`
- `STRONG_SAME_SCHEMA_PREFIX_COLUMN_SIGNATURE`
- `STRONG_SAME_SCHEMA_NEAR_NAME_COLUMN_SIGNATURE`
- `STRONG_SAME_SCHEMA_UNIQUE_COLUMN_SIGNATURE`
- `REVIEW_EXACT_NAME_OTHER_SCHEMA`
- ambiguous/weak/no-candidate classifications

Even `STRONG_*` means only that a candidate is worth a second evidence review. It is not an automatic recovery rule.

Run:

```bat
mvnw.cmd ^
  -Dtest=MySqlDb2TableReconciliationAuditIT ^
  -Dschemaforge.mysql.tablename.snapshotDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson-20260818" ^
  -Dschemaforge.mysql.tablename.db2SysColumnsFile="D:\SYSCOLUMNS-050511.zip" ^
  -Dschemaforge.mysql.tablename.p2r4Dir="D:\Sample-Docs-Scripts\SchemaForge-MySQL-P2-R4" ^
  -Dschemaforge.mysql.tablename.outputDir="D:\Sample-Docs-Scripts\SchemaForge-MySQL-P2-R6" ^
  test
```

The important output is the number and distribution of `STRONG_*`, ambiguous, and `NO_TABLE_CANDIDATE` snapshots.

Project-root patch notes can be normalized after overlay extraction with `scripts\cleanup-mysql-patch-notes.cmd`.
