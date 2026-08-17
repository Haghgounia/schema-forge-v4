# SchemaForge V4 - Physical Phase 1

## P0 object-scoped physical options

Physical options are no longer limited to `Table.physicalOptions()`. The canonical model now also supports optional immutable backing-index options on:

- `Index.physicalOptions()`
- `PrimaryKey.physicalOptions()`
- `UniqueKey.physicalOptions()`

Resolution is backward compatible: an object-scoped value wins when present; otherwise the historical table-scoped index option is used as fallback. This allows two indexes on the same table to carry different `PCTFREE`, `FILLFACTOR`, `STOGROUP`, or placement values without breaking existing Word-derived snapshots or callers.

The change is additive. Word parser/cache semantics are unchanged.


## Purpose

Physical Phase 1 enriches the existing DDL with DBA-reviewable physical options without changing the service contract or turning SchemaForge into a storage-provisioning tool.

The central compatibility rule is:

> Existing active DDL remains active. New physical guidance is emitted as an inline block comment at the syntactically correct location inside the statement.

There is no `REVIEW` / `APPLY` mode in Phase 1. A DBA may remove the surrounding `/* ... */`, replace environment placeholders, adjust values and execute the resulting statement.

## Scope

Included:

- Table placement already supported by the dialect/source model.
- Basic table physical attributes.
- Index placement already supported by the dialect/source model.
- Index physical attributes.
- PK and Unique backing/enforcing index physical guidance.
- Db2 for z/OS explicit enforcing indexes for PK/Unique (existing behavior retained).
- FK supporting-index analysis; no automatic index creation.
- Compression candidates/defaults where suitable for Phase 1.
- Space/fill options.
- Vendor-default guidance and environment placeholders.
- Db2 for z/OS `FOR MIXED DATA` rendering for `CHAR`/`VARCHAR`.

Out of scope:

- `CREATE TABLESPACE`, `CREATE FILEGROUP`, `CREATE STOGROUP`.
- LOB physical provisioning.
- Partitioning.
- Online/concurrent build/deployment options.
- View/trigger generation changes.
- Database/storage provisioning.

## Source evidence is not treated as truth

The input Word/JSON is evidence, not an authority. Physical Phase 1 must not silently clamp, repair, or normalize an invalid physical value merely to make a script executable.

For physical options the policy is:

- syntactically acceptable source value: retain it in the DBA-reviewable physical candidate and mark it as source-derived;
- invalid/out-of-range source value: emit `[SOURCE PHYSICAL ISSUE]`, preserve the bad value in the comment, and emit a placeholder that forces review;
- missing environment/workload value: use a placeholder or documented default only where Phase 1 explicitly defines one;
- no source value: never invent an environment-specific tablespace/filegroup/stogroup/bufferpool.

Physical Phase 1 also never infers a column default from nullability. A `DEFAULT` clause is generated only when the canonical/source column has an explicit default value.

Existing placement remains executable:

- Oracle table: `TS_<SCHEMA>` default or explicit `TABLESPACE`.
- Oracle PK/Unique/normal index: `ITS_<SCHEMA>` default or explicit `INDEX_TABLESPACE`.
- PostgreSQL: source-provided table/index tablespace stays active.
- SQL Server: source-provided filegroup stays active.
- Db2 for z/OS: source-provided `IN <TABLESPACE>` / `IN <DATABASE>.<TABLESPACE>` stays active.

## Inline comment rule

Example:

```sql
CREATE INDEX ...
ON ... (...)

/*
-- DBMS INDEX PHYSICAL OPTIONS
...
*/

<existing active placement>;
```

The semicolon terminates the statement after the physical block and any existing active placement.

Text that must remain a comment after DBA activation is written with `--` inside the block. Candidate clauses themselves are not prefixed with `--`.

## Oracle

Existing active placement is preserved.

Table candidates:

```sql
/*
-- ORACLE TABLE PHYSICAL OPTIONS
PCTFREE 10
INITRANS 1
-- PCTUSED is omitted by default for ASSM-friendly DDL; source PCTUSED is surfaced for review.
NOCOMPRESS
-- LOGGING/NOLOGGING remains workload/recovery policy.
*/
```

Index / PK / Unique candidates:

```sql
/*
-- ORACLE INDEX PHYSICAL OPTIONS
PCTFREE 10
INITRANS 2
NOCOMPRESS
-- LOGGING/NOLOGGING remains workload/recovery policy.
*/
```

The active table/index tablespace remains outside the block.

## PostgreSQL

Table candidates:

```sql
/*
-- POSTGRESQL TABLE PHYSICAL OPTIONS
-- toast_tuple_target is source/profile-only because its upper bound depends on server block size.
WITH (fillfactor = 100)
TABLESPACE <TABLE_TABLESPACE>
-- Column STORAGE/COMPRESSION and autovacuum policy are not invented in Phase 1.
*/
```

The `TABLESPACE` placeholder is included only when no active source placement exists.

B-tree standalone-index candidates:

```sql
/*
-- POSTGRESQL INDEX PHYSICAL OPTIONS
WITH (fillfactor = 90)
TABLESPACE <INDEX_TABLESPACE>
*/
```

PK / Unique backing-index candidates use PostgreSQL constraint grammar instead:

```sql
/*
-- POSTGRESQL INDEX PHYSICAL OPTIONS
WITH (fillfactor = 90)
USING INDEX TABLESPACE <INDEX_TABLESPACE>
*/
```

An active source `USING INDEX TABLESPACE` / `TABLESPACE` remains outside the block.

## SQL Server

Table candidates:

```sql
/*
-- SQL SERVER TABLE PHYSICAL OPTIONS
ON [<TABLE_FILEGROUP>]
WITH (DATA_COMPRESSION = NONE)
*/
```

The filegroup placeholder is included only when no active source placement exists. When a source filegroup is active, grammar order is preserved and the physical block follows the active table filegroup.

Index / PK / Unique candidates:

```sql
/*
-- SQL SERVER INDEX PHYSICAL OPTIONS
WITH (
    PAD_INDEX = OFF,
    FILLFACTOR = 0,
    IGNORE_DUP_KEY = OFF,
    STATISTICS_NORECOMPUTE = OFF,
    ALLOW_ROW_LOCKS = ON,
    ALLOW_PAGE_LOCKS = ON,
    DATA_COMPRESSION = NONE
)
ON [<INDEX_FILEGROUP>]
*/
```

`CLUSTERED` / `NONCLUSTERED` is not inferred in Phase 1.

## Db2 for z/OS

`CHAR` and `VARCHAR` are rendered with `FOR MIXED DATA`.

Nullable columns without an explicit source default do **not** receive `WITH DEFAULT NULL`. Explicit source defaults use Db2 `WITH DEFAULT ...` syntax.

Table candidates:

```sql
/*
-- DB2/ZOS TABLE PHYSICAL OPTIONS
IN <DATABASE>.<TABLESPACE>
-- Table-space attributes belong to CREATE/ALTER TABLESPACE, not CREATE TABLE.
BUFFERPOOL <BUFFERPOOL>
DSSIZE <DSSIZE>
SEGSIZE <SEGSIZE>
FREEPAGE 0
PCTFREE 5
-- FOR UPDATE is source/profile-only because PCTFREE_UPD is subsystem-controlled.
COMPRESS NO
GBPCACHE CHANGED
CLOSE YES
DEFINE YES
LOCKSIZE <LOCKSIZE>
LOCKMAX <LOCKMAX>
MAXROWS 255
-- MEMBER CLUSTER is source/profile-only.
INSERT ALGORITHM 0
TRACKMOD <TRACKMOD>
LOGGED
USING STOGROUP <STOGROUP>
    PRIQTY <PRIQTY>
    SECQTY <SECQTY>
    ERASE NO
-- MAXPARTITIONS/NUMPARTS/PAGENUM/PARTITION remain outside this phase.
*/
```

The `IN` placeholder is omitted when a source placement is already active. The table-space profile stays comment-only: SchemaForge does not provision or recreate an existing table space. Explicit values are validated against the offline Db2 rules available to the generator; invalid values are retained as SOURCE PHYSICAL ISSUE diagnostics rather than normalized.

Index / PK / Unique candidates:

```sql
/*
-- DB2/ZOS INDEX PHYSICAL OPTIONS
USING STOGROUP <STOGROUP>
    PRIQTY <PRIQTY>
    SECQTY <SECQTY>
    ERASE NO
FREEPAGE 0
PCTFREE 10
GBPCACHE CHANGED
COMPRESS NO
BUFFERPOOL <BUFFERPOOL>
CLOSE YES
-- PIECESIZE is emitted only when supplied by source/profile and remains DBA-reviewable.
*/
```

For an index whose key contains a varying-length character column, the block additionally includes:

```sql
-- Varying-length key detected - choose according to subsystem/DBA policy.
<PADDED_OR_NOT_PADDED>
```

No value is hardcoded because the default can depend on the Db2 subsystem policy.


### Physical P0 granularity

Index, Primary Key backing-index and Unique Key backing-index physical options are now object-scoped. Existing table-scoped index options remain a backward-compatible fallback. Table-space physical profile values remain table-scoped because this phase reports the storage context used by that table; SchemaForge still does not provision shared table-space objects.

## FK supporting-index analysis

The FK itself remains logical DDL and has no storage/physical attributes. SchemaForge checks whether the FK child columns are the leading columns, in the same order, of one of the following:

- Primary key.
- Unique key.
- Explicit index.

An index may contain additional trailing columns and still cover the FK.

If no supporting index exists, SchemaForge emits a recommendation such as:

```sql
-- [RECOMMENDATION][PHYS-FK-INDEX-001] Foreign key FK_X has no supporting index whose leading columns match (COL_A,COL_B).
```

SchemaForge does not create the missing index automatically.

## Invalid source physical value example

SchemaForge does not silently normalize a bad source value:

```sql
/*
-- ORACLE TABLE PHYSICAL OPTIONS
-- [SOURCE PHYSICAL ISSUE][ORACLE] ORACLE_PCTFREE=120 is outside the accepted 0..99 integer range; source value was not normalized.
PCTFREE <PCTFREE>
INITRANS 1
...
*/
```

Removing the outer block comment still leaves the issue text commented and the placeholder forces the DBA to make an explicit decision.

## Compatibility constraints

Physical Phase 1 does not modify:

- Canonical logical columns.
- Nullability.
- Source defaults.
- Logical PK/UK/FK/CHECK semantics.
- Existing source placement.
- Existing Oracle `TS_<SCHEMA>` / `ITS_<SCHEMA>` defaults.

`SPACE_FREE_NAME` is not part of the Phase-1 fixture/rules.

## Golden regression evidence

The project-environment full regression after the Phase-1 sanity-check fix completed with:

- Tests run: 318
- Failures: 0
- Errors: 0
- Skipped: 3 intentional database-execution integration tests
- Maven result: `BUILD SUCCESS`

`PhysicalPhase1GoldenCorpusTest` additionally freezes two representative real-project scenarios without depending on Word parsing:

- `VOUCHER_TEMPLATE_HEADER_ROWS`-style: four-dialect physical placement/options, identity PK, unique keys, checks, explicit indexes, source defaults, and Db2 varying-length indexed key handling.
- `CTMSourcePermissionDetail`-style: source-only defaults plus FK supporting-index covered/uncovered analysis.

`SPACE_FREE_NAME` is intentionally excluded from these fixtures.

## Corpus hardening after the 320-test checkpoint

Two additional boundaries are frozen before expanding Phase 1 further:

1. **LOB boundary** - `BLOB/CLOB` remain ordinary logical datatypes in Phase 1. The dialect may map the datatype, but Phase 1 does not provision or tune LOB storage. In particular it does not emit Oracle `LOB (...) STORE AS`, SQL Server `TEXTIMAGE_ON`, or Db2 auxiliary/LOB tablespace clauses.
2. **Real Word-format defects** - the parser accepts the observed `Data RANGE` datatype-header variant and conservatively recovers the observed `NUMBER)5)` typo as `NUMBER(5)`.

The Word regression fixture for these defects intentionally omits `SPACE_FREE_NAME`.

## Real-source regression corpus

Physical Phase 1 is additionally protected by `RealSourcePhysicalPhase1RegressionTest`, which uses the actual project-supplied design documents rather than hand-built canonical fixtures:

- `MCB.BIM.TBL.COUNTRIES.V1.1.docx`
- `MCB.ACC.TBL.VOUCHER_TEMPLATE_HEADER_ROWS.V1.0.docx`
- `14000218_SmsServcD.sd.spc.tb.CTSMSServiceDetails.docx`
- `trunk_Spec_Arz_CmmnDpstD.sd.spc.tb.CTMSourcePermissionDetail.doc`

The tests intentionally avoid treating `SPACE_FREE_NAME` as a contract. For legacy Word inputs, schema remains an explicit REST/API parameter and is not inferred from the source filename.

## Legacy revision-history default precedence

Physical Phase 1 consumes canonical defaults and does not infer them. For legacy Word sources, the parser now reconciles a specific source inconsistency found in `CTMSourcePermissionDetail`: the field grid can retain an older default while the later revision history explicitly removes it.

Rules:
- Explicit Persian `پیش فرض: صفر` is normalized to numeric SQL default `0`.
- A later explicit revision-history statement that removes a default wins over the stale field-grid default.
- Revision history is not used to invent a missing default.
- A one-edit technical-name correction is accepted only when it maps uniquely to one extracted column, covering `ReuestAmnt` -> `RequestAmnt` in the real source.
- Therefore `SourceAmnt` retains `DEFAULT 0`, while `RequestAmnt`, `PermitAmnt`, and `UsedAmnt` have no default in canonical/DDL output.
