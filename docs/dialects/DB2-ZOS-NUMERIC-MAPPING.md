# Db2 for z/OS numeric mapping foundation

This step introduces the first Db2 for z/OS dialect component without yet adding
Db2 to `DatabasePlatform`.

## SAFE strategy

| Source | Db2 for z/OS |
|---|---|
| `NUMBER(p)` | `DECIMAL(p,0)` |
| `NUMBER(p,s)` | `DECIMAL(p,s)` |

## OPTIMIZED strategy

| Source | Db2 for z/OS |
|---|---|
| `NUMBER(1..4,0)` | `SMALLINT` |
| `NUMBER(5..9,0)` | `INTEGER` |
| `NUMBER(10..18,0)` | `BIGINT` |
| `NUMBER(19..31,0)` | `DECIMAL(p,0)` |
| `NUMBER(p,s>0)` | `DECIMAL(p,s)` |

The mapper rejects unbounded `NUMBER` and precision above 31 because those
definitions cannot be represented losslessly by Db2 for z/OS `DECIMAL` without
an explicit migration decision.

The global configuration remains:

```text
schemaforge.numeric-mapping.strategy=SAFE|OPTIMIZED
SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY=SAFE|OPTIMIZED
```
