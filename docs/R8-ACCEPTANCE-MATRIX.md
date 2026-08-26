# R8 Acceptance Matrix

**Prepared:** 2026-08-26  
**State:** R8.1 VERIFIED / CLOSED; R8.2 FINAL FREEZE BLOCKED ONLY BY Db2 z/OS LIVE ENVIRONMENT

| Area | Acceptance evidence | State |
|---|---|---|
| Consolidated architecture / artifact contracts | C11 official baseline | PASS / FROZEN |
| Legacy canonical recovery | 5321 loaded, 4704 generated, 617 evidence-blocked, 0 read/generation failures | PASS |
| Legacy recovered corpus — Oracle | 5294 generated + 2 warnings, 25 mapping blocked | PASS |
| Legacy recovered corpus — PostgreSQL | 5321 generated, 0 mapping blocked | PASS |
| Legacy recovered corpus — Db2 z/OS | 4693 generated, 628 mapping blocked | PASS |
| Legacy recovered corpus — SQL Server | 4703 generated, 618 mapping blocked | PASS |
| Legacy recovered corpus — MySQL | 4704 generated, 617 mapping blocked | PASS |
| New Word standard parser | 660 documents, 0 skipped, 0 parse failures | PASS |
| New Word SAFE strict gate | exact 5-DBMS counts and failure codes | PASS |
| New Word OPTIMIZED strict gate | exact 5-DBMS counts and failure codes | PASS |
| SQL Server M2 live | 20 statements, residual 0, data preserved | PASS |
| MySQL M2 live | 14 statements, residual 0, data preserved | PASS |
| PostgreSQL M2 live | 16 statements, residual 0, data preserved | PASS |
| Oracle M2 live | 16 statements, residual 0, data preserved | PASS |
| Db2 z/OS M2 live | no current live environment | PENDING_ENVIRONMENT |
| R8.1 exact-RC full regression | 587 tests, 0 failures, 0 errors, 4 environment-gated skips; BUILD SUCCESS | PASS / VERIFIED |
| R8.2 operational freeze | requires Db2 live + final regression | BLOCKED_BY_DB2_ENVIRONMENT |

## R7.3 exact failure-code contract

| DBMS | Expected failure codes |
|---|---|
| Oracle | `CANONICAL_DATATYPE_UNRESOLVED:3`, `ORACLE_NUMBER_PRECISION_UNSUPPORTED:1` |
| PostgreSQL | `CANONICAL_DATATYPE_UNRESOLVED:3` |
| Db2 z/OS | `CANONICAL_DATATYPE_UNRESOLVED:3`, `DB2_DECIMAL_PRECISION_UNSUPPORTED:1` |
| SQL Server | `CANONICAL_DATATYPE_UNRESOLVED:3`, `SQLSERVER_DECIMAL_PRECISION_UNSUPPORTED:1` |
| MySQL | `CANONICAL_DATATYPE_UNRESOLVED:3`, `MYSQL_MULTIPLE_AUTO_INCREMENT:9`, `MYSQL_SEQUENCE_NEXTVAL_UNSUPPORTED:2`, `MYSQL_DECIMAL_PRECISION_UNSUPPORTED:1`, `MYSQL_IDENTITY_INTEGER_UNREPRESENTABLE:1` |

Any new failure category or count drift is a regression until explicitly investigated and re-baselined.
