# SchemaForge V4 - Final Physical Gap Audit

Baseline: Db2/zOS Index Build P7 / Physical Baseline Freeze.

Regression status: 376 tests, 0 failures, 0 errors, 3 skipped, user-verified with `mvn clean test` on 2026-08-17.

## Decision rule

SchemaForge only renders a physical/build value when it is explicit in source/profile/model evidence. It does not invent vendor defaults when the default depends on subsystem, edition, storage layout, partitioning, object kind, or operational policy.

## Oracle

Covered now: table/index tablespace placement, PCTFREE, PCTUSED review, INITRANS, table/index compression, LOGGING/NOLOGGING, PARALLEL/NOPARALLEL, SEGMENT CREATION, and index ONLINE build directive.

Important remaining gap: LOB storage (`LOB (...) STORE AS SECUREFILE|BASICFILE`, LOB tablespace, in-row/out-of-row storage, SecureFiles compression/deduplication/encryption/cache). This is column/LOB-segment scoped and should not be flattened into generic table/index physical options. Partition/subpartition physical storage is also intentionally deferred until a partition model exists.

## PostgreSQL

Covered now: table/index tablespace, fillfactor, toast_tuple_target, parallel_workers, B-tree deduplicate_items, GiST buffering, GIN fastupdate/pending-list limit, BRIN pages_per_range/autosummarize, index CONCURRENTLY, and column STORAGE/COMPRESSION.

Remaining: table access method (`USING method`) and partition-specific placement. Per-table/TOAST autovacuum parameters are operational maintenance policy and remain outside the generic physical profile.

## SQL Server

Covered now: table/index filegroup placement, DATA_COMPRESSION, XML_COMPRESSION, index organization, PAD_INDEX, FILLFACTOR, IGNORE_DUP_KEY, statistics options, row/page lock options, OPTIMIZE_FOR_SEQUENTIAL_KEY, and ONLINE/RESUMABLE/MAX_DURATION/MAXDOP/SORT_IN_TEMPDB build options.

Important remaining gap: `TEXTIMAGE_ON` for large-value columns. `FILESTREAM_ON`, partition-specific compression, partition schemes and columnstore-specific policies need additional object/partition/index-kind modeling and are intentionally deferred.

## Db2 for z/OS

Covered now: table-space physical profile, index allocation/tuning profile, PADDED/NOT PADDED, STOGROUP/PRIQTY/SECQTY/ERASE, FREEPAGE/PCTFREE, GBPCACHE, COMPRESS, BUFFERPOOL, CLOSE, PIECESIZE and P7 operational DEFINE/DEFER build directives.

Remaining classifications:
- `COPY`: recovery policy; keep separate from build options.
- `CLUSTER/NOT CLUSTER`: persistent data-organization design; do not infer.
- `INCLUDE NULL KEYS/EXCLUDE NULL KEYS`: index semantic/filtering behavior with restrictions; do not classify as generic physical tuning.
- partitioned-index `DSSIZE`/PARTITION clauses: require a partition model/context.

## P7 selection and freeze decision

P7 implements only Db2 `DEFINE` and `DEFER` because they fit the already-separated `Index.buildOptions` model and can be validated without adding a new domain abstraction. `DEFINE NO` is emitted only with explicit index STOGROUP evidence. `DEFER YES` is emitted only when explicit and is accompanied by a DBA review warning about rebuild-pending behavior on populated tables.

After P7, the Physical DDL workstream is frozen. The remaining gaps require additional object/partition/recovery semantics rather than another flat list of generic physical options. See `PHYSICAL-BASELINE-FREEZE.md`.

## Vendor reference re-verification - 2026-08-17

The freeze decisions were rechecked against current primary vendor documentation before packaging:

- Oracle AI Database 26 SQL Language Reference - `CREATE TABLE`, plus SecureFiles and Large Objects documentation for LOB storage scope.
- PostgreSQL 18 - `CREATE TABLE` and table access-method documentation.
- Microsoft SQL Server Transact-SQL - `CREATE TABLE` and `CREATE INDEX`, including TEXTIMAGE/FILESTREAM and partition-aware compression behavior.
- IBM Db2 13 for z/OS - `CREATE INDEX` and index storage documentation, including DEFINE/DEFER, COPY, CLUSTER and NULL-key semantics.

These references confirm the freeze classification: the remaining items are not safely representable as another flat set of generic table/index physical options without introducing dedicated LOB, partition, recovery, access-method, or organization semantics.
