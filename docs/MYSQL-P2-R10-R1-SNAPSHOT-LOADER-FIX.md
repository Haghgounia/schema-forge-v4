# MySQL P2-R10-R1 - Snapshot Loader Fix

P2-R10 originally walked every `*.json` file under the snapshot root. If a non-canonical JSON artifact lacked a schema, `CanonicalSnapshotMapper` aborted with `NullPointerException: snapshot schema`.

R1 aligns the audit loader with the earlier corpus audits:

- only `*.schema.json` files are considered canonical snapshot inputs;
- malformed/non-canonical artifacts are skipped by this evidence-only audit;
- production DDL generation and canonical JSON remain unchanged.
