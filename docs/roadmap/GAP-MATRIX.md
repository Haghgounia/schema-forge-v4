# SchemaForge V4 Gap Matrix

This file records only gaps verified against the current V4 source code.

| ID | Capability | Status | Evidence / next action |
|---|---|---|---|
| G001 | Oracle character length semantics (`CHAR` / `BYTE`) | Done | Model, parser, JSON and Oracle dialect updated and tested. |
| G002 | One `.json` and one `.sql` output for each input Word document | Done | Implemented by the current offline pipeline. |
| G003 | Duplicate-column recovery and warnings in SQL | Done | Present in the current recovery and generation pipeline. |
| G004 | Oracle sequence `START WITH` and `NOORDER` | Done | Present in the current generator. |
| G005 | Sequence-backed logical identity | Done | `NEXTVAL` defaults are emitted instead of an unused Oracle identity. |
| G006 | Virtual/generated column expression | Done | Present in `Column.generatedExpression` and Oracle generation. |
| G007 | Online Oracle metadata validation | Deferred phase | Must remain separate from the offline generator. Existing earlier metadata code will be assessed before any port. |
| G008 | Production-script features not represented in V4 | To verify | Must be confirmed class-by-class from the current source before implementation. |

## Working rule

No new capability is added from memory or assumption. Each next item must first be demonstrated as absent or incomplete in the current V4 code and in its tests.
