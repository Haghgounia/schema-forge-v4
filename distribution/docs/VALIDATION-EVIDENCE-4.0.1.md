# SchemaForge V4 4.0.1 - Validation Evidence

Release date: 2026-09-07

## FIX-001 focused regression

```text
WordContinuationForeignKeyTest          : PASS
WordLegacyIndexParsingTest              : PASS
WordObjectNameIsolationRegressionTest   : PASS
Focused tests                           : 5
Failures                                : 0
Errors                                  : 0
```

## Real Batch acceptance

The real input that previously failed because Sequence-section text leaked into Index parsing was accepted after FIX-001:

```text
Document            : MCB.RTG.TBL.BANK_TO_BANK_TRANSFERS.docx
Batch documents     : 19 / 19 successful
Request status      : SUCCESS
Generated artifacts : 243
Skipped artifacts   : 266
Blocked             : 0
Failed              : 0
Validation errors   : 0
Validation warnings : 89
Expected six-DBMS DDL: 114 / 114
```

The generated canonical object names for the repaired document are policy-derived. The source-document Sequence name is not used as the generated Sequence physical name.

## Full regression

```text
Tests    : 765
Failures : 0
Errors   : 0
Skipped  : 9
Result   : BUILD SUCCESS
Finished : 2026-09-07T08:59:53+03:30
```

## Reproducible maintenance binary

The authoritative 4.0.1 binary checksum is intentionally not pre-filled in source control during promotion. Run:

```text
distribution\scripts\reproducible-ga-build-windows.cmd
```

The command performs two clean package builds, requires byte-for-byte equality, and then freezes:

```text
distribution/checksums/SHA256SUMS.txt
```

The final distribution assembly and ZIP checksum must then pass the standard assembly and verification scripts.

## Deferred evidence

Db2 z/OS live execution remains environment-dependent and deferred. Offline Db2 z/OS generation/regression remains part of the validated V4 baseline.
