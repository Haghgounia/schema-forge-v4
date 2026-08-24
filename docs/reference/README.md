# SchemaForge V4 - Current Reference Documentation

This directory is the authoritative documentation entry point for the current SchemaForge V4 baseline.

Current official baseline: **SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260823-C11**, verified at full `554/0/0/4` with targeted C11 `95/0/0/0` and fingerprint `03d01cfd60e6b04be02ecc8df9c0dd6c47d6f06dc07f77f6c60844a93d5102ba`. C8 Service Decomposition, C9 Test Matrix / Live-Validation Classification, C10 Documentation Consolidation, and C11 Final Consolidation Regression / Baseline Freeze are complete. The authoritative test matrix is [`../testing/TEST-MATRIX-C9.md`](../testing/TEST-MATRIX-C9.md). The C4-C11 consolidation track is closed; no deferred feature is active until explicitly promoted.

The exact frozen source passed the user-verified clean full regression:

```text
Tests run: 554
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
Finished: 2026-08-24T10:16:01+03:30
```

The four skips are configuration-gated directory execution tests. Opt-in live database `*IT` pilots are separate from the standard Surefire freeze result.

## Core reference set

1. [Architecture](ARCHITECTURE.md)
2. [Canonical domain model](CANONICAL-DOMAIN-MODEL.md)
3. [Inputs, outputs, and processing pipeline](INPUTS-OUTPUTS-PIPELINE.md)
4. [Database support matrix](DATABASE-SUPPORT-MATRIX.md)
5. [Artifact Contract V1](../architecture/ARTIFACT-CONTRACT.md)
6. [Artifact Naming/Layout contract](../architecture/ARTIFACT-NAMING-LAYOUT.md)
7. [Standard Artifact Manifest V1](../architecture/ARTIFACT-MANIFEST.md)
8. [REST Response/Error Contract](../architecture/REST-CONTRACT-C7.1.md)
9. [C8 service decomposition](../architecture/SERVICE-DECOMPOSITION-C8.md)
10. [C9 test matrix and live-validation classification](../testing/TEST-MATRIX-C9.md)
11. [C11 final consolidation verification](../roadmap/C11-FINAL-CONSOLIDATION-VERIFICATION.md)
12. [Physical DDL reference](PHYSICAL-DDL-REFERENCE.md)
13. [Physical metadata comparison](PHYSICAL-METADATA-COMPARISON.md)
14. [Excel comparison workbook reference](EXCEL-COMPARISON-REFERENCE.md)
15. [Evidence and no-guess policy](EVIDENCE-AND-NO-GUESS-POLICY.md)
16. [Known limitations and deferred scope](KNOWN-LIMITATIONS.md)
17. [Developer guide](DEVELOPER-GUIDE.md)
18. [Testing and regression baseline](TESTING-AND-BASELINE.md)
19. [Current release baseline](CURRENT-RELEASE-BASELINE.md)
19. [V4 consolidation execution plan](../roadmap/SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md)
21. [Consolidation candidate/repair/version history](../roadmap/CONSOLIDATION-VERSION-HISTORY.md)
21. [C10 documentation consolidation record](DOCUMENTATION-CONSOLIDATION-C10.md)

## Documentation authority

The files above describe the current behavior and design contract. Older documents under `docs/architecture`, `docs/generation`, `docs/integration`, `docs/testing`, `docs/dialects`, and `docs/release` remain valuable implementation history and validation evidence, but they may describe earlier test counts or intermediate phases.

When an older document conflicts with this reference set, use the current reference set plus the current source code and regression baseline.
