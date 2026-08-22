# ALTER M2-R5 - MySQL CHECK escaped-literal normalization

Real MySQL 8.4 live metadata returned CHECK literals in this catalog form:

```text
(`STATUS` in (_utf8mb4\'A\',_utf8mb4\'I\',_utf8mb4\'S\'))
```

while the desired canonical expression was:

```text
STATUS IN ('A','I','S')
```

M2-R5 normalizes only this MySQL `information_schema` representation for comparison. It does not rewrite generated CREATE or ALTER SQL. Charset-prefixed catalog literals with backslash-escaped delimiters are reconstructed before the existing quote-aware formatting normalization. Internal apostrophes remain semantically preserved.
