# SQL Server Final Closure

SQL Server V4 is closed by retained, evidence-only acceptance data. The closure gate does not
connect to SQL Server or regenerate any source/canonical/DDL artifact.

## Current R7.2 corpus

- canonical snapshots: 5321
- SQL Server generated: 4703
- evidence-blocked mapping: 618
- generation failures: 0

## Current historical live replay

The 2026-08-31 replay used Microsoft SQL Server 16.00.4265 and schema `TSTSHMA`:

- files: 4703 / 4703
- statements: 128865 / 128865 succeeded
- actionable failures: 0
- ignored failures: 0
- cleanup: 4703 / 4703 succeeded
- cleanup failures: 0

Historical mode intentionally skips cross-table FK statements because historical definitions are
executed independently.

## FK runtime evidence

The retained integrated FULL pilot provides the FK runtime proof independently from historical mode:

- pilot tables: 15
- resolved physical FKs: 13
- FK blockers: 0
- SQL Server statements: 274
- failures: 0
- cleanup removed 13 existing FKs before dropping the pilot tables

No synthetic PK/UK/FK creation is permitted to hide source-model gaps.

## Migration M2

Retained SQL Server M2 live evidence:

- statements: 20
- residual changes: 0
- data preserved: true
- cleanup: true

## Closure

`SqlServerFinalClosureTest` freezes these retained facts as `SQL Server status = CLOSED BASELINE`.
