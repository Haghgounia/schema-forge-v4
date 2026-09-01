# Cross-Service Object Naming Contract

Status: implementation complete; Maven full regression pending on the Windows project environment.

## Authority

Names carried by Standard Word, Legacy Word, EA/XMI, canonical JSON, or other source documents are not authoritative for generated database objects. SchemaForge derives logical object names from the owning table and structural definition, then adapts that logical name to the target DBMS identifier limit.

This contract applies consistently to canonical output, CREATE DDL, ALTER/M2 migration, comparison/reporting desired-side objects, diagrams, REST-generated artifacts, and validation.

## Logical formulas

| Object | Logical formula |
| --- | --- |
| Primary-key constraint | `PK_<TABLE>` |
| Primary-key backing unique index | `PK_<TABLE>_<PK_COLUMN1>[_<PK_COLUMN2>...]` |
| Unique-key constraint and backing unique index | `UK_<TABLE>_<COLUMN1>[_<COLUMN2>...]` |
| Foreign key | `FK_<CHILD_TABLE>_<CHILD_COLUMN1>[_<CHILD_COLUMN2>...]` |
| Check constraint | `CHK_<TABLE>_<REFERENCED_COLUMN...>` |
| Standalone index | `IX_<TABLE>_<KEY_TERMS>` |
| Standalone unique index | `IX_<TABLE>_<KEY_TERMS>`; uniqueness remains SQL semantics |

If two distinct structural objects would have the same logical base name, SchemaForge appends a deterministic structural SHA-256 disambiguator. Source object names are never used for disambiguation.

## Physical identifier adaptation

Target identifier limits:

- PostgreSQL: 63
- MySQL: 64
- Oracle: 128
- SQL Server: 128
- DB2 LUW: 128
- DB2 z/OS: 128

If the logical name fits the target limit, it is preserved exactly. Otherwise:

```text
physicalName = LEFT(logicalName, maxLength - 13)
             + "_"
             + SHA256(UPPER(logicalName))[0..11]
```

The 12-character hexadecimal hash is computed from the complete logical name before truncation. This rule is deterministic and collision-resistant; numeric encounter-order suffixes are forbidden.

## Live metadata and migration

Live database object names remain evidence of actual physical state and are not normalized in metadata repositories.

For M2 replacement/rename operations:

- DROP/RENAME source side uses the actual live physical object name.
- ADD/RENAME target side uses the SchemaForge logical formula after target-DBMS physical adaptation.

This preserves executable correctness while preventing source-document names from becoming desired-state authority.

## Verification gates

The naming gate covers:

- input-name poisoning for PK/UK/FK/CHECK/index/unique index;
- Standard Word, Legacy Word, EA/XMI and canonical normalization paths;
- CREATE DDL for all six DBMS;
- ALTER/M2 for all six DBMS;
- PK/UK backing-index naming;
- standalone normal and unique indexes;
- duplicate-base structural disambiguation;
- target identifier boundary, `limit - 1`, `limit`, and `limit + 1`;
- deterministic 12-hex truncate/hash output;
- collision resistance for similar long logical names;
- desired-side metadata comparison and reporting;
- Mermaid and Graphviz relationship labels;
- legacy expectations that previously preserved `CK_`, `IDX_`, EA numbered names, repeated underscores, or DB2-specific `_IX` suffixes.

The local dependency-free expanded gate executed 172 tests with 172 PASS and 0 failures. The authoritative final acceptance remains one targeted Maven naming gate followed by one `mvnw.cmd clean test` on the project machine.
