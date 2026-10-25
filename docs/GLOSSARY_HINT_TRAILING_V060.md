# SchemaForge V0.6.0 — Trailing Glossary hints (2026-10-25)

## Motivation

Previous form placed `/* GLOSSARY_HINT: MISSING=... */` ahead of the physical
column name. This makes DDL hard to scan. The new compact **display-only** form
places a hint after the complete column definition and optional comma, before
existing inline `-- W:...` diagnostics.

## Examples

```sql
REQUEST_NO VARCHAR2(40 CHAR) NOT NULL, /* GH: NO */
CALC_ITEM_ID NUMBER(19,0) NOT NULL, /* GH: CALC */ -- W:SPELL
CHANGE_PAYLOAD_JSON CLOB NOT NULL, /* GH: PAYLOAD,JSON */
```

`GH:` means tokens not yet present as approved, candidate, or rejected Glossary
terms (review status `NEW`). `GH: NO` means the *word* `NO` is missing; it
must not be interpreted as "no missing words". `PLURAL:` marks plural COLUMN
components; it is independent of the missing-token warning.

```sql
PROFILES_ID NUMBER(19,0), /* PLURAL: PROFILES */
ALIEN_PROFILES_ID NUMBER(19,0), /* GH: ALIEN | PLURAL: PROFILES */
```

## Invariants

- Only a comment's location and vocabulary change. SQL tokens, object names,
  constraints and per-DBMS type mappings do not change.
- All seven DBMS and EA integrated run-all generation use the same DdlGenerator
  column-rendering path.
- Glossary hints appear only when enabled for the request. If no finding exists,
  no hint is emitted. No additional SQL line is introduced for a hint.
- The complete unknown-term statuses are retained in the Glossary CSV/JSON
  reports; SQL comments are deliberately shortened for legibility.
- Existing `-- W:...` diagnostics still occupy the end of the same SQL line.
- Legacy clients directly instantiating DdlGenerator without Glossary hints are
  unaffected.

## Operator acceptance (Windows)

From `D:\Projects\schema-forge-v4`, run
`scripts\accept-glossary-hint-trailing-v060.cmd`.

The script runs targeted JUnit tests, including all seven dialects, EA
integration, REST Word output and plural-component hints. A successful Maven
result does not mean SQL has been executed in live databases. Restart the
Backend service after installing source changes, then regenerate EA artifacts.
