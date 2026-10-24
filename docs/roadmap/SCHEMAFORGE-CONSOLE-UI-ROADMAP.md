# SchemaForge Console — Permanent UI Roadmap Index

This is the durable navigation index for the independent Console UI workstream. The detailed existing stage notes remain in this directory; **Glossary governance P1–P5 is a separate acceptance track**.

| Gate | Scope | State as of 2026-10-24 |
| --- | --- | --- |
| UI-C6a | Read reports/Glossary evidence from completed ZIPs without new REST | User accepted Word ZIP preview and evidence; Batch large evidence from V0.5.6 automatic tests PASS, Chrome long-batch check open |
| UI-C6c–C6e | Mermaid/Graphviz render, pan, focus, relationship depth | Basic SVG rendering and table localization accepted by user; some interactions not fully signed off |
| UI-C9b–C9c | Runtime JDBC metadata selection, optional schema | Frontend contract tests PASS; live seven-DBMS integration acceptance open |
| UI-C10h | Large Batch manifest and local ZIP re-opening | User accepted preview of larger Batch; see V0.5.4 |
| UI-C10i | Streaming large Batch Glossary evidence | **52 Java + 60 Node + Vite build PASS on user Windows in V0.5.6**; Chrome legacy evidence gate open |
| UI-C11 | Lazy pages, lazy diagram panel, first-load bundle budget | V0.5.7 source implementation; Node static budget tests PASS; Windows build & browser network acceptance open |
| UI Security | Login/RBAC for shared production | Open; temporary current controls retained, not silently removed |

**Non-regression rule:** This workstream must not modify Canonical Model, DDL generators, seven DBMS dialects, or existing H2. Use `scripts/accept-console-v056.cmd` / `scripts/accept-console-v057.cmd` to exercise 52 focused Java tests and all frontend tests. A successful Vite build is not a live seven-DBMS integration test.

**Scope restraint:** Legacy abbreviations are deferred and are not reclassified or edited during Console optimization.

Latest stage detail: [`SCHEMAFORGE-CONSOLE-UI-V057-LAZY-LOADING.md`](SCHEMAFORGE-CONSOLE-UI-V057-LAZY-LOADING.md). Previous stages:

- [`SCHEMAFORGE-CONSOLE-UI-V056-GLOSSARY-BATCH-EVIDENCE.md`](SCHEMAFORGE-CONSOLE-UI-V056-GLOSSARY-BATCH-EVIDENCE.md)
- [`SCHEMAFORGE-CONSOLE-UI-V054-LARGE-BATCH-ZIP-PREVIEW.md`](SCHEMAFORGE-CONSOLE-UI-V054-LARGE-BATCH-ZIP-PREVIEW.md)
- [`SCHEMAFORGE-CONSOLE-UI-V053-500MB-UPLOAD.md`](SCHEMAFORGE-CONSOLE-UI-V053-500MB-UPLOAD.md)
- [`SCHEMAFORGE-CONSOLE-UI-V052-CONNECTION-SCHEMA-DISCOVERY.md`](SCHEMAFORGE-CONSOLE-UI-V052-CONNECTION-SCHEMA-DISCOVERY.md)
- [`SCHEMAFORGE-GLOSSARY-DEFAULT-ON-V055.md`](SCHEMAFORGE-GLOSSARY-DEFAULT-ON-V055.md)
