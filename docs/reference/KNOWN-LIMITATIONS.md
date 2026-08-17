# Known Limitations and Deferred Scope

This document distinguishes intentionally deferred model boundaries from defects.

## 1. Oracle LOB storage

Not modeled generically:

- SecureFiles/BasicFiles;
- LOB-specific tablespace placement;
- in-row/out-of-row storage;
- CHUNK;
- PCTVERSION/FREEPOOLS;
- LOB compression/deduplication/encryption/cache policies.

Reason: these are LOB/segment-scoped semantics and require a dedicated LOB model rather than generic table/index physical maps.

## 2. Partition/subpartition physical model

Not modeled generically across the four vendors:

- partition-specific tablespace/filegroup placement;
- partition-specific compression;
- partition schemes;
- Db2 partition organization clauses;
- Oracle partition/subpartition storage attributes.

Reason: object-level maps cannot safely represent heterogeneous partition state.

## 3. SQL Server large-value storage

Deferred:

- `TEXTIMAGE_ON`;
- `FILESTREAM_ON`;
- FILESTREAM-specific column/storage semantics.

Reason: these need explicit large-object/FILESTREAM modeling and cannot be represented safely as ordinary table physical properties.

## 4. PostgreSQL table access method

Explicit table access-method design remains deferred.

Reason: it is a distinct storage architecture choice, not just another generic reloption.

## 5. PostgreSQL operational maintenance tuning

Autovacuum and detailed TOAST/autovacuum tuning remain outside the frozen generic physical profile, except for already modeled `toast_tuple_target` where explicit evidence is supported.

Reason: operational maintenance policy should not be inferred as static schema design.

## 6. Db2 recovery and organization semantics

Deferred from generic physical options:

- `COPY`;
- clustering-index design (`CLUSTER` / `NOT CLUSTER`);
- null-key inclusion/exclusion semantics;
- partition-specific organization/provisioning.

Reason: these affect recovery policy or logical/data-organization semantics beyond generic tuning.

## 7. Storage-object provisioning

SchemaForge does not generally provision shared infrastructure objects as part of each table script:

- tablespaces;
- filegroups;
- stogroups;
- vendor storage infrastructure.

The current physical model focuses on safe object placement/reference and DBA-visible review, not infrastructure lifecycle management.

## 8. Historical build-operation reverse engineering

Current database metadata is not used to infer historical create/rebuild options such as:

- Oracle ONLINE;
- PostgreSQL CONCURRENTLY;
- SQL Server ONLINE/RESUMABLE/MAXDOP/SORT_IN_TEMPDB;
- Db2 build-operation choices.

## 9. Column physical comparison

The frozen current canonical model provides comparable persistent column physical semantics only for PostgreSQL `STORAGE` / `COMPRESSION`.

Oracle, SQL Server, and Db2 for z/OS therefore do not receive invented generic column-physical rows.

## 10. Environment-dependent live database tests

The normal regression baseline contains three skipped environment-dependent live database execution tests. They require explicit database configuration/credentials and are intentionally not part of every local Maven run.
