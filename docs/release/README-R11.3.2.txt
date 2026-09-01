SchemaForge V4 R11.3.2 - DB2 z/OS regression green correction

Scope: test-only. No production Java changes.

Fixes four remaining assertions after R11.3.1:
1) WITH RESTRICT ON DROP text occurs only in DBA review comment; test now distinguishes it from an executable clause.
2) Missing DB2 table placement is represented as TABLE PLACEMENT=<DATABASE>.<TABLESPACE> in the DBA review block, not executable IN <DATABASE>.<TABLESPACE>.
3) RealSource tests uppercase generated DB2 SQL before assertions; PADIX/subsystem policy expectation is now uppercase accordingly (two assertions).
