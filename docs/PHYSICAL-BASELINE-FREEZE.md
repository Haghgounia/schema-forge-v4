# SchemaForge V4 - Physical DDL Baseline Freeze

Freeze date: 2026-08-17

Baseline lineage:

`Physical P0 -> Db2 Tablespace P1 -> Oracle P2 -> PostgreSQL P3 -> SQL Server P4 R2 -> Index Build P5 -> PostgreSQL Column Physical P6 R1 -> Db2 Index Build P7`

## Freeze status

The Physical DDL workstream is frozen on the P7 source tree.

User-verified full Maven regression on Windows:

```text
Tests run: 376
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
Finished at: 2026-08-17T05:14:48-07:00
```

The three skipped tests are the existing live database directory-execution integration tests. They are not Physical P0-P7 failures.

This freeze step changes documentation only. Production Java sources, tests, parser versions, snapshot versions, canonical datatype behavior, REST behavior, and generated DDL behavior are unchanged from the green P7 baseline.

## Canonical physical/build scopes

SchemaForge V4 now has the following explicit scopes:

- `Table.physicalOptions`: table and Db2 table-space profile evidence.
- `Column.physicalOptions`: column-scoped storage evidence (currently used by PostgreSQL STORAGE/COMPRESSION).
- `Index.physicalOptions`: standalone-index physical state with compatibility fallback to historical table-scoped options.
- `PrimaryKey.physicalOptions`: enforcing/backing-index physical state for the primary key.
- `UniqueKey.physicalOptions`: enforcing/backing-index physical state for a unique constraint.
- `Index.buildOptions`: operational CREATE INDEX behavior such as ONLINE, CONCURRENTLY, RESUMABLE, DEFINE and DEFER.

Physical state and build operation policy are intentionally separate. Absence of source/profile evidence is not converted into an invented operational directive.

## Frozen coverage by DBMS

### Oracle

Frozen coverage includes table/index tablespace placement, PCTFREE, PCTUSED review, INITRANS, table/index compression, LOGGING/NOLOGGING, PARALLEL/NOPARALLEL, table SEGMENT CREATION, and explicit index ONLINE build behavior.

Deferred by design: LOB storage objects/policies, partitions/subpartitions, IOT-specific storage, and environment-specific legacy STORAGE allocation.

### PostgreSQL

Frozen coverage includes table/index tablespace placement, table/index fillfactor, toast_tuple_target, parallel_workers, B-tree deduplicate_items, GiST buffering, GIN fastupdate/pending-list limit, BRIN pages_per_range/autosummarize, explicit index CONCURRENTLY, and column STORAGE/COMPRESSION.

Deferred by design: table access-method selection, partition-specific placement, and autovacuum/maintenance policy.

### Microsoft SQL Server

Frozen coverage includes table/index filegroup placement, DATA_COMPRESSION, XML_COMPRESSION, explicit CLUSTERED/NONCLUSTERED index organization, PAD_INDEX, FILLFACTOR, IGNORE_DUP_KEY, statistics options, row/page lock options, OPTIMIZE_FOR_SEQUENTIAL_KEY, and separate ONLINE/RESUMABLE/MAX_DURATION/MAXDOP/SORT_IN_TEMPDB build options.

Deferred by design: TEXTIMAGE_ON, FILESTREAM_ON, partition schemes/partition-specific compression, columnstore-specific physical policy, and storage-object provisioning.

### Db2 for z/OS

Frozen table-space profile includes BUFFERPOOL, DSSIZE review, SEGSIZE, FREEPAGE, PCTFREE/FOR UPDATE, COMPRESS, GBPCACHE, CLOSE, DEFINE, LOCKSIZE, LOCKMAX, MAXROWS, MEMBER CLUSTER, INSERT ALGORITHM, TRACKMOD, LOGGED/NOT LOGGED, STOGROUP, PRIQTY, SECQTY and ERASE.

Frozen index profile includes PADDED/NOT PADDED, STOGROUP, PRIQTY, SECQTY, ERASE, FREEPAGE, PCTFREE, GBPCACHE, COMPRESS, BUFFERPOOL, CLOSE and PIECESIZE. P7 adds explicit `DEFINE YES|NO` and `DEFER YES|NO` as build options.

Deferred by design: executable CREATE TABLESPACE/STOGROUP provisioning, COPY recovery policy, CLUSTER data-organization semantics, INCLUDE/EXCLUDE NULL KEYS semantics, and partition-specific index/table-space clauses.

## Source-value contract

1. Source/JSON/metadata is evidence, not automatically trusted truth.
2. SchemaForge does not silently clamp an invalid physical source value.
3. Invalid/inapplicable values remain visible through source/build ISSUE markers.
4. Context-sensitive but syntactically usable values remain visible through REVIEW markers.
5. Active placement already supported by the DDL remains active; newly recommended tuning remains review-visible unless an explicit build directive is deliberately modeled as executable.
6. Word, EA, and JDBC ingestion must not invent P0-P7 physical/build options that the source cannot actually represent.

## Re-opening rules

The frozen Physical workstream should be re-opened only when at least one of the following exists:

- a real source contract contains a physical/build property that cannot be represented;
- a production DBA use case requires one of the explicitly deferred object models;
- a target DBMS syntax/version change invalidates an existing renderer rule;
- a regression proves that a P0-P7 option is rendered incorrectly.

Do not re-open Physical work merely to mirror every vendor clause. New vendor features must first be classified as logical semantics, physical state, build/deployment policy, recovery policy, maintenance policy, or provisioning.

## Baseline for subsequent work

Use the packaged Physical Baseline Freeze ZIP as the source baseline for the next SchemaForge V4 workstream. Do not branch subsequent work from pre-P7 archives.
