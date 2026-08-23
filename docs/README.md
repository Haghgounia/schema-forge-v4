# SchemaForge Documentation

## Current authoritative documentation

For the current SchemaForge V4 baseline, start here:

- [Current reference documentation](reference/README.md)
- [Architecture](reference/ARCHITECTURE.md)
- [Canonical domain model](reference/CANONICAL-DOMAIN-MODEL.md)
- [Inputs, outputs, and pipeline](reference/INPUTS-OUTPUTS-PIPELINE.md)
- [Database support matrix](reference/DATABASE-SUPPORT-MATRIX.md)
- [Physical DDL reference](reference/PHYSICAL-DDL-REFERENCE.md)
- [Physical metadata comparison](reference/PHYSICAL-METADATA-COMPARISON.md)
- [Excel workbook reference](reference/EXCEL-COMPARISON-REFERENCE.md)
- [Evidence / no-guess policy](reference/EVIDENCE-AND-NO-GUESS-POLICY.md)
- [Known limitations](reference/KNOWN-LIMITATIONS.md)
- [Developer guide](reference/DEVELOPER-GUIDE.md)
- [Testing and baseline](reference/TESTING-AND-BASELINE.md)
- [Current release baseline](reference/CURRENT-RELEASE-BASELINE.md)
- [V4 consolidation execution plan](roadmap/SCHEMAFORGE-V4-CONSOLIDATION-EXECUTION-PLAN.md)
- [Consolidation candidate/repair/version history](roadmap/CONSOLIDATION-VERSION-HISTORY.md)
- [Artifact inventory (C4.1)](architecture/SCHEMAFORGE-ARTIFACT-INVENTORY.md)
- [Artifact Contract V1 (C4)](architecture/ARTIFACT-CONTRACT.md)
- [Artifact Naming/Layout contract (C5)](architecture/ARTIFACT-NAMING-LAYOUT.md)
- [Artifact Manifest V1 design (C6.1)](architecture/ARTIFACT-MANIFEST-C6.1.md)
- [C5.3-R2 MySQL NUMBER(19) AutoNum repair](maintenance/2026-08-23-MYSQL-NUMBER19-AUTOINCREMENT-R2.md)

Last frozen source state: **official 2026-08-22 V4 consolidated baseline C5.3**. The exact C5.3-R1 source is user-verified at **492 tests, 0 failures, 0 errors, 4 configuration-based skips, BUILD SUCCESS**; verification finished 2026-08-22T23:33:56-07:00. A post-freeze C5.3-R2 MySQL compatibility repair is pending Maven regression. C6.1 Manifest V1 design is complete/documentation-only; C6.2 production implementation waits for the R2 regression gate.

## Physical phase evidence

- [Physical P0-P7 baseline freeze](PHYSICAL-BASELINE-FREEZE.md)
- [Physical final gap audit](PHYSICAL-FINAL-GAP-AUDIT.md)
- [Physical coverage matrix](physical-phase1-coverage-matrix.md)
- [P8-A table physical metadata comparison](P8-TABLE-PHYSICAL-METADATA-COMPARISON.md)
- [P8-B index/PK/UK physical metadata comparison](P8B-INDEX-PHYSICAL-METADATA-COMPARISON.md)
- [P8-C column physical metadata comparison](P8C-COLUMN-PHYSICAL-METADATA-COMPARISON.md)
- [P8-D physical comparison freeze](P8D-PHYSICAL-COMPARISON-BASELINE-FREEZE.md)

## Architecture history and detailed implementation notes

- [Dialect capability](architecture/DIALECT-CAPABILITY.md)
- [Application dialect selection](architecture/APPLICATION-DIALECT-SELECTION.md)
- [Application pipeline](architecture/APPLICATION-PIPELINE.md)
- [Metadata validation Phase 2](architecture/METADATA-VALIDATION-PHASE2.md)

## DDL generation history

- [Oracle offline DDL](generation/ORACLE-OFFLINE-DDL-COMPLETION.md)
- [PostgreSQL DDL](generation/POSTGRESQL-DDL-COMPLETION.md)
- [PostgreSQL feature parity](generation/POSTGRESQL-FEATURE-PARITY.md)
- [Output naming](generation/DBMS-AWARE-OUTPUT-NAMING.md)
- [Document/database comparison](generation/DOCUMENT-DATABASE-COMPARISON.md)
- [ZIP output packaging](generation/ZIP-OUTPUT-PACKAGING.md)

## Dialect detail

- [Db2 for z/OS dialect](dialects/DB2-ZOS-DIALECT.md)
- [Db2 for z/OS metadata](dialects/DB2-ZOS-METADATA.md)
- [Db2 for z/OS numeric mapping](dialects/DB2-ZOS-NUMERIC-MAPPING.md)
- [Oracle CRUD metadata](dialects/ORACLE-CRUD-METADATA.md)
- [SQL Server dialect](dialects/SQL-SERVER-DIALECT.md)
- [SQL Server metadata](dialects/SQL-SERVER-METADATA.md)
- [SQL Server CRUD metadata](dialects/SQLSERVER-CRUD-METADATA.md)

## Integration

- [Legacy Word parser](integration/LEGACY-WORD-PARSER.md)
- [Canonical JSON snapshot cache](integration/CANONICAL-JSON-SNAPSHOT-CACHE.md)
- [Corpus bulk validation](integration/CORPUS-BULK-VALIDATION.md)
- [Integrated foreign-key analysis](integration/INTEGRATED-FOREIGN-KEY-ANALYSIS.md)
- [Oracle SQL directory execution test](ORACLE-SQL-DIRECTORY-EXECUTION-TEST.md)
- [PostgreSQL SQL directory execution test](POSTGRESQL-SQL-DIRECTORY-EXECUTION-TEST.md)
- [SQL Server SQL directory execution test](integration/SQLSERVER-SQL-DIRECTORY-EXECUTION-TEST.md)

## Diagrams

- [Mermaid export](diagram/MERMAID-EXPORT-PHASE1.md)
- [Mermaid production integration](diagram/MERMAID-PRODUCTION-INTEGRATION.md)
- [Graphviz export](diagram/GRAPHVIZ-EXPORT-PHASE2.md)

## Testing and validation history

- [DDL execution validation](testing/DDL-EXECUTION-VALIDATION.md)
- [SQL Server validation](testing/SQL-SERVER-VALIDATION.md)
- [Db2 for z/OS live validation](testing/DB2-ZOS-LIVE-VALIDATION.md)
- [Physical Phase-1 corpus audit](testing/PHYSICAL-PHASE1-CORPUS-AUDIT.md)

## Historical release documents

Files under `docs/release/` preserve earlier V4 release/freeze evidence. Some use the word `FINAL` for an earlier milestone and therefore contain older test counts. They remain historical evidence and are superseded for current status by [Current release baseline](reference/CURRENT-RELEASE-BASELINE.md).

## Codebase documentation

- [Class-level JavaDoc coverage](CLASS-DOCUMENTATION-COVERAGE.md)
- [V4.1 documentation and cleanup](V4.1-DOCUMENTATION-CLEANUP.md)
- [Warning abbreviations](WARNING-ABBREVIATIONS.md)

## Roadmap/history

- [Roadmap change log](roadmap/CHANGELOG.md)
- [Consolidation version/repair history](roadmap/CONSOLIDATION-VERSION-HISTORY.md)
- [Gap matrix](roadmap/GAP-MATRIX.md)
