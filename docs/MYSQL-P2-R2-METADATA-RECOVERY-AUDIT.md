# MySQL P2-R2 - Metadata Recovery Audit

P2-R2 is an evidence-only audit. It measures whether MySQL datatype blockers in persisted canonical snapshots can be resolved from an offline `SYSIBM.SYSCOLUMNS` export.

The audit performs only exact `schema/table/column` lookups through the existing `Db2SysColumnsFileCatalog`. It does not mutate snapshots or change DDL mappings.

## Inputs

- Canonical snapshot directory (`*.schema.json`)
- Offline `SYSIBM.SYSCOLUMNS` export in CSV/TSV/TXT/ZIP format

## Outputs

- `mysql-metadata-recovery-summary_<timestamp>.txt`
- `mysql-metadata-recovery-details_<timestamp>.csv`

The details report preserves each historical occurrence and also reports exact metadata lookup status and the MySQL type that metadata would map to when such mapping is lossless.
