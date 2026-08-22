# ALTER/Migration M2-R4 - MySQL CHECK formatting equivalence

The MySQL M2 live pilot proved that the structural ALTER statements execute, but the post-migration metadata comparison could still report the recreated CHECK as changed.

M2-R4 keeps comparison strict while canonicalizing only SQL formatting that has no logical effect in MySQL `information_schema.CHECK_CONSTRAINTS.CHECK_CLAUSE`:

- identifier backticks;
- automatic `_utf8mb4` / `_utf8mb3` string introducers;
- whitespace around commas and parentheses outside string literals.

The normalizer is quote-aware. Literal contents are not compacted, so `'A, B'` is not considered equal to `'A,B'`. If a residual CHECK remains in the live pilot, `residual-diff.txt` now includes both raw expressions for direct evidence.

CREATE DDL remains unconditional. Flyway-compatible ALTER remains an additional artifact only when the live table differs from the desired canonical table.
