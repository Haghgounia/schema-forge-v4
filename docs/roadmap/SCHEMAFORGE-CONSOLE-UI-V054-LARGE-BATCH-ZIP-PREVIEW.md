# UI-C10h - Large Batch ZIP previews (Console V0.5.4)

**Source baseline:** `schemaforge-v4-2026-10-24-console-upload-500mb-V053-FULL.zip`. Scope: React/TypeScript client, tests and documentation only. This does not change Spring Boot, upload limits, Java, DDL, Canonical data, H2, or REST contracts.

## Evidence and root cause

- User Chrome screenshot: `schemaforge-batch-20261024-093449-136.zip` was downloaded and the diagram index reported 2,024 items, but the viewer displayed `manifest.json` as unreadable and showed no manifest counters.
- In V0.5.3 the viewer capped **each uncompressed JSON/report file at 2 MiB** even when the compressed ZIP was well below the separate 100 MiB archive-inspection cap.
- This is a UI preview limit; upload 500MB/520MB, ZIP download and browser preview are independent. Without the exact batch ZIP, the manifest's true uncompressed size and backend outcomes have not been verified.

## V0.5.4 behavior

1. Parse a bounded **32 MiB uncompressed `manifest.json`**, validate it as JSON, retain authoritative `requestStatus`, `artifactOutcomes`, `validation`, `source` and batch counters. Retain up to 100 non-generated artifact examples and the exact number of non-generated outcomes, rather than keeping all entries in React state.
2. If the manifest exceeds 32 MiB or cannot be parsed, attempt to read only the bounded opening of the existing `reports/schemaforge-generation-summary.txt`. Clearly label the resulting status as **report-derived / partial preview**. Do not fabricate individual artifact counters, statuses or Glossary terms.
3. Raise bounded Glossary `summary.json` reading to 8 MiB. Retain the current 5 MiB evidence-import ceiling, so the existing authorized REST import contract is unchanged.
4. For large CSV/TXT/JSON reports, show an **up-to-2 MiB uncompressed prefix** with an explicit truncation notice. CSV preview contains up to 75 *complete* records, and does not present its partial row count as a total. Inline text is capped at 150,000 characters.
5. Allow extracting an individual report up to **64 MiB uncompressed**. On overflow, offer the original ZIP download, which is independent of the preview and does not trigger another generation request.
6. For Batch output, default to the Status tab when Glossary is absent and select `reports/batch-generation-summary.csv` (or the generation-summary text) in Files, ahead of the giant root Manifest.
7. Offer **open existing output ZIP** in the Generation UI. Read local `File` as Blob, run the identical bounded preview, and do not call Generation REST a second time.
8. Retain the existing 100 MiB **compressed ZIP** inspection cap, 256 KiB source limit for individual diagrams, 5 MiB Glossary CSV import cap and read-only ZIP processing.

## Gates

| Gate | Acceptance | Evidence / status |
|---|---|---|
| UI-C10h-1 | 2+ MiB manifest status, full counts and bounded exceptional sample | synthetic Node test, PASS |
| UI-C10h-2 | Large CSV preview bounded, valid rows only | synthetic Node test, PASS |
| UI-C10h-3 | 10-64 MiB individual report download separate from preview | synthetic Node test, PASS |
| UI-C10h-4 | >32 MiB manifest does not lie about status; text summary fallback | synthetic Node test, PASS |
| UI-C10h-5 | Previous REST, Glossary, diagram, upload-limit Node tests unchanged | `npm run test`, 51/51 PASS in authoring container using locally available JSZip dependency |
| UI-C10h-6 | Previously downloaded output ZIP can be re-opened locally without REST | structural plus JSZip offline Node test, PASS |
| UI-C10h-7 | TypeScript compile & production Vite build with installed project dependencies | **OPEN: Windows `npm run typecheck && npm run build`** |
| UI-C10h-8 | User's exact `schemaforge-batch-20261024-093449-136.zip` in Windows Chrome | **OPEN: not supplied in current turn** |

Do not treat HTTP 200 or successful ZIP download as proof of `SUCCESS` generation. Do not widen the browser archive cap to match the server's 500MB upload allowance. Reuse Java's existing Manifest and report values; do not duplicate business validation in React.
