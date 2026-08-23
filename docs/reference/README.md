# SchemaForge V4 - Current Reference Documentation

This directory is the authoritative documentation entry point for the current SchemaForge V4 baseline.

Current source baseline: **SchemaForge V4 Consolidated Baseline 2026-08-22 (C5.3)**. The exact C5.3-R1 source is frozen after targeted, repair, and full regression verification. A later C5.3-R2 MySQL compatibility repair is pending Maven regression; it is not yet part of the official baseline.

The exact frozen source passed the user-verified clean full regression:

```text
Tests run: 492
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Finished: 2026-08-22T23:33:56-07:00
```

The four skips are configuration-gated directory execution tests. Opt-in live database `*IT` pilots are separate from the standard Surefire freeze result.

## Core reference set

1. [Architecture](ARCHITECTURE.md)
2. [Canonical domain model](CANONICAL-DOMAIN-MODEL.md)
3. [Inputs, outputs, and processing pipeline](INPUTS-OUTPUTS-PIPELINE.md)
4. [Database support matrix](DATABASE-SUPPORT-MATRIX.md)
5. [Physical DDL reference](PHYSICAL-DDL-REFERENCE.md)
6. [Physical metadata comparison](PHYSICAL-METADATA-COMPARISON.md)
7. [Excel comparison workbook reference](EXCEL-COMPARISON-REFERENCE.md)
8. [Evidence and no-guess policy](EVIDENCE-AND-NO-GUESS-POLICY.md)
9. [Known limitations and deferred scope](KNOWN-LIMITATIONS.md)
10. [Developer guide](DEVELOPER-GUIDE.md)
11. [Testing and regression baseline](TESTING-AND-BASELINE.md)
12. [Current release baseline](CURRENT-RELEASE-BASELINE.md)
13. [V4 consolidation execution plan](../roadmap/SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md)
14. [Consolidation candidate/repair/version history](../roadmap/CONSOLIDATION-VERSION-HISTORY.md)
15. [C6.1 Standard Artifact Manifest V1 design](../architecture/ARTIFACT-MANIFEST-C6.1.md)
16. [C5.3-R2 MySQL NUMBER(19) AutoNum repair](../maintenance/2026-08-23-MYSQL-NUMBER19-AUTOINCREMENT-R2.md)

## Documentation authority

The files above describe the current behavior and design contract. Older documents under `docs/architecture`, `docs/generation`, `docs/integration`, `docs/testing`, `docs/dialects`, and `docs/release` remain valuable implementation history and validation evidence, but they may describe earlier test counts or intermediate phases.

When an older document conflicts with this reference set, use the current reference set plus the current source code and regression baseline.
