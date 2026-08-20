# MySQL P2-R5 — Cross-source canonical/DB2 conflict audit

This is an audit-only integration test. It does not modify production source, canonical JSON, or DDL generation policy.

After P2-R4, many remaining blockers have exact DB2 schema/table/column metadata but the DB2 datatype conflicts with the canonical datatype. P2-R5 checks whether independent historical canonical snapshots for the exact same schema/table/column unanimously map to the same MySQL datatype as the DB2 catalog value.

Classifications:

- `CROSS_SOURCE_EXACT_CONSENSUS`: every mappable historical observation agrees exactly with the DB2 MySQL mapping.
- `CROSS_SOURCE_SAME_FAMILY_DIFFERENT_DETAILS`: family agrees but length/precision/fractional-seconds details differ; do not auto-apply.
- `CROSS_SOURCE_CONFLICT`: historical evidence disagrees with DB2; do not auto-apply.
- `CROSS_SOURCE_NO_HISTORICAL_EVIDENCE`: DB2 conflict exists but there is no independently mappable historical observation.
- `CROSS_SOURCE_INSUFFICIENT_EVIDENCE`: evidence count is below the requested threshold.

Only `CROSS_SOURCE_EXACT_CONSENSUS` is intended to become a candidate for a later evidence-backed overlay. P2-R5 itself applies nothing.
