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
schema/mortgage-registry.edn DataScript schema for the three planes
data/datascript-tx.edn       GENERATED projection — never hand-edit
scripts/emit_tx.cljs         regenerates data/ from src/ (nbb)
test/mortgage/facts_test.cljc honesty invariants
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
