# EA/Word Offline Spelling V1 — SchemaForge V4 (2026-10-21)

## Baseline and measured bottleneck

On the same 6,370,350-byte EA XMI input, `Deposit_Account_Operations_6_RC_2026-09-22.xml`:

| User's Windows A/B run | Enabled HTTP LanguageTool | Spell check disabled |
|---|---:|---:|
| XML parse | 1.230 s | 1.202 s |
| Validation + enrichment | **421.333 s** | **0.093 s** |
| Other generation | 29.316 s | 27.385 s |
| Complete EA request | **451.887 s** | **28.685 s** |

The root cause is the legacy `LanguageToolSpellCheckService` making one HTTP request per table/column identifier; on this file that is 114 tables + 1,767 columns. It was **not** slow XML parsing.

## What V1 changes

- Default spelling provider for the Spring Boot REST application is now `OFFLINE`, with spelling still enabled by default. No HTTP request is made by the `OFFLINE` provider. An explicitly selected `LANGUAGETOOL` remains supported.
- The embedded `en_US.aff` / `en_US.dic` Hunspell dictionary is read once and its English prefix and suffix forms are expanded into an in-memory lexical set. No native libraries or new Maven dependencies are needed. This is a **Hunspell-dictionary-derived lexical checker**, **not** a complete Hunspell engine or a grammar/style checker: non-English, compounding, morphology beyond the bundled en_US affix rules, and advanced Hunspell replacement rules are outside the V1 contract.
- `IdentifierSpellTokenizer` splits underscores, hyphens and camelCase. Numbers, very short tokens, explicitly configured technical terms and distant unknown words do not trigger speculative typo warnings. Up to the configured maximum near matches are suggested; the real canonical name is unchanged.
- Suggestions are ranked first by bounded edit distance, then by the 800 single-word candidate keys extracted from the **already bundled** `glossary/01_Atomic_Terms.xlsx` baseline. The priority list is only a **suggestion-ranking hint**: it is **not** loaded from live H2, does not change `APPROVED`/`REJECTED`/`CANDIDATE` status, and cannot create or approve a Glossary entry. Online/incremental H2 Glossary refresh remains separate roadmap work.
- Per-token in-memory caching makes repeated `ACCOUNT`, `ID`, `CUSTOMER`, etc. cheap across thousands of identifiers. The cache has a bounded clear-on-limit safety policy.
- `SpellCheckService` / `SpellingError` / `SPELLING_WARNING` public contracts are retained. Only spelling-warning content/count can differ from LanguageTool; **no DDL, table names, column names, keys, constraints, schema conversion or DBMS dialect behavior changes**.
- The existing production H2 file is never included in either delivery ZIP; no migrations or writes to H2 are introduced.

### Dictionary attribution and licensing

`src/main/resources/spelling/en_US.aff` and `en_US.dic` are copied from the Debian `hunspell-en-us` distribution installed in the development environment (SCOWL word-list lineage). `NOTICE-hunspell-en_US.txt` contains the complete packaged copyright and redistribution notices (Kevin Atkinson/SCOWL, Ispell, WordNet and other constituent lists). Keep the notice when distributing binary or source bundles. For downstream/commercial redistribution, legal owners should review the supplied notices.

## Configuration

In `src/main/resources/application.yml`:

```yaml
schemaforge:
  spell-check:
    enabled: ${SCHEMAFORGE_SPELL_CHECK_ENABLED:true}
    provider: ${SCHEMAFORGE_SPELL_CHECK_PROVIDER:OFFLINE}
    # The legacy external endpoint is used ONLY if provider=LANGUAGETOOL.
    endpoint: https://api.languagetool.org/v2/check
    maximum-suggestions: 3
    fail-open: true
    technical-terms: [MEPS, BEHSAZAN, MELLAT, BNCP, SUBLEDGER, TXN, RECON, PAYINST, ENR, RECALC,
                      IDEMPOTENCY, JSON, IBAN, GRANTOR, NOSTRO, VOSTRO, KYC, AML, CIF, UUID, SQL]
```

Windows CMD, before starting Spring Boot:

```cmd
cd /d D:\Projects\schema-forge-v4
set "SCHEMAFORGE_SPELL_CHECK_ENABLED=true"
set "SCHEMAFORGE_SPELL_CHECK_PROVIDER=OFFLINE"
set "SCHEMAFORGE_EA_PERF_ENABLED=true"
if not exist logs\ea-performance mkdir logs\ea-performance
mvnw.cmd spring-boot:run > logs\ea-performance\ea-performance-offline-spelling.log 2>&1
```

- Set `SCHEMAFORGE_SPELL_CHECK_PROVIDER=LANGUAGETOOL` **explicitly** only for legacy HTTP behavior (potentially slow, sends model identifiers outside the machine).
- Set `SCHEMAFORGE_SPELL_CHECK_ENABLED=false` to disable spelling completely; structural, naming and Glossary validations remain unaffected.
- Unknown provider names fail fast instead of silently making a network request.

## Repeatable test gate

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd clean ^
  -Dtest=OfflineHunspellSpellCheckServiceTest,SchemaPreparationOfflineSpellingTest,LanguageToolSpellCheckServiceTest,SpecificationSpellCheckTest,GlossaryPluralNamingPolicyTest,GlossaryXlsxExportAndSpellingTest,EaGenerationOrchestratorTest ^
  test
```

After Maven passes, restart the server as shown above, upload **the exact same EA XML with `platform=all` and the same DBMS availability/settings** as in the earlier A/B trial, then extract:

```cmd
findstr /I /C:"section=prepare" /C:"section=request" logs\ea-performance\ea-performance-offline-spelling.log
```

For strict acceptance compare emitted canonical JSON, DDL statements and DBMS selection against the earlier SpellCheck-disabled bundle (exclude expected spelling warning/report differences and non-deterministic generation IDs, timestamps, hashes and ZIP packaging). Check `SPELLING_WARNING` for deliberate typos `CUTOMER`, `CUSTMER`, `TRANSACTOIN`; check glossary naming rules independently. Run `mvnw.cmd clean test` after the targeted gate if production rollout is intended.

## Local (not Windows HTTP) Java 21 micro-benchmark

Using the user's exact XML, the test harness extracted 1,881 identifier occurrences (114 table names, 1,767 column names). With a 512 MiB Java heap and a real bundled Hunspell dictionary:

- Dictionary initialization plus representative typo probes: ~0.4–0.5 seconds.
- A full scan of 1,881 identifiers: ~35.6 milliseconds; subsequent passes with cached token results were ~22–34 milliseconds in the final run (local JVM/machine noise applies).
- `CUTOMER` → `CUSTOMER`, `CUSTMER` → `CUSTOMER`, `TRANSACTOIN` → `TRANSACTION`, `PROVINC` → `PROVINCE`, `BENEFCIARY` → `BENEFICIARY`, `WITHDRAWL` → `WITHDRAWAL` as top suggestions; known correct deposit/account/transaction names receive no spelling warning.
- The three false positives encountered on the real input (`IDEMPOTENCY`, `JSON`, `GRANTOR`) were explicitly added to the REST technical exclusions, along with other common codes such as `IBAN`. With those configured, the final standalone scan emitted **0 spelling warnings** for the 1,881 source names; intentional typo probes still returned suggestions. This is a source-corpus observation, not an accuracy guarantee.
- No HTTP/Spring Boot/metadata access was involved in this local micro-benchmark. **Do not treat this as proof the full EA service takes under 30/35 seconds**; the next Windows API log is the release gate.

## Limitations, quality policy and roadmap

This is a conservative lexical typo detector. It deliberately skips unknown terms that have no credible near match instead of labeling every niche bank term a misspelling. New word governance, semantic meaning, approved singular/plural policy and status review belong to the existing Glossary pipeline; the offline provider does **not** import or authorize anything. If future workloads require more advanced morphology, evaluate a maintained complete Hunspell implementation with comparable regression tests. The immediate goals are zero network requests, low latency, no code/name mutation and useful human-review suggestions. Live H2 Glossary priority refresh, corpus precision/recall testing and multilang support remain follow-ups.

### Status

The standalone offline provider and a Java 21 smoke benchmark pass in the assistant environment. **Full Maven integration and Windows `platform=all` timing have not yet been run for this version** and must be accepted on the Windows project installation before claiming a production release.
