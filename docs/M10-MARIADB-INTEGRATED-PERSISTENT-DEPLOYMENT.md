# M10 - MariaDB Integrated Persistent Deployment

## M10.1 Selection manifest

The historical canonical corpus contains more than one definition for many qualified table names.
Production integrated deployment must not select a historical winner implicitly.

`CanonicalJsonSelectionManifestIT` converts the duplicate-definition audit into an explicit,
machine-readable no-guess selection contract:

- a unique qualified table definition is `AUTO_SELECTED_UNIQUE`;
- an `EXACT_LOGICAL_DUPLICATE / SAFE_EXACT_LOGICAL` group is collapsed to one deterministic
  representative because all members carry the same exact logical signature;
- every other duplicate group is `REQUIRES_REVIEW` and has no selected snapshot.

The representative chosen inside an exact-logical group is the lexicographically first normalized
snapshot path. This is not a historical winner: all members are logically equivalent by the audit
contract. The manifest records the selected snapshot, source, source SHA-256, and exact-logical
signature so the choice is reproducible and auditable.

The runner also cross-checks the audit against the canonical corpus. Missing groups, member-count
mismatches, signature mismatches, or selected audit members absent from the corpus are reported as
selection-audit mismatches and fail the gate by default.

Outputs:

- `selection-manifest_<timestamp>.csv`: one row per distinct qualified table;
- `selection-auto-selected_<timestamp>.csv`: only auto-selected rows, usable as M10.2 input;
- `selection-review-candidates_<timestamp>.csv`: every candidate snapshot for review groups;
- `selection-review-decisions-template_<timestamp>.csv`: one blank decision row per review table;
- `selection-audit-mismatches_<timestamp>.csv`: audit/corpus integrity findings;
- `selection-summary_<timestamp>.txt`: aggregate counts and paths.

M10.2 persistent deployment must consume only an approved one-version-per-table manifest. It must
not infer winners from filename order, timestamps, directory layout, or source-document recency.
