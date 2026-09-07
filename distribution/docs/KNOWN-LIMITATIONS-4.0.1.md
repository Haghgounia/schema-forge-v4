# SchemaForge V4 4.0.1 - Known Limitations

## Db2 z/OS live execution

Db2 z/OS DDL generation, mapping, contracts, and offline regression coverage are included in V4. Live execution validation remains deferred until the required Db2 z/OS environment is available.

## IBM JCC in the standard distribution

The normal 4.0.1 Maven build does not bundle IBM JCC. The standard distribution must keep Db2 LUW and Db2 z/OS live metadata profiles disabled unless the required IBM runtime dependency is supplied through the approved deployment mechanism.

This does not remove Db2 DDL generation support.

## Embedded development defaults

The application source baseline still contains development-oriented values in the embedded `application.yml`. The runtime distribution therefore requires the supplied launcher, which forces the safe external configuration file:

```text
scripts\start-windows.cmd
--spring.config.location=file:./config/application.yml
```

Do not deploy the distribution with a bare `java -jar` command unless an equivalent approved external configuration boundary is explicitly supplied.

## Live metadata-dependent artifacts

Comparison, migration, CRUD, and conformance operations that require live metadata depend on the corresponding metadata repository. When a repository is disabled or unavailable, optional metadata-derived artifacts can be skipped according to the frozen artifact contract while offline DDL generation continues.

## Schema Conformance warnings

Schema Conformance is advisory/read-only. `compliant=false` can result solely from warnings and does not by itself indicate an execution failure.

## Distribution platform

The supplied distribution scripts target Windows command shells. Linux service scripts/container packaging are not included in this maintenance release.
