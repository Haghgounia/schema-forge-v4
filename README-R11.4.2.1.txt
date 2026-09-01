SchemaForge V4 - R11.4.2.1 source-sync overlay

Purpose
-------
Correct a partial R11.4.2 source overlay where updated tests were present but the
production Artifact Contract sources on disk were still from the previous version.

This overlay introduces NO new behavior beyond R11.4.2. It re-applies all eleven
production Java files changed by R11.4.2 so a clean rebuild sees the same contract
that the R11.4.2 tests expect.

Key source contract markers after extraction:
- ArtifactDescriptor has outcomeReason()
- ArtifactLedger has skipped(..., producer, outcomeReason)
- ArtifactManifestArtifact has outcomeReason
- ArtifactManifestAssembler serializes descriptor.outcomeReason()
- Comparison/Migration/CRUD producers record deterministic skip reasons
- Legacy 'Value = 0' default normalization is preserved
- Legacy default recovery findings are renderable in SQL
