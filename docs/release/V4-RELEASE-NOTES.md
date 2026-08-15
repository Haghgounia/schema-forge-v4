# SchemaForge V4 Release Notes

## Final baseline

SchemaForge V4 is frozen at baseline `SCHEMAFORGE-V4-FINAL-20260814` after a successful 270-test regression run with zero failures/errors and extensive real-database validation on Oracle, PostgreSQL, and Microsoft SQL Server.

## Major capabilities in the baseline

- Legacy `.doc` / `.docx` table specification parsing into a DBMS-neutral canonical model.
- Enterprise Architect XMI import into the same canonical domain.
- Versioned canonical JSON snapshot cache to decouple expensive Word parsing from iterative DDL development.
- Oracle, PostgreSQL, and SQL Server DDL generation.
- Collision-safe output naming for source documents that normalize to the same filename.
- Historical execution mode for independent validation of legacy table versions.
- Integrated FK analysis with strict one-definition-per-table production semantics.
- Integrated deployment planning that creates tables before cross-table foreign keys.
- Cross-dialect FK compatibility validation, including SQL Server precision/scale/length constraints.
- Integrated SQL rendering for Oracle, PostgreSQL, and SQL Server.
- Dependency coverage reporting for missing targets, self-references, and historical aggregate cycle candidates.

## Important fixes validated during finalization

- Oracle reserved identifiers and legacy datatype/default edge cases.
- PostgreSQL schema-qualified `CREATE INDEX` names.
- SQL Server decimal/timestamp bounds.
- SQL Server cleanup syntax and dependency-aware cleanup.
- SQL Server historical FK skip / derivative `CHECK CONSTRAINT` handling.
- SQL*Plus `PROMPT` handling in Oracle integrated execution.
- Output filename collisions caused by source filenames differing only by trailing spaces before `.doc`.
- SQL Server integrated FK column type mismatch detection before execution.

## Validation summary

Historical per-source DDL validation:

- Oracle: PASS
- PostgreSQL: PASS
- SQL Server: PASS

Integrated FK validation on the same selected real canonical model:

- 2-table / 1-FK pilot: PASS on all three DBMS.
- 15-table / 13-FK large pilot: PASS on all three DBMS.

Dependency coverage of the historical corpus found five distinct self-reference relations and two multi-table cycle candidates. Both cycle candidates were proven to be historical-aggregate-only and do not coexist in a one-version-per-table integrated input.

## Compatibility note

SchemaForge intentionally does not hide cross-DBMS model incompatibilities by rewriting canonical column definitions. When a valid canonical FK maps to incompatible SQL Server column types, integrated validation reports `SQLSERVER_FK_TYPE_MISMATCH` so the model owner can make an explicit decision.

## Upgrade / continuation rule

This package is the V4 baseline. New functionality should be introduced after this baseline rather than changing the frozen behavior in place. Any subsequent work should preserve the full regression suite and the three-DBMS historical/integrated validation contracts.
