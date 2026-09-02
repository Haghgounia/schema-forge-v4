# SchemaForge V4 4.0.0 - Known Limitations

## Db2 z/OS live execution

Db2 z/OS DDL generation, mapping, contracts, and offline regression coverage are included in V4. Live execution validation is deferred until the required Db2 z/OS environment is available.

## IBM JCC in the standard GA distribution

The normal 4.0.0 GA Maven build does not bundle IBM JCC. The source project contains dedicated Db2 live profiles, but the standard distribution JAR must keep Db2 LUW and Db2 z/OS live metadata profiles disabled.

This does not remove Db2 DDL generation support.

## Embedded development defaults in the validated GA JAR

The immutable 4.0.0 GA JAR contains the source baseline's development `application.yml` resource. The runtime distribution mitigates this by forcing the safe external `config/application.yml` through `scripts/start-windows.cmd`.

Operational rule for this package:

```text
Do not start the 4.0.0 distribution with a bare java -jar command.
Use scripts\start-windows.cmd.
```

Removing/replacing the embedded resource would change the validated GA binary SHA-256 and therefore requires an intentional maintenance binary/release rather than silently modifying 4.0.0.

## Live metadata-dependent artifacts

Optional comparison, migration, CRUD, or conformance operations require the corresponding live metadata repository. An unavailable/disabled repository can cause a metadata-derived operation to be unavailable or an optional artifact to be skipped according to its frozen contract.

## Schema Conformance warnings

Schema Conformance is advisory. `compliant=false` can result solely from warnings. It does not by itself indicate an execution failure.

## Distribution platform

The 4.0.0 distribution scripts supplied in this package target Windows command shells. Linux service scripts/container packaging are not included in this distribution phase.
