# Physical DDL Reference

## 1. Frozen baseline

Physical DDL model/rendering work P0-P7 is frozen at:

```text
Tests run: 376
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
```

P8 adds comparison only and does not change the P0-P7 physical DDL contract.

## 2. Scope model

Physical state is attached at the narrowest supported object scope:

```text
Table.physicalOptions
Column.physicalOptions
Index.physicalOptions
PrimaryKey.physicalOptions
UniqueKey.physicalOptions
```

Index creation-operation directives are separate:

```text
Index.buildOptions
```

Historical table-scoped index options may remain as compatibility fallback where existing renderers explicitly support that path.

## 3. Phase history

| Phase | Main contract |
|---|---|
| P0 | object-scoped physical options for index/PK/UK |
| P1 | Db2 for z/OS table-space physical profile |
| P2 | Oracle table/index logging, parallel, segment-related physical handling |
| P3 | PostgreSQL table/index physical options and method-aware candidates |
| P4 | SQL Server XML compression, organization, metadata-backed placement |
| P5 | separate index build-option channel |
| P6 | PostgreSQL column `STORAGE` / `COMPRESSION` |
| P7 | Db2 for z/OS explicit index `DEFINE` / `DEFER` build options |

## 4. Oracle

Current table candidates include:

- tablespace placement / approved project placement convention;
- PCTFREE;
- explicit PCTUSED with applicability review;
- INITRANS;
- supported table compression forms;
- explicit LOGGING/NOLOGGING;
- explicit PARALLEL/NOPARALLEL/degree;
- explicit segment-creation intent with review safeguards.

Current index/PK/UK candidates include:

- index tablespace placement;
- PCTFREE;
- INITRANS;
- supported index compression;
- LOGGING/NOLOGGING;
- PARALLEL/NOPARALLEL/degree.

Current explicit index build path includes Oracle `ONLINE`.

Not represented generically: LOB segment storage, partition storage, IOT-specific storage.

## 5. PostgreSQL

Current table candidates include:

- explicit tablespace;
- fillfactor;
- `toast_tuple_target` from explicit evidence;
- table `parallel_workers` from explicit evidence.

Current index candidates include:

- tablespace;
- fillfactor;
- B-tree `deduplicate_items`;
- method evidence for btree/hash/gist/spgist/gin/brin;
- GiST buffering;
- GIN fastupdate and pending-list limit;
- BRIN pages-per-range and autosummarize.

Column physical options include:

- `STORAGE PLAIN|EXTERNAL|EXTENDED|MAIN|DEFAULT`;
- `COMPRESSION pglz|lz4|default` with applicability guards.

Current explicit index build path includes PostgreSQL `CONCURRENTLY`.

Not represented generically: autovacuum tuning, explicit table access-method design, partition storage.

## 6. SQL Server

Current table candidates include:

- filegroup/data-space placement;
- data compression;
- XML compression where explicitly supported.

Current index/PK/UK candidates include:

- filegroup/data-space placement;
- explicit CLUSTERED/NONCLUSTERED organization;
- PAD_INDEX;
- FILLFACTOR;
- IGNORE_DUP_KEY with applicability review;
- STATISTICS_NORECOMPUTE;
- STATISTICS_INCREMENTAL where explicitly supported;
- ALLOW_ROW_LOCKS / ALLOW_PAGE_LOCKS;
- data compression;
- XML compression;
- OPTIMIZE_FOR_SEQUENTIAL_KEY from explicit evidence.

Current explicit build options include:

- ONLINE;
- RESUMABLE;
- MAX_DURATION;
- MAXDOP;
- SORT_IN_TEMPDB.

Not represented generically: `TEXTIMAGE_ON`, `FILESTREAM_ON`, partition scheme/partition-specific placement.

## 7. Db2 for z/OS

Current table/table-space profile includes supported validated candidates for:

- database/tablespace placement;
- BUFFERPOOL;
- DSSIZE;
- SEGSIZE;
- FREEPAGE;
- PCTFREE / PCTFREE FOR UPDATE;
- COMPRESS;
- GBPCACHE;
- CLOSE;
- DEFINE;
- LOCKSIZE / LOCKMAX;
- MAXROWS;
- MEMBER CLUSTER;
- INSERT ALGORITHM;
- TRACKMOD;
- LOGGED / NOT LOGGED;
- STOGROUP;
- PRIQTY / SECQTY;
- ERASE.

Current index/PK/UK candidates include supported validated forms of:

- PADDED/NOT PADDED;
- STOGROUP;
- PRIQTY/SECQTY;
- ERASE;
- FREEPAGE;
- PCTFREE;
- GBPCACHE;
- COMPRESS;
- BUFFERPOOL;
- CLOSE;
- PIECESIZE.

Current explicit P7 index build options include:

- DEFINE YES/NO;
- DEFER YES/NO.

`DEFINE NO` requires explicit STOGROUP evidence in the current safe rendering rule.

Not represented generically: executable tablespace/stogroup provisioning, partition-specific clauses, `CLUSTER`, `COPY`, LOB auxiliary provisioning.

## 8. Rendering policy

- explicit valid source/profile values may become active DDL when the current renderer contract permits it;
- invalid/inapplicable values are surfaced as physical issues/review comments rather than silently clamped;
- environment/workload settings are not invented;
- provisioning of shared storage objects remains outside per-table physical rendering;
- operational build directives stay separate from persistent physical state.

For detailed option-by-option coverage, see `docs/physical-phase1-coverage-matrix.md`.
