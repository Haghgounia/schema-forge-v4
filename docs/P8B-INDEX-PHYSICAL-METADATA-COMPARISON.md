# P8-B - Index Physical Metadata Comparison

P8-B extends the P8-A expected-vs-actual physical comparison to ordinary indexes and to the backing indexes of PRIMARY KEY and UNIQUE constraints.

## Scope

P8-B is comparison-only. JDBC metadata is captured on the database-side canonical objects and is used by the Excel comparison writer. It is not promoted into design intent and does not feed generated DDL.

The workbook adds `INDEX_PHYSICAL_COMPARE` with columns:

`SCOPE | OBJECT | PROPERTY | EXPECTED | ACTUAL | STATUS | NOTE`

`SCOPE` is one of `INDEX`, `PRIMARY_KEY`, or `UNIQUE_KEY`.

Statuses remain: `MATCH`, `MISMATCH`, `NOT_SPECIFIED`, `NOT_AVAILABLE`, and `REVIEW`.

## Vendor acquisition

### Oracle

`ALL_INDEXES` supplies persistent backing-index state such as tablespace, PCTFREE, INITRANS, logging, compression/prefix length, and degree. PRIMARY KEY and UNIQUE constraints use the index identified by `ALL_CONSTRAINTS.INDEX_OWNER/INDEX_NAME`.

### PostgreSQL

Backing and ordinary indexes use `pg_class`, `pg_am`, `pg_tablespace`, and `pg_index`/`pg_constraint`. Persistent access method, effective tablespace, and supported index `reloptions` are mapped. Operational options are excluded.

### SQL Server

`sys.indexes`, `sys.data_spaces`, `sys.stats`, and `sys.partitions` provide persistent index organization, filegroup/data space, fill factor, padding, duplicate-key/locking/statistics flags, compression and supported version-dependent state. Mixed partition compression becomes `REVIEW`, never an invented object-level value. SQL Server FILLFACTOR 0 and 100 compare as equivalent.

### Db2 for z/OS

`SYSIBM.SYSINDEXES` supplies persistent index state such as buffer pool, ERASE/CLOSE, PIECESIZE, PADDED, COMPRESS, STOGROUP, FREEPAGE, PCTFREE, and GBPCACHE. Current allocation quantities are not reverse-mapped to PRIQTY/SECQTY; COPY/CLUSTER and other recovery/logical semantics remain outside P8-B.

## Explicit exclusions

P8-B does not acquire `buildOptions`. ONLINE, CONCURRENTLY, RESUMABLE, MAXDOP, SORT_IN_TEMPDB, DEFINE/DEFER and similar creation-operation directives are not inferred from an existing database object.

No Domain, parser, snapshot, API, dialect, physical renderer, or DDL generation behavior is changed by P8-B.
