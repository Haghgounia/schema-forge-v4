# Canonical JSON bulk mapping diagnostics

The canonical JSON corpus runner distinguishes executable DDL validation from dialect-mapping risk.

## Mapping findings

- Oracle: reports NUMBER precision clamping, TIMESTAMP precision clamping, and conservative character/RAW to LOB fallback performed by the existing Oracle dialect.
- PostgreSQL: reports explicit TIMESTAMP precision currently omitted by the existing PostgreSQL mapper. This diagnostic does not change production mapping behavior.
- SQL Server: reports DECIMAL precision and temporal precision bounding already used by the existing mapper.
- Db2 for z/OS: reports lossless numeric blockers per table/column before DDL generation. NUMBER without explicit precision and exact numerics with precision above 31 block Db2 generation rather than being silently approximated.

The diagnostics-only runner must not invent a Db2 numeric type when the canonical source cannot be represented losslessly.
