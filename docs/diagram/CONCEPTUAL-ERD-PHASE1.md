# Conceptual ERD Phase 1

SchemaForge V4 adds a field-free conceptual ERD view without changing the existing ER or dependency diagrams.

## Output contract

For normal create-table generation, the canonical schema now produces two additional textual artifacts:

- `<base>.conceptual-erd.mermaid.mmd`
- `<base>.conceptual-erd.graphviz.dot`

ZIP batch generation also produces:

- `mermaid/batch/schema-conceptual-erd.mmd`
- `graphviz/batch/schema-conceptual-erd.dot`

The EA manifest records `conceptualErdMermaid` and `conceptualErdGraphviz`.

## Semantics

The conceptual ERD contains entity names, relationships, cardinality, optionality, and an evidence-backed relationship label. It deliberately omits columns, datatypes, lengths, precision/scale, defaults, and physical options.

Cardinality is derived only from canonical relational evidence:

- FK nullability: required FK => exactly one parent; nullable FK => zero or one parent.
- Exact PK/UK match on the FK column set: zero or one child per parent.
- Otherwise: zero or many children per parent.
- The minimum number of children is always zero because FK/PK/UK metadata alone cannot prove that every parent must have a child.

No relationship is inferred from column names such as `PARTY_ID`. No strong/weak-entity classification, associative-entity collapse, or business relationship verb is guessed.

## Views retained

`ER` and `DEPENDENCY` remain unchanged. The new diagram type is `CONCEPTUAL_ERD` (`conceptual-erd` in the Mermaid REST parameter).

The Mermaid and Graphviz renderers use the same cardinality resolver so both formats carry the same relationship semantics.
