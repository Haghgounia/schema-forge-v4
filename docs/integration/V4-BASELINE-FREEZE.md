# SchemaForge V4 Baseline Freeze

This document defines the freeze gate for the current V4 multi-database DDL and integrated-FK
baseline. It does not declare the baseline frozen by itself; the final local Maven regression and
canonical dependency coverage run must complete successfully in the project environment.

## Proven database execution baseline

The project has already been exercised against real databases using the generated canonical output:

- Oracle HISTORICAL execution: all executed statements succeeded after the Oracle generator fixes;
- PostgreSQL HISTORICAL execution: 4,768 scripts, 120,614 executed statements, zero failures;
- SQL Server HISTORICAL execution: all 4,768 scripts validated after the runner-only FK/CHECK skip fix;
- integrated pilot: one real physical FK passed in FULL mode on Oracle, PostgreSQL and SQL Server;
- integrated large pilot: 15 tables and 13 physical FKs passed in FULL mode on Oracle, PostgreSQL
  and SQL Server.

These execution results prove syntax/runtime behavior for the exercised baseline. They do not change
the production input contract: integrated deployment requires one canonical definition per qualified
table.

## Freeze gates

Before tagging/freezing the baseline:

1. run the complete ordinary Maven regression suite:

   ```bat
   mvnw.cmd clean test
   ```

2. run historical canonical dependency coverage:

   ```bat
   mvnw.cmd -Dtest=CanonicalJsonDependencyCoverageIT test ^
     -Dschemaforge.dependency.inputDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson" ^
     -Dschemaforge.dependency.outputDir="D:\get-git-doc-files-master\SchemaForgeDependencyCoverage" ^
     -Dschemaforge.dependency.failOnSnapshotErrors=true
   ```

3. inspect the coverage summary and the self-reference/cycle reports. Aggregate historical cycles
   are coverage candidates only; if a cycle needs runtime proof, construct one unique-version
   integrated pilot containing exactly that cycle and execute it in FULL mode on the selected DBMSs.

## Stable boundaries

The following areas are frozen unless a regression proves a real defect:

- Legacy Word parser;
- canonical JSON snapshot contract;
- Oracle DDL generator/dialect;
- PostgreSQL DDL generator/dialect;
- SQL Server DDL generator/dialect;
- HISTORICAL execution semantics;
- integrated FK analyzer/planner/renderer ordering.

New work should prefer additive validation/reporting rather than changing these proven paths.
