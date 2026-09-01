# DEFECT-NUM-001 — Runtime safety for unspecified exact-numeric precision

This overlay closes the runtime-failure portion of NUM-001.

Canonical contract:
- Missing numeric precision remains `null` / UNSPECIFIED.
- No `0` or `-1` sentinel is introduced because `DataType` intentionally rejects non-positive precision.

Fallbacks:
- Db2 LUW: DECIMAL(31,0)
- Db2 z/OS: DECIMAL(31,0)
- SQL Server: DECIMAL(38,0)
- MySQL: DECIMAL(65,0)

Oracle and PostgreSQL already accept unspecified exact-numeric precision and are unchanged.

The SQL HINT portion is intentionally not implemented in the mapper return value because `sqlType()`
is also used by compatibility/FK logic. The hint must be added through SchemaForge's SQL issue rendering
path so it cannot corrupt datatype comparisons.
