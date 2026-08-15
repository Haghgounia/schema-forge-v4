# Mermaid Canonical JSON Pilot

`CanonicalJsonMermaidPilotIT` generates real Mermaid artifacts directly from canonical JSON snapshots.
It does not open Word documents, render SQL, or connect to a database.

## Historical corpus policy

Production diagram export requires one definition per qualified table. The historical regression corpus
contains multiple historical versions of many tables, so historical selection is disabled by default.
For the explicit test-only pilot, enable it with:

```text
-Dschemaforge.diagram.pilot.allowHistoricalSelection=true
```

Every selected table version is written to `mermaid-pilot-selected_*.csv`.

## Outputs

The runner creates four UTF-8 `.mmd` artifacts:

- full selected ER diagram
- full selected dependency diagram
- root-table ER neighborhood at the configured dependency depth
- root-table dependency neighborhood at the configured dependency depth

It also writes a diagram manifest, selected-version report, and text summary.

## Recommended historical pilot

```bat
mvnw.cmd -Dtest=CanonicalJsonMermaidPilotIT test ^
  -Dschemaforge.diagram.pilot.inputDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson" ^
  -Dschemaforge.diagram.pilot.outputDir="D:\get-git-doc-files-master\SchemaForgeMermaidPilot" ^
  -Dschemaforge.diagram.pilot.allowHistoricalSelection=true ^
  -Dschemaforge.diagram.pilot.seedTable="TSTSHMA.CTMACCTYPEPARAMGRPARZSOURCE" ^
  -Dschemaforge.diagram.pilot.maxTables=20 ^
  -Dschemaforge.diagram.pilot.targetTables=15 ^
  -Dschemaforge.diagram.pilot.minPhysicalForeignKeys=5 ^
  -Dschemaforge.diagram.pilot.minFkChainDepth=2 ^
  -Dschemaforge.diagram.pilot.allowDisconnectedExpansion=true ^
  -Dschemaforge.diagram.pilot.dependencyDepth=2
```

The historical selection is only for regression/pilot visualization and does not change the production
one-version-per-table policy.
