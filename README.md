# mortgage-registry

**不動産ローン(mortgage)について、各国の「手続き」「公的支援」「組織」を1つの
EDN 面に載せた registry.** A jurisdiction-keyed catalog of how a loan secured on
real property is actually taken out and perfected, which public support
programmes that jurisdiction operates, and which organizations own each of
those — non-adjudicating reference data, never advice and never a decision.

Part of the [`cloud-itonami`](https://github.com/cloud-itonami) compliance-fact
family. Design ADR: `com-junkawasaki/root`
`90-docs/adr/2608011200-mortgage-registry-procedure-support-organizations.edn`.

## Why this repository exists

Two sibling actors already covered the ends of a property purchase, and neither
covered the middle:

| repo | plane | what it holds |
|---|---|---|
| [`cloud-itonami-isic-6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492) | credit | generic consumer-credit disclosure/licensing per jurisdiction (Credit-LLM ⊣ Credit Governor) |
| [`cloud-itonami-isic-6810`](https://github.com/cloud-itonami/cloud-itonami-isic-6810) | transfer | property-transfer / title / land-registry requirements (Realtor-LLM ⊣ RealtorGovernor) |
| **this repo** | **security + support** | **how the loan is secured against the property, and what public support exists for it** |

`isic-6492`'s own README is explicit that it models *"no loan-purpose/collateral
analysis"* — so mortgage-specific facts had nowhere to live. There was also no
plane anywhere in the fleet for **public support programmes** (state guarantee,
subsidised loan, tax relief), which is the part an actual borrower asks about
first.

## The three planes

```clojure
(require '[mortgage.facts :as facts])

(facts/spec-basis "DEU")          ; => the whole entry, or nil if uncovered
(facts/support-programmes "NLD")  ; => [{:id "nld.nhg" ...}]
(facts/organizations "JPN")       ; => [{:id "jhf" ...} ...]
(facts/all-organizations)         ; => the organization plane on its own
(facts/coverage ["JPN" "BRA"])    ; => covered / missing, honestly
(facts/unverified-claims)         ; => the catalog's own to-do list
(facts/summary)                   ; => one line per jurisdiction
```

### Seeded coverage (6 jurisdictions, 8 programmes, 16 organizations)

| iso3 | security instrument | support programmes seeded |
|---|---|---|
| JPN | 抵当権, perfected by 登記 at 法務局 | 【フラット３５】(住宅金融支援機構 + 民間金融機関) · 住宅借入金等特別控除 (国税庁) |
| USA | mortgage / deed of trust, recorded per-county | FHA 203(b) mortgage insurance (HUD/FHA) |
| GBR | legal charge, registered at HM Land Registry | Shared Ownership · Mortgage Guarantee Scheme (in transition) |
| DEU | Grundschuld, Abteilung III des Grundbuchs | KfW 300 „Wohneigentum für Familien – Neubau" |
| FRA | hypothèque / privilège de prêteur de deniers | Prêt à taux zéro (PTZ) |
| NLD | hypotheekrecht, ingeschreven bij het Kadaster | Nationale Hypotheek Garantie (NHG) |

**6 of roughly 194 jurisdictions.** That is a seed, not a survey. Every
uncovered jurisdiction resolves to `nil`, never to a permissive default.

## The honesty contract

This catalog is built to make it structurally hard to look more complete than
it is. Four rules, enforced by `test/mortgage/facts_test.cljc` (8 tests, 132
assertions):

1. **Nothing is asserted without a source.** Every jurisdiction's procedure and
   every support programme carries an `https` `:provenance` URL, and every
   programme carries `:retrieved-at`.
2. **Every jurisdiction publishes its own gaps.** Each entry's
   `:verification` block lists `:fetched-this-session` *and* `:not-verified`.
   An empty gap list is a test failure — it would claim a completeness no
   seeded catalog has.
3. **Figures that were not read are not written.** Where a source could not be
   text-extracted (the FCA Handbook renders its rule text client-side; the
   HUD 203(b) page does not cite its own statute), the entry says so verbatim
   instead of filling the gap from memory. See `:verbatim-rule-text-note`,
   `:statutory-cite-note`, `:rate-note`, `:figures-note`.
4. **Non-adjudicating.** `:eligibility-signals` disclose a programme's
   published conditions as *signals*. This repository never concludes that a
   person qualifies, never computes affordability, and never calculates a
   deadline — the same hard line `saisei` holds for insolvency procedure.

`(facts/unverified-claims)` returns all of rule 2's gaps as data, so the
to-do list is queryable rather than buried in prose.

## The observation contract (`mortgage-observation/2`)

`src/mortgage/observation.cljc` is the contract that turns a reading of this
catalog into a **provenance-preserving, re-observable claim** — and that makes
it structurally hard for an observation run to look more certain than it is.
v2 keeps every shape /1 froze and adds the auditable-refresh machinery
(item 10 below); a /1-stamped artifact and a /2-stamped artifact stay
comparable on purpose:

1. **Source receipt** (`receipt`) — one frozen record per source reading:
   https URL, closed-vocabulary source class, language, issuing entity,
   jurisdiction, `sha256:` content-hash, `observed-at` (when WE fetched) vs
   `asserted-at` (the source's own date), and the read method. Same bytes
   re-observed later = a NEW receipt; that is how a refresh is seen. A
   receipt without a hash is a rumor and is refused; a receipt whose stored
   id no longer derives from its own hash (edited after freezing) is refused
   too — never re-branded.
2. **Typed observation** (`observation`) — one subject
   (jurisdiction × plane × subject-id, which must EXIST in this catalog — no
   phantom entities), bound to a measurement window, its receipts, its
   verbatim figures and its missingness. Receipts from another jurisdiction
   are refused (entity separation).
3. **Currency and area basis** — a monetary figure MUST carry its currency
   and the date its amount is nominal at; a dimensional figure MUST carry its
   measurement unit. Nothing is normalized into a comparable number anywhere:
   amounts at different dates and areas under different standards are not
   interchangeable (they ride as verbatim text plus basis).
4. **Method / version** — every artifact carries `mortgage-observation/2`.
   There is no model on this path anywhere.
5. **Missingness** — flags come from a closed vocabulary, and an observation
   that declares NO gaps where the catalog's own `:verification
   :not-verified` publishes some is REFUSED (silence would claim
   completeness). The catalog's gaps ride into every proposal.
6. **Derived observations** (`coverage-observation`, `jurisdiction-observation`)
   — per-plane absent/located/verbatim counts from `mortgage.plan` plus each
   jurisdiction's published gaps, under the window. A coverage COUNT — not a
   market metric, not a valuation, not a score, not a ranking.
7. **Refresh history** (`refresh`, pure) — append-only in data; the same
   observation id can never be recorded twice; a re-observation links to what
   it refreshes via `:obs/refresh-of`, and (v2) a link to an observation about
   a DIFFERENT subject is refused at append time — lineage is per subject.
8. **Hyakka proposal shape** (`hyakka-proposal`) — the exact claim shape for
   the `fudosan` corpus: one claim per figure plus one per observed subject,
   each with its receipt, verbatim value, basis, gaps and `:no-model true`.
   It is DATA — this contract sends nothing anywhere, and a proposing run
   that uses it owns the actual proposal.
9. **Query / readback** (`readback`, `readback-coverage`) — the latest
   observation at or before an as-of, with every receipt re-validated on the
   way out (tampered histories are refused, never returned); a miss is
   reported as a miss, never defaulted.
10. **Auditable refresh (v2)** (`refresh-delta`, `readback-chain`) — the
    comparison a refresh owes its predecessor, as an artifact instead of a
    promise. `refresh-delta` compares two frozen observations of the SAME
    subject at the VERBATIM level: which figure fields were added, which were
    REMOVED (reported, never dropped), which changed raw text or basis —
    both sides carried in full, no numeric difference computed, no amount
    normalized — plus missingness flag and gap movement, the receipt ids of
    BOTH generations, and `:delta/kind :unchanged` when nothing moved.
    `readback-chain` walks the `:obs/refresh-of` lineage back to its origin,
    oldest first, revalidating every generation on the way out, refusing a
    truncated or cycling lineage and a chain element about another subject,
    and returning the pairwise deltas aligned to the chain.

Every rule refuses loudly (`ex-info` with a `:refusal/code` from the
documented `refusals` set) instead of degrading quietly.

**Two gaps this contract surfaces instead of papering over.** (a) The
workspace real-estate scope's `:source-policy :allow` vocabulary has no class
for state programme-operator publications or government portals — the two
classes this catalog mostly reads. Receipts carry the true class and proposals
flag `:proposal/source-class-unmapped`; nothing is relabelled into a scope
class it does not have. (b) The proposal props (`prop/mortgage-*`) are
contract-local and NOT registered in the Hyakka ontology; every claim carries
`:proposal/ontology-registration-pending true` so a proposing run cannot
quietly present them as already-registered props.

Deterministic fixtures (no network at test time) live in
`test/fixtures/observation/` and cover temporal refresh, entity separation,
currency/unit basis, provenance, missingness and query/readback. The NLD
receipt was recorded live on 2026-09-01 (polite GET, robots respected, no
login/paywall/captcha) with its sha256 — a re-fetch hash that differs is an
observation, not a fixture failure.

```bash
nbb --classpath src:test run-tests.cljs   # 53 tests / 354 assertions, 0 failures
```

## Worldwide coverage plan

`src/mortgage/plan.cljc` is the plan for the other ~182 jurisdictions, written
as data so progress is *measured* rather than asserted.

**Universe: 188 jurisdictions**, derived from the existing
`cloud-itonami-iso3166-*` family (225 repositories, minus 37 agency /
sub-national codes such as `JPN-FSA`, `USA-SEC`). Not typed by hand —
`data/jurisdiction-universe.edn` is generated and writes out the excluded set
so the filter can be audited.

**Status is computed, never declared.** `plan/plane-status` reads the catalog
entry: a plane is `:verbatim` only if the evidence that defines it is
physically present. Nobody can mark a jurisdiction done. Today:

| plane | absent | located | verbatim |
|---|---|---|---|
| organizations | 182 | 6 | — |
| procedure | 182 | **2** | 4 |
| support | 182 | — | 6 |

The two `:located` procedures are **GBR and FRA** — exactly the two whose
sources resisted extraction. The plan surfaces that as outstanding work rather
than letting the seed look uniform.

**Waves**, each of which must justify its own position (a test fails a
rationale shorter than 80 characters):

| wave | what | why there |
|---|---|---|
| 0 | the seeded 6 | proves the three-plane shape end to end |
| 1 | IDN, AUS, CAN (+ `AUS-NSW`/`CAN-ON`/`USA-CA` exemplars) | jurisdictions the sibling actors already cite — cheapest possible wave, and it stops the fleet disagreeing with itself |
| 2 | 27 EEA states | Directive 2014/17/EU harmonises ESIS/APRC/withdrawal, collapsing the per-country question to transposition + land register + support programme |
| 3 | 20 large non-EEA markets | ordered by mortgage market size — **blocked** until that ordering has a source |
| 4 | the remaining ~140 | `:located` organizations **only** — exhaustive in *who to ask*, silent on what the answer is |

**Shortcuts must state what they do not buy.** `plan/shared-instruments`
records MCD, OHADA and Torrens with both `:reduces` and `:does-not-reduce`.
MCD reduces *research* (read the directive once) and never *citation* (all 27
still need their own transposition and URL). Torrens is recorded explicitly as
reducing **nothing** legally, so a future contributor cannot mistake a shared
registration style for a shared legal basis.

**Cost is measured, not estimated.** `plan/throughput` records the real
2026-08-01 session: 6 jurisdictions, 24 web calls, ~4 successful fetches per
jurisdiction at verbatim level, ~25% call failure rate. There is deliberately
**no completion date** — the waves are ordered work, not a schedule.

**`plan/source-access-hazards`** is the most reusable artefact here: which
access method failed on which host (`mba.org` 403, FCA Handbook client-side
rendered, `hypo.org` PDF resolves but yields no text — *a resolving URL is not
a read source*) and which worked (`kfw.de` PDF read page by page). Extend it
whenever a source resists a method, so the next run does not re-burn fetches
rediscovering it.

**`plan/open-decisions`** names three things the plan refuses to decide alone:
the wave-3 ordering source, whether to adopt 6810's sub-national exemplar
keys, and whether a scheduled fleet routine may consume `next-batch`. Each
records what deferring it blocks.

```bash
nbb --classpath src scripts/emit_coverage_plan.cljs   # refresh the queue snapshot
```

```clojure
(plan/next-batch universe 12)   ; the next 12 units of work, earliest wave first
(plan/progress universe)        ; counted against 188, never against the seeded 6
```

## Joining to the rest of the fleet

The organization plane carries `:isic` + `:country` on every entry, which is
the join key to the
`cloud-itonami-assoc-<isic>-<iso3>-<abbrev>` family — e.g. `{:isic "6492"
:country "USA"}` joins to
[`cloud-itonami-assoc-6492-usa-mba`](https://github.com/cloud-itonami/cloud-itonami-assoc-6492-usa-mba)
(Mortgage Bankers Association) and `{:isic "6419" :country "JPN"}` to
[`cloud-itonami-assoc-6419-jpn-zenginkyo`](https://github.com/cloud-itonami/cloud-itonami-assoc-6419-jpn-zenginkyo).
`data/datascript-tx.edn` tags every entity `:source/dataset
"mortgage-registry"` so the catalog can be loaded next to the other datasets on
the superproject's DataScript query plane without ambiguity.

## Layout

```
src/mortgage/facts.cljc      catalog + query fns (canonical; portable .cljc)
src/mortgage/observation.cljc observation contract over the catalog (receipts,
                             windows, currency/area basis, missingness, refresh
                             history, Hyakka proposal shape, readback)
schema/mortgage-registry.edn DataScript schema for the three planes
data/datascript-tx.edn       GENERATED projection — never hand-edit
scripts/emit_tx.cljs         regenerates data/ from src/ (nbb)
test/mortgage/facts_test.cljc honesty invariants
test/mortgage/observation_test.cljc deterministic observation fixtures (no network)
run-tests.cljs               nbb test entry point
```

```bash
nbb --classpath src:test run-tests.cljs   # 8 tests, 132 assertions
nbb --classpath src scripts/emit_tx.cljs  # regenerate data/datascript-tx.edn
```

Script host is **nbb only** (ADR-2607173000) — no `bb.edn`, no shell scripts.
`deps.edn` exists so the same `.cljc` loads on a JVM classpath for linting and
for consumers embedding the catalog; there is no JVM-only code here.

## Extending coverage

Adding a jurisdiction is a **research task, not a code task**. Fetch the
official source, quote it, record `:retrieved-at`, and — most importantly —
record what you could *not* verify in the same session under
`:verification :not-verified`. Never invent a procedure step, a programme
figure, or an eligibility ceiling to make the table longer.

## Licence

AGPL-3.0-or-later.
