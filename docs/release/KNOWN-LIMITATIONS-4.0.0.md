# SchemaForge V4 4.0.0 - Known Limitations

## Db2 z/OS live execution

Db2 z/OS DDL generation, mapping, contracts, and offline regression coverage are included in V4. Live execution validation remains deferred because the required Db2 z/OS environment is not currently available.

This is an environment-dependent deferred validation item and is not treated as a V4 GA code defect.

## Live metadata-dependent artifacts

Some optional comparison, migration, or metadata-derived artifacts can be reported as `SKIPPED` when a configured live database, schema, or table is unavailable. Under the frozen artifact contract, `SKIPPED` by itself does not make a request `PARTIAL_SUCCESS`.

## Schema Conformance warnings

Schema Conformance Audit is advisory/read-only. A report can have `compliant=false` solely because warnings are present. Warnings do not imply that the audit failed to execute.
