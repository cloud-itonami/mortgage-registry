(ns mortgage.observation
  "The observation contract over `mortgage.facts` — how a reading of this
  catalog becomes a provenance-preserving, re-observable claim, and what such
  a claim can never become.

  ONE CONTRACT, ELEVEN PARTS. Every observation run against this registry
  produces the same shapes, so a refresh can be compared with a prior refresh
  and a reader can audit what was seen, when, from where, and what was NOT
  seen:

  1. SOURCE RECEIPT  — `receipt`: the frozen record of one source reading
     (https URL, class, language, issuing entity, jurisdiction, sha256
     content-hash, observed-at vs asserted-at, method). A receipt without a
     hash is a rumor.
  2. TYPED OBSERVATION — `observation`: one subject (jurisdiction × plane ×
     subject-id, which must EXIST in the catalog) bound to its window, its
     receipts, its verbatim figures (currency / area basis carried), and its
     missingness.
  3. MEASUREMENT WINDOW — every observation states `{:from :to}`; a claim
     without a window is un-dateable and refused.
  4. CURRENCY AND AREA BASIS — a monetary figure carries its currency and the
     date its amount is nominal at; a dimensional figure carries its unit.
     Neither is ever normalized into a comparable number here (scope boundary:
     amounts at different dates and areas under different measurement
     standards are NOT interchangeable).
  5. METHOD / VERSION — every artifact names `contract-version`; there is no
     model anywhere in this path (deterministic extraction only).
  6. MISSINGNESS / COVERAGE — flags come from a closed vocabulary and the
     catalog's own `:not-verified` gaps must be carried forward: an
     observation that declares no gaps where the catalog publishes some is
     refused (silence would claim completeness).
  7. DERIVED OBSERVATION — `coverage-observation` / `jurisdiction-observation`:
     counts computed from `mortgage.plan` (absent / located / verbatim per
     plane) plus each jurisdiction's published gaps. A coverage COUNT, never a
     market metric.
  8. REFRESH HISTORY — `refresh` / `history` (pure, append-only in data):
     re-observations link to what they refresh via `:obs/refresh-of`; the same
     observation id can never be recorded twice.
  9. HYAKKA PROPOSAL — `hyakka-proposal`: the exact claim shape proposed to
     the `fudosan` corpus, one per figure plus one per observed subject. The
     proposal carries the receipt, the verbatim value, its basis, its gaps,
     and `:no-model true`. It is DATA for the proposing run to carry — this
     contract sends nothing anywhere. Prop names are contract-local and NOT
     yet registered in the Hyakka ontology; the proposal says so instead of
     quietly borrowing someone else's prop.
  10. QUERY / READBACK — `readback`: the latest observation for a subject at
     or before an as-of, re-validating every receipt it returns and refusing
     tampered ones; a miss is reported as a miss, never defaulted.
  11. REFUSALS — every rule above refuses loudly (`ex-info` with a
      `:refusal/code`) instead of degrading quietly. `refusals` documents the
      codes.

  WHAT THIS CONTRACT NEVER PRODUCES: a valuation, a market score, a ranking
  of jurisdictions / programmes / organizations, an eligibility conclusion
  about any person (the catalog discloses signals, it never adjudicates), or
  any statement about a borrower. Mortgage figures are programme parameters
  as published — observations, not advice and not offers."

  (:require [clojure.string :as str]
            [mortgage.facts :as facts]
            [mortgage.plan :as plan]))

;; --- identity --------------------------------------------------------------

(def contract-version
  "Every receipt, observation, coverage row and claim carries this string."
  "mortgage-observation/1")

;; --- refusals (loud, never silent degradation) ------------------------------

(defn- refuse
  [code message]
  (throw (ex-info message {:refusal/code code
                           :refusal/contract contract-version
                           :refusal/message message})))

(defn refusal-code
  "The `:refusal/code` of an `ex-info` thrown by this contract, or nil."
  [e]
  (:refusal/code (ex-data e)))

(def refusals
  "Every refusal code this contract can raise, with what it means. A caller
  catching an exception without a code here did not come from this contract."
  #{:receipt/missing-field
    :receipt/url-not-https
    :receipt/unknown-source-class
    :receipt/unknown-method
    :receipt/bad-content-hash
    :receipt/bad-observed-at
    :receipt/bad-asserted-at
    :receipt/observed-before-asserted
    :receipt/bad-jurisdiction
    :figure/empty-raw
    :figure/monetary-without-currency
    :figure/bad-currency
    :figure/monetary-without-nominal-at
    :figure/dimensional-without-area-unit
    :figure/unknown-area-unit
    :observation/missing-field
    :observation/unknown-plane
    :observation/unknown-subject
    :observation/bad-window
    :observation/window-inverted
    :observation/no-receipts
    :observation/cross-jurisdiction-receipt
    :observation/unknown-missingness-flag
    :observation/silence-claims-completeness
    :observation/refresh-of-unknown
    :observation/refresh-of-self
    :history/duplicate-observation-id
    :history/not-an-observation
    :coverage/bad-universe
    :readback/tampered-receipt
    :proposal/not-an-observation})

;; --- vocabulary --------------------------------------------------------------

(def receipt-classes
  "The closed source-class vocabulary a receipt may carry. The first block is
  taken verbatim from the workspace real-estate scope's `:source-policy
  :allow`; the last two are catalogue-specific (state programme operators and
  government portals publish most of this catalog) and have NO scope class —
  see `unmapped-in-scope`. Unknown classes are refused, not guessed."
  #{:official-land-registry :official-cadastre :official-statistics-agency
    :official-tax-authority :official-regulator :official-securities-filing
    :official-stock-exchange-filing :official-municipal-planning-authority
    :official-central-bank :fund-first-party :manager-first-party
    :official-programme-operator :official-government-portal})

(def unmapped-in-scope
  "Receipt classes with NO counterpart in the scope's `:source-policy :allow`
  vocabulary. A proposal carrying one of these is flagged
  `:proposal/source-class-unmapped` — surfaced, never silently relabelled
  into a scope class it does not have."
  #{:official-programme-operator :official-government-portal})

(def receipt-methods
  "How the bytes were read. `:verbatim-citation` — page/PDF read and quoted;
   `:official-api` — structured response from the publisher's own endpoint;
   `:archived-receipt` — hash over bytes archived by a previous run."
  #{:verbatim-citation :official-api :archived-receipt})

(def missingness-flags
  "The closed missingness vocabulary an observation may declare. Chosen from
  the gaps the seeded catalog actually publishes; extending it is a contract
  change, not a free-form field."
  #{:rate-or-ceiling-unverified
    :source-not-text-extractable
    :figures-partial
    :related-programme-unverified
    :legal-construction-unverified
    :successor-scheme-parameters-unknown
    :statutory-cite-unverified
    :not-seeded})

(def planes
  "The catalog planes an observation can be about (same three as the catalog
  and the plan)."
  plan/planes)

;; --- shapes ------------------------------------------------------------------

(def ^:private iso-date-re #"\d{4}-\d{2}-\d{2}")
(def ^:private iso-instant-re #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
(def ^:private sha256-re #"sha256:[0-9a-f]{64}")
(def ^:private currency-re #"[A-Z]{3}")
(def ^:private jurisdiction-re #"[A-Z]{3}(-[A-Z0-9]{2,3})?")

(defn- iso-date? [s] (boolean (re-matches iso-date-re (str s))))
(defn- iso-instant? [s] (boolean (re-matches iso-instant-re (str s))))
(defn- valid-jurisdiction? [s] (boolean (re-matches jurisdiction-re (str s))))

(defn- assert-present
  [m prefix required]
  (doseq [k required]
    (when (or (nil? (get m k)) (and (string? (get m k)) (str/blank? (get m k))))
      (refuse (keyword prefix "missing-field")
              (str prefix " is missing required field " k)))))

(defn receipt
  "Validate and freeze one source reading. Returns the receipt with its
  deterministic `:receipt/id` (hash prefix + observation date). A receipt is
  immutable identity for the BYTES observed — the same bytes re-observed on a
  later date are a DIFFERENT receipt, which is how a refresh is seen.

  Refuses: missing fields, non-https URLs, classes/methods outside the
  closed vocabularies, malformed sha256 / dates / jurisdictions, and an
  observation instant earlier than the date the source asserts."
  [{:keys [source-url source-class source-language issuing-entity jurisdiction
           content-hash observed-at asserted-at method]
    :or {method :verbatim-citation} :as r}]
  (assert-present r "receipt" [:source-url :source-class :source-language
                               :issuing-entity :jurisdiction :content-hash
                               :observed-at :asserted-at])
  (when-not (str/starts-with? (str source-url) "https://")
    (refuse :receipt/url-not-https (str "receipt source-url must be https: " source-url)))
  (when-not (contains? receipt-classes source-class)
    (refuse :receipt/unknown-source-class (str "unknown receipt source-class: " source-class)))
  (when-not (contains? receipt-methods method)
    (refuse :receipt/unknown-method (str "unknown receipt method: " method)))
  (when-not (re-matches sha256-re (str content-hash))
    (refuse :receipt/bad-content-hash
            (str "content-hash must be \"sha256:\" + 64 lowercase hex chars: " content-hash)))
  (when-not (iso-instant? observed-at)
    (refuse :receipt/bad-observed-at
            (str "observed-at must be an ISO-8601 instant with Z suffix: " observed-at)))
  (when-not (iso-date? asserted-at)
    (refuse :receipt/bad-asserted-at
            (str "asserted-at must be an ISO date (the source's own assertion date): " asserted-at)))
  (let [observed-date (subs (str observed-at) 0 10)
        asserted-date (str asserted-at)]
    (when (neg? (compare observed-date asserted-date))
      ;; you cannot read an assertion before the source made it
      (refuse :receipt/observed-before-asserted
              (str "observed-at " observed-at " precedes asserted-at " asserted-at))))
  (when-not (valid-jurisdiction? jurisdiction)
    (refuse :receipt/bad-jurisdiction
            (str "jurisdiction must be iso3 or iso3-subnational: " jurisdiction)))
  (assoc r :method method
           :receipt/id (str "rcpt-" (subs (subs (str content-hash) 7) 0 12)
                            "-" (subs (str observed-at) 0 10))))

(defn receipt-valid?
  "True when `receipt` accepts `r` without refusing."
  [r]
  (try (receipt r) true (catch :default _ false)))

(defn- validate-figure
  [{:figure/keys [field raw monetary? currency nominal-at area-value area-unit]
    :as f}]
  (when (or (str/blank? (str field)) (str/blank? (str raw)))
    (refuse :figure/empty-raw
            (str "figure needs a non-empty :figure/field and verbatim :figure/raw: " (pr-str f))))
  (when monetary?
    (cond
      (nil? currency)
      (refuse :figure/monetary-without-currency
              (str "monetary figure \"" field "\" must carry an ISO-4217-shaped currency"))
      (not (re-matches currency-re (str currency)))
      (refuse :figure/bad-currency
              (str "currency must be a 3-uppercase-letter ISO-4217 code: " currency))
      :else nil)
    (when-not (iso-date? nominal-at)
      (refuse :figure/monetary-without-nominal-at
              (str "monetary figure \"" field "\" must carry the date its amount is nominal at"))))
  (when (and (seq (str area-value)) (str/blank? (str area-unit)))
    (refuse :figure/dimensional-without-area-unit
            (str "dimensional figure \"" field "\" must carry its measurement unit")))
  (when (and (seq (str area-unit))
             (not (re-matches #"[a-z0-9]{1,8}" (str area-unit))))
    (refuse :figure/unknown-area-unit
            (str "area-unit must be a short lowercase unit token (m2, sqft, tsubo, ...): " area-unit)))
  f)

(defn- subject-exists?
  "The typed subject must EXIST in the catalog — no phantom entities, no
  observations about a programme or organization this registry does not carry."
  [{:keys [jurisdiction plane subject-id]}]
  (let [entry (facts/spec-basis jurisdiction)]
    (boolean
     (case plane
       :procedure (and entry (:procedure entry) (= "procedure" (str subject-id)))
       :support (some #(= (str subject-id) (str (:id %))) (:support entry))
       :organizations (some #(= (str subject-id) (str (:id %))) (:organizations entry))
       nil))))

(defn observation
  "Validate one typed observation over the catalog and freeze it with a
  deterministic `:obs/id`. Refuses phantom subjects, inverted windows,
  zero receipts, receipts from another jurisdiction (entity separation),
  missingness flags outside the closed vocabulary, and — the honesty rule —
  an observation that declares NO gaps where the catalog's own
  `:verification :not-verified` block publishes some."
  [{:obs/keys [subject window receipts figures missingness refresh-of]
    :or {figures [] missingness {:flags [] :not-verified []}}
    :as o}]
  (assert-present o "observation" [:obs/subject :obs/window :obs/receipts])
  (let [{:keys [jurisdiction plane subject-id]} subject
        {:keys [from to]} window]
    (when-not (contains? (set planes) plane)
      (refuse :observation/unknown-plane (str "unknown plane: " plane)))
    (when-not (subject-exists? subject)
      (refuse :observation/unknown-subject
              (str "no " plane " subject \"" subject-id "\" in " jurisdiction
                   " — the catalog is the entity authority, no phantom observations")))
    (when-not (and (iso-date? from) (iso-date? to))
      (refuse :observation/bad-window (str "window :from/:to must be ISO dates: " (pr-str window))))
    (when (pos? (compare from to))
      (refuse :observation/window-inverted (str "window from " from " > to " to)))
    (when-not (seq receipts)
      (refuse :observation/no-receipts "an observation without a receipt is a rumor"))
    (doseq [r receipts]
      (receipt r)
      (when-not (= (str (:jurisdiction r)) (str jurisdiction))
        (refuse :observation/cross-jurisdiction-receipt
                (str "receipt jurisdiction " (:jurisdiction r)
                     " cannot evidence an observation about " jurisdiction))))
    (let [validated-figures (mapv validate-figure figures)
          {:keys [flags not-verified]} missingness
          unknown (remove #(contains? missingness-flags %) flags)]
      (when (seq unknown)
        (refuse :observation/unknown-missingness-flag
                (str "missingness flags outside the closed vocabulary: " (pr-str (vec unknown)))))
      (let [catalog-gaps (vec (get-in facts/catalog [jurisdiction :verification :not-verified]))
            declared-not-verified (vec not-verified)]
        (when (and (empty? flags) (empty? declared-not-verified) (seq catalog-gaps))
          (refuse :observation/silence-claims-completeness
                  (str jurisdiction " publishes " (count catalog-gaps)
                       " not-verified gap(s); an observation that declares none would claim "
                       "completeness the catalog itself refuses — carry them or narrow them")))
        (assoc o
               :obs/method contract-version
               :obs/id (str "obs-" jurisdiction "-" (name plane) "-" subject-id "-" to)
               :obs/figures validated-figures
               :obs/missingness {:flags (vec flags)
                                 :not-verified declared-not-verified
                                 :catalog/not-verified catalog-gaps})))))

;; --- derived observations (counts, never market metrics) ---------------------

(defn coverage-observation
  "One derived observation over the whole catalog against `universe` (the
  generated jurisdiction list from `data/jurisdiction-universe.edn`, passed in
  by the caller — this namespace does not read generated data files): per-plane
  absent/located/verbatim frequencies and the catalog's own gap count. This is
  a COVERAGE count with the window attached. It is not a market metric, not a
  valuation, not a score, and not a completeness claim."
  [universe window]
  (when-not (and (vector? universe) (seq universe) (every? valid-jurisdiction? universe))
    (refuse :coverage/bad-universe "universe must be a non-empty vector of jurisdiction codes"))
  (let [{:keys [from to]} window]
    (when-not (and (iso-date? from) (iso-date? to))
      (refuse :observation/bad-window (str "window :from/:to must be ISO dates: " (pr-str window))))
    (when (pos? (compare from to))
      (refuse :observation/window-inverted (str "window from " from " > to " to))))
  {:obs/type :coverage
   :obs/id (str "cov-" (:to window))
   :obs/window window
   :obs/method contract-version
   :cov/universe (count universe)
   :cov/jurisdictions-seeded (count facts/catalog)
   :cov/planes (into (sorted-map)
                     (for [p planes]
                       [p (frequencies (map #(plan/plane-status % p) universe))]))
   :cov/published-gaps (count (facts/unverified-claims))
   :cov/non-goals ["coverage count — not a market metric, not a valuation, not a score"
                   "not a ranking of jurisdictions, programmes or organizations"
                   "not a completeness claim: 'worldwide' is a coverage goal"]})

(defn jurisdiction-observation
  "One derived observation per jurisdiction: the plan's per-plane status plus
  the entry's own published `:not-verified` gaps, under the window. Counts and
  statuses only — nothing here is a market judgement."
  [iso3 window]
  (when-not (facts/spec-basis iso3)
    (refuse :observation/unknown-subject (str "jurisdiction not seeded: " iso3)))
  (let [{:keys [from to]} window]
    (when-not (and (iso-date? from) (iso-date? to))
      (refuse :observation/bad-window (str "window :from/:to must be ISO dates: " (pr-str window))))
    (when (pos? (compare from to))
      (refuse :observation/window-inverted (str "window from " from " > to " to)))
    {:obs/type :jurisdiction
     :obs/id (str "jur-" iso3 "-" to)
     :obs/window window
     :obs/method contract-version
     :jur/iso3 iso3
     :jur/planes (into (sorted-map) (for [p planes] [p (plan/plane-status iso3 p)]))
     :jur/published-gaps (mapv :unverified
                               (filter #(= iso3 (:jurisdiction %)) (facts/unverified-claims)))
     :jur/non-goals ["statuses and published gaps — not a valuation, score or ranking"]}))

;; --- refresh history (pure; data, not a side-effecting ledger) ---------------

(defn refresh
  "Append `obs` to `history` (a vector of observations) and return the new
  history. Pure — the caller owns persistence. Refuses the same observation
  id twice, a `:obs/refresh-of` pointing at an id not in the history, and a
  refresh pointing at itself."
  [history obs]
  (when-not (and (contains? obs :obs/id) (contains? obs :obs/method))
    (refuse :history/not-an-observation
            "refresh takes a frozen observation from `observation`, not a raw map"))
  (let [id (:obs/id obs)]
    (when (some #(= (:obs/id %) id) history)
      (refuse :history/duplicate-observation-id
              (str "observation " id " is already recorded — a re-observation gets a new window and id")))
    (when-let [ro (:obs/refresh-of obs)]
      (when (= ro id)
        (refuse :observation/refresh-of-self "an observation cannot refresh itself"))
      (when-not (some #(= (:obs/id %) ro) history)
        (refuse :observation/refresh-of-unknown
                (str ":obs/refresh-of " ro " is not in the history"))))
    (conj history obs)))

(defn- revalidate!
  "Re-run receipt validation over every receipt an observation carries —
  readback refuses to return observations whose receipts no longer validate
  (tamper check)."
  [obs]
  (doseq [r (:obs/receipts obs)]
    (when-not (receipt-valid? r)
      (refuse :readback/tampered-receipt
              (str "receipt for " (:receipt/id r) " no longer validates — refusing readback")))
    (doseq [f (:obs/figures obs)]
      (validate-figure f))))

(defn readback
  "Query shape: the LATEST observation for a subject whose window closes at or
  before `as-of`, with every receipt re-validated on the way out. A subject
  with no such observation returns a `:readback/miss` report — never a
  default, never a neighbouring subject's data (entity separation)."
  [history {:keys [jurisdiction plane subject-id as-of]}]
  (when-not (iso-date? as-of)
    (refuse :observation/bad-window (str "as-of must be an ISO date: " as-of)))
  (let [hits (->> history
                  (filter #(let [s (:obs/subject %)]
                             (and (= (str (:jurisdiction s)) (str jurisdiction))
                                  (= (name (:plane s)) (name plane))
                                  (= (str (:subject-id s)) (str subject-id))
                                  (not (pos? (compare (:to (:obs/window %)) as-of))))))
                  (sort-by #(get-in % [:obs/window :to]))
                  vec)
        latest (peek hits)]
    (if latest
      (do (revalidate! latest) latest)
      {:readback/miss true
       :readback/subject {:jurisdiction jurisdiction :plane plane :subject-id subject-id}
       :readback/as-of as-of
       :readback/note "no observation at or before this date — missing is unmeasured"})))

(defn readback-coverage
  "Latest coverage observation at or before `as-of`, or a miss report."
  [history as-of]
  (when-not (iso-date? as-of)
    (refuse :observation/bad-window (str "as-of must be an ISO date: " as-of)))
  (let [latest (last (sort-by #(get-in % [:obs/window :to])
                              (filter #(= :coverage (:obs/type %))
                                      (filter #(not (pos? (compare (get-in % [:obs/window :to]) as-of)))
                                              history))))]
    (or latest
        {:readback/miss true :readback/as-of as-of
         :readback/note "no coverage observation at or before this date"})))

;; --- Hyakka proposal (shape only; nothing is sent by this contract) ----------

(def ^:private claim-props
  "Contract-local prop vocabulary for the fudosan corpus proposals. These are
  NOT registered props in the Hyakka ontology — minting them there is an
  ontology change this contract does not make; every claim carries
  `:proposal/ontology-registration-pending true` so a proposing run cannot
  quietly present them as already-registered props."
  {:procedure "prop/mortgage-procedure-observed"
   :support "prop/mortgage-support-programme-observed"
   :support-figure "prop/mortgage-support-programme-figure"
   :organizations "prop/mortgage-organization-observed"})

(defn- claim-entity
  [{:keys [jurisdiction plane subject-id]}]
  (case plane
    :procedure (str "world/mortgage-procedure/" jurisdiction)
    :support (str "world/mortgage-support/" subject-id)
    :organizations (str "world/mortgage-organization/" subject-id)
    (str "world/mortgage-unknown/" jurisdiction)))

(defn hyakka-proposal
  "The exact claim shape a proposing run would carry to the `fudosan` corpus:
  one claim per verbatim figure plus one subject-level claim, each with its
  receipt, its currency/area basis, its missingness, and the no-model /
  deterministic-extractor qualifiers. The proposal is DATA — this contract
  performs no transmission, no outreach and no publishing.

  Refuses anything that is not a frozen observation (`:obs/id` + method
  present), so a half-built map can never masquerade as a proposal."
  [obs]
  (when-not (and (contains? obs :obs/id) (contains? obs :obs/method))
    (refuse :proposal/not-an-observation
            "hyakka-proposal takes a frozen observation from `observation`, not a raw map"))
  (let [subject (:obs/subject obs)
        window (:obs/window obs)
        receipts (:obs/receipts obs)
        {:keys [flags]} (:obs/missingness obs)
        declared-gaps (vec (:not-verified (:obs/missingness obs)))
        catalog-gaps (vec (:catalog/not-verified (:obs/missingness obs)))
        all-gaps (vec (distinct (concat declared-gaps catalog-gaps)))
        basis (some-> receipts peek
                      (select-keys [:source-url :content-hash :observed-at :asserted-at
                                    :source-class :method]))
        unmapped (contains? unmapped-in-scope (:source-class basis))
        common {:claim/corpus "fudosan"
                :claim/as-of (:to window)
                :claim/entity (claim-entity subject)
                :claim/receipt basis
                :claim/receipts (mapv #(select-keys % [:source-url :content-hash :observed-at
                                                       :asserted-at :source-class :method])
                                      receipts)
                :claim/qualifiers
                (cond-> {:deterministic-extractor contract-version
                         :no-model true
                         :non-goals ["observation — not advice, valuation, score or ranking"
                                     "programme parameters as published — not an offer"
                                     "eligibility signals as published — no person is adjudicated"]}
                  (seq flags) (assoc :missing/flags (vec flags))
                  (seq all-gaps) (assoc :missing/not-verified all-gaps)
                  unmapped (assoc :proposal/source-class-unmapped true)
                  :always (assoc :proposal/ontology-registration-pending true))}
        figure-claims
        (for [f (:obs/figures obs)]
          (merge common
                 {:claim/id (str "fudosan/mortgage-registry/"
                                 (:jurisdiction subject) "/" (name (:plane subject)) "/"
                                 (:subject-id subject) "/" (:figure/field f) "/" (:to window))
                  :claim/prop (get claim-props
                                   (if (= :support (:plane subject))
                                     :support-figure (:plane subject)))
                  :claim/value (:figure/raw f)}
                 (when (:figure/monetary? f)
                   {:claim/currency (:figure/currency f)
                    :claim/nominal-at (:figure/nominal-at f)
                    :claim/basis-note "nominal at own date — not comparable across dates or currencies without a stated basis"})
                 (when (seq (str (:figure/area-value f)))
                   {:claim/area-value (:figure/area-value f)
                    :claim/area-unit (:figure/area-unit f)
                    :claim/area-basis-note "area under its own measurement standard — not interchangeable"})))
        subject-claim
        [(merge common
                {:claim/id (str "fudosan/mortgage-registry/"
                                (:jurisdiction subject) "/" (name (:plane subject)) "/"
                                (:subject-id subject) "/observed/" (:to window))
                 :claim/prop (get claim-props (:plane subject))
                 :claim/value (str "observed via " contract-version " — see receipt")})]]
    (into subject-claim (vec figure-claims))))
