# SchemaForge V4 - EA PK Identity Inference R3

Status: REPAIR CANDIDATE / REAL EA REFERENCE INPUT PROBED

## Trigger

`Party-Reference_14050601.xml` failed on `/api/v1/generate/ea-xml` with:

```text
MySQL AUTO_INCREMENT requires an integer column; no lossless integer mapping exists for
DataType[name=VARCHAR2, length=50, ...] on RECOMMENDATION_STATUS_CODE
```

The reference XMI contains code-based primary keys such as
`REF_RECOMMENDATION_STATUS.RECOMMENDATION_STATUS_CODE VARCHAR2(50)` and does not mark them AutoNum.
The EA REST preparation path intentionally enables primary-key identity inference for portable EA
models, but the parser previously applied that inference to every PK datatype.

## Repair policy

Explicit EA identity/AutoNum remains authoritative and unchanged.
When `primaryKeyAsIdentity=true`, inferred identity is now limited to integer-compatible canonical
PK datatypes:

- INTEGER / INT / SMALLINT / BIGINT / TINYINT;
- NUMBER / NUMERIC / DECIMAL / DEC only when scale is explicitly 0.

Character, temporal, binary and other PKs are never inferred as identity merely because they are PKs.

## Compatibility

- FEE portable XMI numeric PK inference remains enabled.
- Legacy Party `AutoNum=True` recognition remains unchanged.
- R2 FK association recovery logic remains unchanged.
- No dialect, REST contract, artifact layout or canonical datatype mapping is changed.

## Direct verification in build environment

Java 21 compilation of the repaired parser: PASS.

Real `Party-Reference_14050601.xml` probe with `primaryKeyAsIdentity=true`:

```text
tables=104
identityCount=0
REF_RECOMMENDATION_STATUS.RECOMMENDATION_STATUS_CODE identity=false
MySQL AUTO_INCREMENT present=false
MySQL column=`RECOMMENDATION_STATUS_CODE` VARCHAR(50) NOT NULL
```

The same file contains 105 PK columns and all are VARCHAR2; no PK has an explicit identity/AutoNum tag.

Real FEE portable XMI compatibility probe:

```text
tables=21
identityCount=21
FEE_DEFINITION identities=[FEE_DEFINITION_ID]
```

Maven regression must still be run in the user environment.
