# R11.1 - Db2 z/OS 12 physical baseline activation

R11.1 is a focused Db2 z/OS generator change derived from comparison with a real Db2 Administration Tool (ADB2GEN) Db2 12 extraction.

## Active baseline

Table DDL emits `AUDIT NONE`, `DATA CAPTURE NONE`, `NOT VOLATILE`, and `APPEND NO`.

Index DDL emits the documented baseline `FREEPAGE 0`, `PCTFREE 10`, `GBPCACHE CHANGED`, `COMPRESS NO`, `INCLUDE NULL KEYS`, `CLOSE YES`, and `DEFER NO`. Explicit safe source/profile values take precedence.

When explicit evidence exists, index `STOGROUP`, `PRIQTY`, `SECQTY`, `ERASE`, `BUFFERPOOL`, `COPY`, and `CLUSTER` are executable. Environment/capacity/recovery choices without explicit evidence remain in a single compact DBA review block. `PIECESIZE` remains review-only because its validity depends on table-space/index organization context.

## Column syntax

Db2 z/OS now renders non-null defaults as `NOT NULL WITH DEFAULT <expression>`, matching Db2 z/OS grammar and ADB2GEN output. This ordering change is dialect-specific.

## Post-DDL package action

Each Db2 z/OS script ends with a commented DBA hint to review dependent packages and use `REBIND PACKAGE(*)` only when appropriate. SchemaForge does not execute REBIND.

## Explicitly deferred

This patch does not implement bare `WITH DEFAULT`, `FOR BIT DATA`, or reuse of a catalog/source-named enforcing PK/UK index. Those require separate canonical/evidence changes and are not guessed in R11.1.
