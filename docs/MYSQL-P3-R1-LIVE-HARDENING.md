# MySQL P3-R1 live hardening

Full live corpus execution on MySQL 8.4.11: 4,537 files, 11,871 attempted statements, 20 actionable CREATE TABLE failures.

Root causes:
- 18 x MySQL 1067: explicit `DEFAULT NULL NOT NULL`.
- 1 x MySQL 1074: `VARCHAR(30000)` exceeds the effective VARCHAR limit in the utf8mb4 validation environment.
- 1 x MySQL 1118: aggregate inline row size exceeds 65,535 bytes.

P3-R1 fixes only the semantics-safe 1067 case by omitting an unquoted NULL default on a NOT NULL column. It intentionally does not translate large VARCHAR columns to TEXT/BLOB because that changes datatype/length semantics without explicit policy.

The live validator global failed-statement counter is also corrected so `Statements failed` agrees with actionable SQL error rows.
