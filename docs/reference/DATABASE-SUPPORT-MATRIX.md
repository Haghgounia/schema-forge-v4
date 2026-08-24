# Database Support Matrix

This matrix describes the current frozen SchemaForge V4 source baseline. `Implemented` means a code path exists and is regression-targeted; `Live pilot available` means an opt-in live integration harness exists and does not by itself certify the latest source revision.

## 1. Platform-level support

| Capability | Oracle | PostgreSQL | Db2 for z/OS | SQL Server | MySQL |
|---|---|---|---|---|---|
| Logical DDL generation | Yes | Yes | Yes | Yes | Yes |
| JDBC metadata repository | Yes | Yes | Yes* | Yes | Yes |
| Logical/object Excel comparison | Yes | Yes | Yes | Yes | Yes |
| Table physical DDL | Yes | Yes | Yes | Yes | Deferred |
| Index/PK/UK physical DDL | Yes | Yes | Yes | Yes | Deferred |
| Table physical metadata comparison | Yes | Yes | Yes | Yes | Deferred |
| Index/PK/UK physical metadata comparison | Yes | Yes | Yes | Yes | Deferred |
| Column physical metadata comparison | No generic mapping | `STORAGE` / `COMPRESSION` | No generic mapping | No generic mapping | Deferred |
| ALTER/Flyway M2 | Yes | Yes | Yes | Yes | Yes |
| M2 live pilot harness | Yes | Yes | Yes* | Yes | Yes |
| Metadata-based CRUD generator | Oracle package | No | No | Stored procedures | No |
| Standard configured table grants | Yes | Yes | Yes | Yes | Yes |
| Table/column comments | Yes | Yes | Yes | Yes | Yes |

`*` Db2 for z/OS JDBC/live execution uses the external IBM JCC environment; the driver is not bundled.

`No generic mapping` means SchemaForge intentionally does not invent a column-scoped physical abstraction where the current domain model has no safe vendor-neutral representation.

## 2. Dialect feature contract

`DialectFeature` is the active capability contract used by `Dialect`, `DdlGenerator`, and migration rendering.

| Dialect feature | Oracle | PostgreSQL | Db2 for z/OS | SQL Server | MySQL |
|---|---|---|---|---|---|
| Sequence | Yes | Yes | Yes | Yes | No |
| Identity column | Yes | Yes | Yes | Yes | Yes |
| Generated column | Yes | Yes | Yes | Yes | Yes |
| Table comment | Yes | Yes | Yes | Yes | Yes |
| Column comment | Yes | Yes | Yes | Yes | Yes |
| Grant | Yes | Yes | Yes | Yes | Yes |
| Expression index | Yes | Yes | No declared capability | No declared capability | Yes |
| Deferrable constraint | Yes | Yes | No declared capability | No declared capability | No declared capability |
| Index INCLUDE | No declared capability | Yes | No declared capability | Yes | No declared capability |
| Partial/filtered index | No declared capability | Yes | No declared capability | Yes | No declared capability |

This table describes SchemaForge's explicit V4 capability declarations, not every SQL feature the underlying database engines can theoretically support.

### MySQL sequence rule

MySQL does not declare standalone sequence support. A legacy sequence reference that only backs a logical identity column is adapted through MySQL `AUTO_INCREMENT`; an independent sequence that cannot be represented safely is rejected instead of being approximated.

## 3. Logical object coverage

| Logical object / behavior | Oracle | PostgreSQL | Db2 for z/OS | SQL Server | MySQL |
|---|---|---|---|---|---|
| CREATE TABLE | Yes | Yes | Yes | Yes | Yes |
| PK | Yes | Yes | Yes | Yes | Yes |
| UK | Yes | Yes | Yes | Yes | Yes |
| FK | Yes | Yes | Yes | Yes | Yes |
| CHECK | Yes | Yes | Yes | Yes | Yes |
| Standalone index | Yes | Yes | Yes | Yes | Yes |
| Identity | Yes | Yes | Yes | Yes | Yes |
| Generated/computed column | Yes | Yes | Yes | Yes | Yes |
| Standard configured GRANT | Yes | Yes | Yes | Yes | Yes |

## 4. ALTER/Flyway M2 coverage

The current M2 path supports semantic diff and DBMS-specific rendering for:

| Migration capability | Oracle | PostgreSQL | Db2 for z/OS | SQL Server | MySQL |
|---|---|---|---|---|---|
| Add/drop column | Yes | Yes | Yes | Yes | Yes |
| Type/nullability/default change | Yes | Yes | Yes | Yes | Yes |
| PK change | Yes | Yes | Yes | Yes | Yes |
| UK change | Yes | Yes | Yes | Yes | Yes |
| FK change | Yes | Yes | Yes | Yes | Yes |
| CHECK change | Yes | Yes | Yes | Yes | Yes |
| Standalone index change | Yes | Yes | Yes | Yes | Yes |
| SAFE / REVIEW / DESTRUCTIVE classification | Yes | Yes | Yes | Yes | Yes |
| Explicit destructive confirmation | Yes | Yes | Yes | Yes | Yes |
| Rename inference | Forbidden | Forbidden | Forbidden | Forbidden | Forbidden |
| Physical-option migration | Deferred | Deferred | Deferred | Deferred | Deferred |
| Incoming FK planning from other tables | Deferred | Deferred | Deferred | Deferred | Deferred |

## 5. Physical DDL coverage summary

### Oracle

Table and index/backing-index physical rendering includes validated placement and supported persistent attributes such as PCTFREE/INITRANS, compression, logging/parallel state, plus explicit build options where modeled.

LOB-specific storage, partition-specific storage, and IOT-specific storage remain outside the generic model.

### PostgreSQL

Supports table/index tablespace and supported storage parameters, method-aware index physical options, and column `STORAGE`/`COMPRESSION`. Operational autovacuum tuning and explicit table access-method design are outside the frozen generic scope.

### SQL Server

Supports filegroup/data-space placement, rowstore organization, fillfactor/padding/locking/statistics/compression, XML compression where explicitly supported, and separate index build options. `TEXTIMAGE_ON`, `FILESTREAM_ON`, and partition schemes remain deferred.

### Db2 for z/OS

Supports table-space profile/review data and index/backing-index physical candidates including bufferpool, DSSIZE/SEGSIZE, PCTFREE/FREEPAGE, compression, GBPCACHE, placement/storage-group information, and explicit P7 `DEFINE/DEFER` build options. Provisioning, partition organization, `COPY`, and `CLUSTER` remain deferred.

### MySQL

The JDBC repository captures MySQL-specific evidence such as engine, collation, native column type, and index type. This evidence is not yet promoted into a frozen MySQL physical design contract. There is currently no `MySqlPhysicalRenderer` and no MySQL branch in the frozen physical comparison contract; SchemaForge therefore reports MySQL physical design as deferred rather than guessing vendor-specific intent.

## 6. Metadata comparison coverage summary

Current acquisition is intentionally limited to persistent current-state metadata that can be compared safely.

- Oracle: logical metadata plus table and index/backing-index physical state.
- PostgreSQL: logical metadata plus table, index/backing-index, and column storage/compression state.
- SQL Server: logical metadata plus table and index/backing-index physical state, with mixed partition state reported as `REVIEW` where it cannot be collapsed safely.
- Db2 for z/OS: logical metadata plus table-space and index/backing-index current state, excluding unsafe reconstruction of historical allocation/recovery choices.
- MySQL: logical metadata is comparison-enabled; MySQL-specific physical evidence is acquired but is not yet part of the frozen expected-vs-actual physical workbook contract.
