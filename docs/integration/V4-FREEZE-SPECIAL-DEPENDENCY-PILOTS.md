# SchemaForge V4 Freeze - Special Dependency Pilots

This freeze step closes the two remaining integrated-FK coverage cases: self-referencing foreign keys and multi-table dependency cycles.

## Scope

The implementation is test-only. Production integrated deployment continues to require one definition per `schema.table`; no historical version is selected automatically in production.

The special pilot runner reads the existing canonical JSON cache and never opens Word documents.

## Self-reference pilot

The runner searches the historical corpus for a physical self-referencing FK, resolves any additional FK closure required by that selected table, validates the canonical FK model, and requires successful rendering for every requested DBMS.

If a compatible self-reference is found, scripts are written below:

```text
<output>/self-reference/oracle/integrated-self-reference.oracle.sql
<output>/self-reference/postgresql/integrated-self-reference.postgresql.sql
<output>/self-reference/sqlserver/integrated-self-reference.sqlserver.sql
```

## Cycle assessment

Historical aggregate dependency cycles are not assumed to be production cycles. For every aggregate cycle group, the runner attempts to choose exactly one historical definition for each member table and preserve the cycle in one canonical schema.

Possible statuses are:

- `DEPLOYABLE_CYCLE`: one-version-per-table cycle exists, canonical FK validation passes, and all requested DBMS render successfully.
- `HISTORICAL_AGGREGATE_ONLY`: the cycle exists only when edges from incompatible historical definitions are aggregated.
- `COEXISTING_CANONICAL_BLOCKED`: a coexisting cycle exists but canonical FK validation blocks deployment.
- `COEXISTING_PORTABILITY_BLOCKED`: a coexisting canonical cycle exists but at least one requested DBMS rejects the FK mapping at render validation.
- `INCONCLUSIVE_COMBINATION_LIMIT`: the configured deterministic search limit was reached; increase the limit before freezing V4.

Only `DEPLOYABLE_CYCLE` produces database pilot SQL. A deployable cycle is written under a numbered directory such as:

```text
<output>/cycle-01/oracle/integrated-cycle-01.oracle.sql
<output>/cycle-01/postgresql/integrated-cycle-01.postgresql.sql
<output>/cycle-01/sqlserver/integrated-cycle-01.sqlserver.sql
```

## Regression test

```bat
mvnw.cmd clean -Dtest=SpecialDependencyPilotSelectorTest test
```

Expected result:

```text
Tests run: 4
Failures: 0
Errors: 0
BUILD SUCCESS
```

## Canonical corpus analysis and pilot generation

```bat
mvnw.cmd -Dtest=CanonicalJsonSpecialDependencyPilotIT test ^
  -Dschemaforge.special.pilot.inputDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson" ^
  -Dschemaforge.special.pilot.outputDir="D:\get-git-doc-files-master\SchemaForgeSpecialDependencyPilot" ^
  -Dschemaforge.special.pilot.platforms=oracle,postgresql,sqlserver ^
  -Dschemaforge.special.pilot.maxTables=20 ^
  -Dschemaforge.special.pilot.maxCycleCombinations=20000
```

This command performs no database execution.

Reports include selected snapshot/source rows, per-cycle classification, FK-analysis findings, and a summary.

## Freeze decision

- A deployable self-reference should be executed in `FULL` mode on Oracle, PostgreSQL, and SQL Server.
- Every cycle classified `DEPLOYABLE_CYCLE` should likewise be executed in `FULL` mode on all three DBMS.
- `HISTORICAL_AGGREGATE_ONLY` requires no database execution because no normal one-version-per-table input contains that cycle.
- `COEXISTING_CANONICAL_BLOCKED` or `COEXISTING_PORTABILITY_BLOCKED` is a real model/portability finding and must be recorded before freeze.
- `INCONCLUSIVE_COMBINATION_LIMIT` is not a freeze-safe result.
