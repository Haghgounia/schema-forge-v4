# PostgreSQL PG-P10 - External Database Audit Qualification

PG-P10 qualifies the existing production `SchemaConformanceAuditService` against PostgreSQL objects created directly through JDBC outside SchemaForge.

The live fixture contains:

- `P10_PARENT` with PK and UK;
- `P10_CHILD` with a valid FK to `P10_PARENT`;
- `P10_NO_PK` with no primary key and a PostgreSQL `NUMERIC` column with unspecified precision.

Acceptance criteria:

- the production PostgreSQL metadata repository reads all three tables;
- the materialized FK is visible and produces no FK-integrity finding;
- table audit and schema audit both execute;
- the missing PK is reported;
- the existing datatype-compatibility warning for unspecified `NUMERIC` precision is reported;
- the audit does not add, drop, rename, or mutate any table/FK;
- fixture cleanup succeeds.

Legacy documentation-only FK inconsistencies remain non-blocking evidence issues according to the project decision rule. This phase adds no new product feature or mapping; it only qualifies existing audit behavior for PostgreSQL.
