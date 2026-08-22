# ALTER/Migration M2-R12 - SQL Server CHECK catalog normalization

Evidence from the real SQL Server live pilot showed a residual false-positive CHECK replacement:

- live catalog: `([ID]>(0) AND [PARENT_ID]>(0))`
- desired model: `(ID > 0) AND (PARENT_ID > 0)`

M2-R12 normalizes only SQL Server catalog-rendering differences during CHECK comparison:

- ordinary identifier brackets such as `[ID]`
- scalar numeric parentheses such as `(0)`
- redundant parentheses around atomic boolean predicates
- whitespace adjacent to SQL operators

Boolean grouping that changes precedence and string-literal contents remain significant.
No CREATE SQL, ALTER SQL, destructive-confirmation policy, or other database dialect behavior is changed.
