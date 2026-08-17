# Physical Phase 1 - coverage matrix

Scope: physical placement and table/index storage options that can be rendered safely without provisioning database storage objects or guessing environment/workload values.

## Oracle

Implemented for table candidates:
- active TABLESPACE preservation / project TS_<SCHEMA> behavior
- PCTFREE
- PCTUSED only when source explicitly supplies it; otherwise ASSM/MSSM review note
- INITRANS
- table compression: NOCOMPRESS, basic/advanced row compression, and source-preserved Hybrid Columnar Compression syntax with storage-capability review
- source/profile-aware LOGGING / NOLOGGING; omitted values remain intentionally unspecified
- source/profile-aware PARALLEL / NOPARALLEL / PARALLEL n; documented NOPARALLEL default is shown when absent
- source/profile-aware SEGMENT CREATION DEFERRED / IMMEDIATE; omitted values remain a review placeholder so SchemaForge does not override DEFERRED_SEGMENT_CREATION policy
- source-value validation; invalid values remain visible as SOURCE PHYSICAL ISSUE

Implemented for index / PK / UK backing-index candidates:
- active TABLESPACE preservation / project ITS_<SCHEMA> behavior
- PCTFREE
- INITRANS
- index compression source validation (NOCOMPRESS, prefix COMPRESS[/n], advanced compression)
- object-scoped source/profile-aware LOGGING / NOLOGGING
- object-scoped source/profile-aware PARALLEL / NOPARALLEL / PARALLEL n
- standalone-index ONLINE build directive from explicit `Index.buildOptions` only

Intentionally not auto-selected in Phase 1:
- legacy/manual STORAGE allocation attributes (environment/tablespace policy)
- LOB storage, partitioning and IOT-specific storage

## PostgreSQL

Implemented:
- source TABLESPACE preservation; no invented default tablespace
- table fillfactor
- source/profile-only toast_tuple_target with offline block-size review
- source/profile-only table parallel_workers; absent values remain server-derived
- index fillfactor with B-tree default preserved and explicit non-B-tree method awareness
- source/profile-only B-tree deduplicate_items
- source/profile index access-method evidence for btree/hash/gist/spgist/gin/brin
- GiST buffering
- GIN fastupdate and gin_pending_list_limit
- BRIN pages_per_range and autosummarize
- method-conflict validation: method-specific options are not silently applied to a conflicting explicit access method
- correct USING INDEX TABLESPACE grammar for PK/UK constraint placement
- standalone-index CONCURRENTLY build directive from explicit `Index.buildOptions` only
- column-scoped explicit `STORAGE PLAIN|EXTERNAL|EXTENDED|MAIN|DEFAULT`
- column-scoped explicit `COMPRESSION pglz|lz4|default` with safe type/storage guards

Intentionally not auto-selected:
- autovacuum storage parameters (operational tuning)
- table access method changes
- detailed TOAST/autovacuum tuning beyond `toast_tuple_target`
- partitioning

## SQL Server

Implemented:
- source filegroup placement; no invented default filegroup
- table DATA_COMPRESSION (NONE/ROW/PAGE)
- index PAD_INDEX
- FILLFACTOR
- IGNORE_DUP_KEY, with UNIQUE-index context review when the renderer cannot prove uniqueness
- STATISTICS_NORECOMPUTE
- ALLOW_ROW_LOCKS
- ALLOW_PAGE_LOCKS
- index DATA_COMPRESSION
- source/profile-only OPTIMIZE_FOR_SEQUENTIAL_KEY
- invalid source values remain visible and are not clamped

Implemented additionally in P4/P5:
- source/profile `XML_COMPRESSION`
- source/metadata-backed explicit CLUSTERED / NONCLUSTERED organization
- separate index build options: ONLINE, RESUMABLE, MAX_DURATION, MAXDOP, SORT_IN_TEMPDB

Intentionally not auto-selected:
- TEXTIMAGE_ON / FILESTREAM_ON / partition scheme provisioning

## Db2 for z/OS

Implemented table placement/table-space profile:
- active IN database.tablespace preservation
- BUFFERPOOL (source/profile identifier or placeholder)
- DSSIZE syntax/range review without inferring PBG/PBR/PAGENUM organization
- SEGSIZE (multiple of 4, 4..64)
- FREEPAGE (0..255)
- PCTFREE (0..99) and source/profile PCTFREE FOR UPDATE (-1..99), including combined <=99 validation
- COMPRESS NO/YES/YES FIXEDLENGTH/YES HUFFMAN
- GBPCACHE CHANGED/ALL/NONE
- CLOSE YES/NO
- DEFINE YES/NO
- LOCKSIZE ANY/TABLESPACE/PAGE/ROW and LOCKMAX SYSTEM/integer compatibility checks
- MAXROWS (1..255)
- source/profile MEMBER CLUSTER
- INSERT ALGORITHM (0..2)
- source/profile TRACKMOD YES/NO
- LOGGED / NOT LOGGED
- USING STOGROUP, PRIQTY, SECQTY, ERASE
- profile remains comment-only; CREATE TABLESPACE is not provisioned

Implemented index / PK / UK backing-index candidates:
- PADDED / NOT PADDED review only when a varying-length string key makes it applicable
- source PADDED on fixed-length-only keys is surfaced as an issue instead of silently ignored
- USING STOGROUP
- PRIQTY (source validation: positive integer or -1)
- SECQTY (source validation: positive integer, 0 or -1)
- ERASE
- FREEPAGE
- PCTFREE
- GBPCACHE
- COMPRESS
- BUFFERPOOL
- CLOSE
- source/profile-only PIECESIZE with syntax validation and table-space/index-organization review
- P7 explicit CREATE INDEX DEFINE YES|NO and DEFER YES|NO through `Index.buildOptions`; absent values produce no clause
- DEFINE NO requires explicit index STOGROUP evidence; DEFER YES remains DBA-review-visible

Intentionally not auto-selected / still deferred:
- executable CREATE TABLESPACE / CREATE STOGROUP provisioning
- MAXPARTITIONS / NUMPARTS / PAGENUM / partition-specific clauses
- CLUSTER (index data-organization design)
- COPY (recovery policy)
- LOB auxiliary table/tablespace provisioning
## Source-value policy

Word/JSON is evidence, not truth. SchemaForge:
1. retains a source physical value only when it passes the target-specific check available at generation time;
2. never silently clamps an invalid physical source value;
3. writes `[SOURCE PHYSICAL ISSUE]` in the same SQL physical block for invalid/inapplicable values;
4. uses environment placeholders when a value cannot be safely inferred;
5. keeps new recommendations inside activation-ready block comments; existing active placement remains active.

## Physical-option granularity

Column, Index, Primary Key backing-index, and Unique Key backing-index physical options are object-scoped. Historical table-scoped index options remain a compatibility fallback. `Index.buildOptions` is a separate object-scoped channel for operational CREATE INDEX directives and is not merged into persistent physical state. Db2 table-space profile options remain table-scoped; shared table-space provisioning/deduplication is not modeled as an executable schema object.

## Frozen baselines

The Physical DDL renderer/model workstream P0-P7 remains frozen at the user-verified 376-test baseline (0 failures, 0 errors, 3 skipped).

The expected-vs-actual Physical Metadata Comparison workstream P8-A/P8-B/P8-C is frozen by P8-D at the user-verified 399-test baseline (0 failures, 0 errors, 3 skipped). P8 adds database-catalog acquisition and Excel comparison only; it does not promote actual database state into design intent or generated DDL.

Frozen P8 workbook coverage:
- `TABLE_PHYSICAL_COMPARE`: Oracle, PostgreSQL, SQL Server, Db2 for z/OS
- `INDEX_PHYSICAL_COMPARE`: ordinary indexes plus PK/UK backing indexes for all four platforms
- `COLUMN_PHYSICAL_COMPARE`: PostgreSQL `STORAGE` / `COMPRESSION` only

Remaining LOB, partition, recovery, access-method, FILESTREAM/TEXTIMAGE and similar gaps require dedicated models or explicit source contracts; they are not to be flattened into generic `physicalOptions`. See `docs/P8D-PHYSICAL-COMPARISON-BASELINE-FREEZE.md`.


## 2026-08-17 SQL Server Physical P4 delta
- `XML_COMPRESSION` is supported from explicit table/index source or profile evidence only; it remains review-visible because the clause is SQL Server 2022+.
- `STATISTICS_INCREMENTAL` is supported from explicit index evidence only; filtered-index `ON` is rejected to a review placeholder.
- Explicit rowstore organization (`CLUSTERED` / `NONCLUSTERED`) is preserved for standalone indexes and PK/UK backing indexes.
- SQL Server catalog ingestion now retains `sys.indexes.type_desc` and index data-space placement at object scope, including `sys.key_constraints.unique_index_id` backing indexes.
- Build-time options (`ONLINE`, `RESUMABLE`, `MAX_DURATION`, `MAXDOP`, `SORT_IN_TEMPDB`) are implemented in the separate P5 `Index.buildOptions` policy path.
