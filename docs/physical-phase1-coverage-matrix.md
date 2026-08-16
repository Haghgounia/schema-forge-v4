# Physical Phase 1 - coverage matrix

Scope: physical placement and table/index storage options that can be rendered safely without provisioning database storage objects or guessing environment/workload values.

## Oracle

Implemented for table candidates:
- active TABLESPACE preservation / project TS_<SCHEMA> behavior
- PCTFREE
- PCTUSED only when source explicitly supplies it; otherwise ASSM/MSSM review note
- INITRANS
- table compression: NOCOMPRESS, basic/advanced row compression, and source-preserved Hybrid Columnar Compression syntax with storage-capability review
- source-value validation; invalid values remain visible as SOURCE PHYSICAL ISSUE

Implemented for index / PK / UK backing-index candidates:
- active TABLESPACE preservation / project ITS_<SCHEMA> behavior
- PCTFREE
- INITRANS
- index compression source validation (NOCOMPRESS, prefix COMPRESS[/n], advanced compression)

Intentionally not auto-selected in Phase 1:
- LOGGING / NOLOGGING (recovery/workload policy)
- PARALLEL / NOPARALLEL (execution policy)
- legacy/manual STORAGE allocation attributes (environment/tablespace policy)
- LOB storage, partitioning and IOT-specific storage

## PostgreSQL

Implemented:
- source TABLESPACE preservation; no invented default tablespace
- table fillfactor
- source/profile-only toast_tuple_target with offline block-size review
- index fillfactor
- source/profile-only B-tree deduplicate_items
- correct USING INDEX TABLESPACE grammar for PK/UK constraint placement

Intentionally not auto-selected:
- autovacuum storage parameters (operational tuning)
- table access method changes
- column STORAGE/COMPRESSION and detailed TOAST/autovacuum tuning beyond `toast_tuple_target`
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

Intentionally not auto-selected:
- CLUSTERED / NONCLUSTERED organization
- ONLINE, RESUMABLE, SORT_IN_TEMPDB, MAXDOP (deployment/build choices)
- XML_COMPRESSION unless a later version/type-aware phase is added
- TEXTIMAGE_ON / FILESTREAM_ON / partition scheme provisioning

## Db2 for z/OS

Implemented table placement/options:
- active IN database.tablespace preservation
- physical-only table block; non-storage semantics such as AUDIT, DATA CAPTURE, CCSID, VOLATILE, APPEND and RESTRICT ON DROP are deliberately excluded

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

Intentionally not auto-selected:
- CREATE TABLESPACE / STOGROUP provisioning and table-space FREEPAGE/PCTFREE/BUFFERPOOL/COMPRESS/DSSIZE
- CLUSTER (data organization design)
- COPY (recovery policy)
- DEFINE / DEFER (deployment)
- partition-specific FREEPAGE/PCTFREE/GBPCACHE/DSSIZE details (advanced/partitioning scope)
- LOB auxiliary table/tablespace provisioning

## Source-value policy

Word/JSON is evidence, not truth. SchemaForge:
1. retains a source physical value only when it passes the target-specific check available at generation time;
2. never silently clamps an invalid physical source value;
3. writes `[SOURCE PHYSICAL ISSUE]` in the same SQL physical block for invalid/inapplicable values;
4. uses environment placeholders when a value cannot be safely inferred;
5. keeps new recommendations inside activation-ready block comments; existing active placement remains active.

## Known Phase-1 granularity boundary

Source/profile physical options are currently attached to the table-level physical option map. The renderer can distinguish table, PK/UK backing-index, standalone index, key columns and UNIQUE context, but it does not yet carry a separate per-index physical-option map. Therefore Phase 1 must not pretend to preserve different source tuning values for two indexes of the same table when the source model cannot represent that distinction. This is reported/documented rather than guessed.
