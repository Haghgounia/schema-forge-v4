# SchemaForge V4 4.0.1 - Known Limitations

- Db2 z/OS live execution validation remains deferred until an appropriate environment is available; offline generation and regression remain covered.
- IBM JCC is not bundled in the standard distribution; Db2 live metadata profiles require the approved IBM runtime dependency.
- The embedded source `application.yml` still contains development-oriented defaults. The distribution launcher forces external `config/application.yml`; bare `java -jar` deployment without an equivalent external configuration boundary is not approved.
- Metadata-dependent comparison/migration/CRUD/conformance operations require the corresponding live metadata repository. Optional artifacts can be skipped when metadata is unavailable while offline DDL generation continues.
- Schema Conformance warnings can produce `compliant=false` without an execution error.
- Supplied runtime scripts target Windows command shells.
