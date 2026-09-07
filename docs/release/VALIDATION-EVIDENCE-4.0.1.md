# SchemaForge V4 4.0.1 - Validation Evidence

Date: 2026-09-07

## FIX-001 focused gate

```text
Tests    : 5
Failures : 0
Errors   : 0
Result   : BUILD SUCCESS
```

Covered:

- Word section boundary before an inline Sequence section
- SchemaForge-owned Sequence naming
- SchemaForge-owned Index naming
- SchemaForge-owned Foreign Key naming
- existing Legacy Index and continuation-FK regression coverage

## Real Batch gate

```text
Input documents     : 19
Successful          : 19
Failed              : 0
Request status      : SUCCESS
Generated artifacts : 243
Skipped artifacts   : 266
Blocked artifacts   : 0
Failed artifacts    : 0
Validation errors   : 0
Validation warnings : 89
Six-DBMS DDL        : 114 / 114
```

The formerly failing `MCB.RTG.TBL.BANK_TO_BANK_TRANSFERS.docx` completed successfully. Its generated object names are derived from SchemaForge naming policy rather than physical names written in the source document.

## Full regression gate

```text
Tests    : 765
Failures : 0
Errors   : 0
Skipped  : 9
Result   : BUILD SUCCESS
Finished : 2026-09-07T08:59:53+03:30
```

## Binary/distribution gate

Pending after version promotion:

1. two clean reproducible 4.0.1 builds with identical SHA-256
2. checksum freeze in `distribution/checksums/SHA256SUMS.txt`
3. 4.0.1 distribution assembly
4. distribution stage/JAR/ZIP checksum verification

Db2 z/OS live execution remains deferred.
