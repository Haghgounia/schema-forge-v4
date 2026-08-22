# SchemaForge V4 - Current Reference Documentation

This directory is the authoritative documentation entry point for the current SchemaForge V4 baseline.

Current source baseline: **SchemaForge V4 Consolidated Baseline 2026-08-22 (C1)**.

The exact frozen source passed the user-verified clean full regression:

```text
Tests run: 467
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Finished: 2026-08-22T07:01:53-07:00
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

## Documentation authority

The files above describe the current behavior and design contract. Older documents under `docs/architecture`, `docs/generation`, `docs/integration`, `docs/testing`, `docs/dialects`, and `docs/release` remain valuable implementation history and validation evidence, but they may describe earlier test counts or intermediate phases.

When an older document conflicts with this reference set, use the current reference set plus the current source code and regression baseline.
