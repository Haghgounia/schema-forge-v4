# MySQL P3-R2 - semantics-preserving storage adaptation

Live MySQL 8.4.11 execution reduced P3 failures to two storage-limit cases:

- ER_TOO_BIG_FIELDLENGTH (1074): VARCHAR(30000) under utf8mb4.
- ER_TOO_BIG_ROWSIZE (1118): a table whose declared utf8mb4 VARCHAR budget exceeds 65,535 bytes.

P3-R2 keeps the canonical logical character length while adapting only target storage:

- A VARCHAR that cannot fit MySQL utf8mb4 VARCHAR is promoted to TEXT/MEDIUMTEXT/LONGTEXT.
- If the declared VARCHAR byte budget alone exceeds the MySQL logical row-size limit, the minimal set of largest eligible VARCHAR columns is promoted to off-row TEXT storage.
- Each promotion gets an enforced CHAR_LENGTH(column) <= original_length CHECK constraint.
- Each promotion is annotated inline in the generated SQL.
- Columns participating in PK/UK/index/FK/default/generated semantics are not promoted automatically; SchemaForge fails instead of guessing prefix-index or altered semantics.

The DB-neutral generator gained default table-aware datatype and inline-column-constraint hooks. Existing dialect behavior is unchanged by default.
