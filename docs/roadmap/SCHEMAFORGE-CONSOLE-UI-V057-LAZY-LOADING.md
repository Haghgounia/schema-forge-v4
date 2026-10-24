# SchemaForge Console UI V0.5.7 — Load-on-Demand / Bundle Budget

Date: 2026-10-24. Baseline: V0.5.6 FULL source and completed Windows acceptance of 52 focused Java tests, 60 Node tests, TypeScript and Vite build. Gate: **UI-C11 — Frontend Payload**.

## Scope and invariants

- Only React/TypeScript/Vite packaging, frontend test cases, release docs and a Windows acceptance wrapper change. **No modifications to Java, REST routes, Glossary rules, Canonical Model, DDL, H2 files, JDBC routing, or any of the seven DBMS dialects.**
- Render the home page eagerly for a responsive starting point; use `React.lazy` + `Suspense` for nine functional views (Generation, Connections, Conformance, CRUD, Diagram and four Glossary panels).
- Load ZIP reports as a secondary lazy view only when results are inspected; load ZIP diagram panel only when the **Diagrams** report tab is chosen. Load the shared rendered SVG viewer only for an actual diagram.
- Existing `diagramRender.ts` dynamic `import('mermaid')` and `import('@viz-js/viz')` remain unchanged. Render locally, never via remote diagram servers.
- All administrative secrets remain in volatile React memory, not in a client bundle. Review approval/import behavior, the 128 MiB evidence-scan ceiling and ZIP safeguards are unchanged.

## Build budget and large vendor chunks

Vite 8 creates a static entry graph and dynamic import graph. `build.manifest=true` exports those exact graphs. The post-build `ui/scripts/check-lazy-build.mjs` **fails** the build when:

- Any of 12 route/report/diagram component chunks is in the HTML entry's transitive **static** import graph.
- `viz-*.js`, `mermaid.core-*.js`, or `jszip.min-*.js` appears in the static first-load graph.
- Sum of Gzip-compressed HTML-entry JavaScript and associated CSS (static imports included) exceeds **240 KiB**.

`chunkSizeWarningLimit` moves from Vite's advisory 500 kB to **1400 kB only because Graphviz's approximately 1.36 MB uncompressed vendor chunk is intentionally downloaded on demand**. This does not reduce the size of the Graphviz engine; the separate first-load gate prevents it from becoming eager by accident. Any unrelated new large chunk can still trigger a Vite advisory and needs investigation. The build checker reports the *measured* eager gzip total; do not claim a V0.5.7 payload size before a successful real Vite build.

## Acceptance criteria

| Gate | Check | Current evidence |
| --- | --- | --- |
| UI-C11-1 | All functional views lazy; home eager | Static Node tests added, local PASS |
| UI-C11-2 | ZIP reports and diagram viewer load only upon use | Static Node tests added, local PASS |
| UI-C11-3 | Manifest static graph excludes heavyweight engines and first-load total <= 240 KiB gzipped | Simulated manifest tests PASS; **real Windows Vite build OPEN** |
| UI-C11-4 | All prior REST/ZIP/Glossary evidence, zoom/focus and seven DBMS regressions | Prior Windows V0.5.6: 52 Java + 60 Node PASS; rerun V0.5.7 **OPEN** |
| UI-C11-5 | Chrome Network: opening Home does not request Mermaid/Graphviz; opening diagrams fetches engine only upon render | **OPEN** |
| UI-C11-6 | No change to Canonical/DDL/Java/H2; full preservation | Source diff gate: **no Backend edits**; run existing 52 Maven tests on Windows |

## Windows acceptance (CMD, no PowerShell)

```cmd
cd /d D:\Projects\schema-forge-v4
call scripts\accept-console-v057.cmd
cd ui
npm run dev
```

Using Chrome DevTools > Network > Disable cache, reload `http://127.0.0.1:5173/#/overview` and verify no `viz`, `mermaid.core` or `jszip.min` loads. Open a Glossary/Connections tab, then the Generation page without opening a ZIP and confirm no heavy diagram engine loads. Open a ZIP with an ERD, select **Diagrams** and Mermaid/Graphviz and verify expected lazy downloads and SVG/focus behaviors. Check direct `/#/glossary/history` and `/#/connections` links after a hard reload. Build checker output `[PASS] Initial UI: ... gzip bytes` is mandatory for final gate.

**Environment limitation at authoring:** npm registry DNS returns `EAI_AGAIN`; dependencies cannot be installed, so a real Vite build, full npm test and browser network verification cannot be claimed as PASS in this delivery.
