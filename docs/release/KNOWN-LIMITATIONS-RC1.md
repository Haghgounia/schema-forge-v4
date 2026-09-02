# SchemaForge V4 RC1 - Known Limitations and Deferred Evidence

## Db2 z/OS live execution

Db2 z/OS generation and offline validation are part of the frozen V4 baseline, but final live execution validation remains deferred because a real Db2 z/OS environment is not currently available.

This is an environment/evidence limitation, not an open DDL-generation defect.

## Environment-dependent tests

The final Maven suite contains opt-in/live integration tests that are skipped when their required external database environment or corpus is not enabled. The accepted final regression result contains 9 skipped tests and zero failures/errors.

## Source-quality blocking fixture

The retained Word regression corpus includes `MCB.BIM.TBL.PROVINCES.V1.1.docx`, whose `BIM.PROVINCES.POPULATION` datatype is unresolved. SchemaForge deliberately blocks DDL generation for that input rather than guessing a datatype. The newer `PROVINCES.V1.2` fixture is valid.

This is expected no-guess behavior and is covered by regression tests.

## Schema Conformance warnings

Schema Conformance Audit reports existing database-design and naming warnings. A successful audit can therefore return `compliant=false` while having `errorCount=0`. RC1 does not automatically alter or remediate audited database objects.
