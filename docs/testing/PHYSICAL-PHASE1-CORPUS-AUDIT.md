# Physical Phase 1 Corpus Audit

`PhysicalPhase1CorpusAuditIT` audits the Phase-1 physical DDL contract over persisted canonical JSON sources without turning datatype compatibility into the audit target.

## Scope

The audit checks:

- source table placement remains active;
- Oracle default `TS_<SCHEMA>` and `ITS_<SCHEMA>` placement remains active;
- PostgreSQL and SQL Server do not invent active placement when source placement is absent;
- missing environment placement is represented by an activation-ready placeholder inside a block comment;
- every table has one table physical block;
- PK, unique-key backing indexes, and emitted standalone indexes have one index physical block;
- Db2 index blocks retain `STOGROUP`, `PRIQTY`, `SECQTY`, and `BUFFERPOOL` placeholders;
- Db2 `<PADDED_OR_NOT_PADDED>` appears only for varying-length character index keys;
- FK supporting-index recommendations match the canonical leading-column analysis;
- FK and CHECK statements do not receive storage/physical option blocks;
- Phase-1 tuning/storage recommendations remain commented and environment placeholders never become executable SQL.

The audit does **not** decide cross-DB datatype compatibility. If full DDL cannot be rendered because of a datatype or another non-physical issue, that source is still audited at canonical/physical-renderer level. The DDL gap is reported as `PHYS-DDL-UNAVAILABLE-001` and is not counted as a physical violation.

## Run

```bat
mvnw.cmd ^
  -Dtest=PhysicalPhase1CorpusAuditIT ^
  -Dschemaforge.physical.audit.inputDir="D:\get-git-doc-files-master\SchemaForgeCanonicalJson\all" ^
  -Dschemaforge.physical.audit.outputDir="D:\SchemaForge-Physical-Audit\Json" ^
  -Dschemaforge.physical.audit.platforms=oracle,postgresql,sqlserver,db2zos ^
  -Dschemaforge.physical.audit.failOnViolations=false ^
  test
```

Use `failOnViolations=false` for the first corpus discovery run. After findings are understood and accepted/fixed, use `true` to turn the corpus audit into a regression gate.

## Reports

- `physical-phase1-audit-summary_<timestamp>.txt`
- `physical-phase1-audit-summary_<timestamp>.csv`
- `physical-phase1-audit-detail_<timestamp>.csv`
- `physical-phase1-audit-findings_<timestamp>.csv`

The detail report has one row per snapshot/platform. `PASS` means the model and full rendered DDL passed the physical audit. `MODEL_PASS_DDL_UNAVAILABLE` means the physical model/renderer contract passed but full DDL was unavailable for a non-physical reason. `VIOLATION` means a Physical Phase-1 invariant was broken.
