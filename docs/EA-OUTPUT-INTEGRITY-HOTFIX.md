# EA output integrity hotfix

Date: 2026-09-13

## Scope

This hotfix corrects Enterprise Architect output integrity without changing DBMS datatype mappings or guessing user intent.

### 1. FK association identity and ambiguity handling

EA XMI `UML:Association` elements are now tracked by their own `xmi.id` instead of being collapsed only by source FK operation name.

If more than one distinct association resolves to the same FK operation, SchemaForge:

- emits `EA_FK_ASSOCIATION_CONFLICT` as a validation ERROR,
- records all conflicting association ids, targets and column mappings,
- omits the ambiguous physical FK,
- never selects a winner automatically.

Stale EA association role names can still be recovered only when source columns identify exactly one FK operation. Ambiguous structural matches remain fail-closed.

### 2. EA run-all execution safety

EA `run-all` scripts no longer execute per-table files sequentially. They are rendered as one integrated deployment with explicit phases:

1. schema/sequences and all CREATE TABLE statements,
2. table-local checks/unique keys/indexes,
3. all physical foreign keys,
4. comments/metadata/grants.

This guarantees that a foreign key is never executed before all referenced tables exist, including cyclic schemas.

If a DBMS-specific integrated render is unsafe (for example an FK datatype incompatibility), that platform's RUN_SCRIPT artifact is recorded as `BLOCKED` with the exact reason instead of emitting a misleading executable script.

### 3. Cycle reporting

The EA manifest now reports only true strongly-connected cycle members (plus self loops). Tables that are merely downstream from a cycle are no longer reported as cyclic.

## Verification against the supplied EA model

Static verification of `Dps_Opn_14050622.xml` after the hotfix:

- tables: 89
- columns: 1256
- primary keys: 89
- unique keys: 83
- checks: 178
- indexes: 53
- unambiguous physical foreign keys: 96
- ambiguous FK operations: 6, reported as `EA_FK_ASSOCIATION_CONFLICT`
- true cycle: `DEPOSIT_OPENING_BATCH_ITEM <-> DEPOSIT_OPENING_REQUEST`

Integrated run-all rendering is ordered correctly for Oracle, PostgreSQL, DB2 z/OS, DB2 LUW, MySQL and MariaDB. SQL Server is intentionally blocked on this source model by the existing no-loss FK compatibility gate because `DEPOSIT_OPENING_AUDIT_EVENT.CHANNEL_CODE` renders as `VARCHAR(30)` while `REF_DEP_OPEN_CHANNEL.CHANNEL_CODE` renders as `VARCHAR(50)`.

## Non-goals

This hotfix does not repair or reinterpret contradictory EA associations, change canonical datatype mappings, rewrite FK columns, or alter the source EA model.
