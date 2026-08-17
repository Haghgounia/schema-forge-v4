# Database Support Matrix

## 1. Platform-level support

| Capability | Oracle | PostgreSQL | Db2 for z/OS | SQL Server |
|---|---|---|---|---|
| DDL generation | Yes | Yes | Yes | Yes |
| JDBC metadata repository | Yes | Yes | Yes | Yes |
| Logical/object Excel comparison | Yes | Yes | Yes | Yes |
| Table physical metadata comparison | Yes | Yes | Yes | Yes |
| Index/PK/UK physical metadata comparison | Yes | Yes | Yes | Yes |
| Column physical metadata comparison | No generic mapping | `STORAGE` / `COMPRESSION` | No generic mapping | No generic mapping |
| Metadata-based CRUD generator | Oracle package | No | No | Stored procedures |
| Standard grants | Yes | Yes | Yes | Yes |
| Table/column comments | Yes | Yes | Yes | Yes |

`No generic mapping` means SchemaForge intentionally does not invent a column-scoped physical abstraction where the current frozen domain model has no safe vendor-neutral representation.

## 2. Dialect feature contract

The current `DialectFeature` declarations provide this feature-level view:

| Dialect feature | Oracle | PostgreSQL | Db2 for z/OS | SQL Server |
|---|---|---|---|---|
| Sequence | Yes | Yes | Yes | Yes |
| Identity column | Yes | Yes | Yes | Yes |
| Generated column | Yes | Yes | Yes | Yes |
| Table comment | Yes | Yes | Yes | Yes |
| Column comment | Yes | Yes | Yes | Yes |
| Grant | Yes | Yes | Yes | Yes |
| Expression index | Yes | Yes | No declared capability | No declared capability |
| Deferrable constraint | Yes | Yes | No declared capability | No declared capability |
| Index INCLUDE | No declared capability | Yes | No declared capability | Yes |
| Partial/filtered index | No declared capability | Yes | No declared capability | Yes |

This table describes the explicit V4 dialect capability declarations, not every SQL feature the underlying DBMS can theoretically support.

## 3. Physical DDL coverage summary

### Oracle

Table and index/backing-index physical rendering includes validated placement and supported persistent attributes such as PCTFREE/INITRANS, compression, logging/parallel state, plus explicit build options where modeled.

LOB-specific storage, partition-specific storage, and IOT-specific storage remain outside the generic model.

### PostgreSQL

Supports table/index tablespace and supported storage parameters, method-aware index physical options, and column `STORAGE`/`COMPRESSION`. Operational autovacuum tuning and explicit table access-method design are outside the frozen generic scope.

### SQL Server

Supports filegroup/data-space placement, rowstore organization, fillfactor/padding/locking/statistics/compression, XML compression where explicitly supported, and separate index build options. `TEXTIMAGE_ON`, `FILESTREAM_ON`, and partition schemes remain deferred.

### Db2 for z/OS

Supports table-space profile/review data and index/backing-index physical candidates including bufferpool, DSSIZE/SEGSIZE, PCTFREE/FREEPAGE, compression, GBPCACHE, placement/storage-group information, and explicit P7 `DEFINE/DEFER` build options. Provisioning, partition organization, `COPY`, and `CLUSTER` remain deferred.

## 4. Metadata comparison coverage summary

P8 acquisition is intentionally limited to persistent current-state metadata that can be compared safely.

- Oracle: table and index/backing-index actual physical state.
- PostgreSQL: table, index/backing-index, and column storage/compression actual state.
- SQL Server: table and index/backing-index actual physical state, with mixed partition state reported as `REVIEW` where it cannot be collapsed safely.
- Db2 for z/OS: table-space and index/backing-index current state, excluding unsafe reconstruction of historical allocation/recovery choices.
