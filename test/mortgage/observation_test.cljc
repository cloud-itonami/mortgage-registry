(ns mortgage.observation-test
  "Deterministic fixtures for the observation contract. No network: the live
  receipt was recorded once into `test/fixtures/observation/` and every run
  replays it. The six required dimensions are covered: temporal refresh,
  entity separation, currency/unit basis, provenance, missingness, and
  query/readback. These tests do NOT assert that any programme figure is
  correct — no test can; only a fresh fetch against the official source can."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["fs" :as fs]
            [mortgage.facts :as facts]
            [mortgage.observation :as obs]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(def ^:private fixture-receipt
  (edn/read-string (fs/readFileSync "test/fixtures/observation/receipt-nhg-grens-2026.edn" "utf8")))

(defn- varied-receipt
  "A second-generation reading of the SAME page: different observed-at and a
  different content-hash (the source moved between reads) — a NEW receipt, as
  the contract defines a refresh to be. Re-frozen from the RAW fixture so the
  derived :receipt/id matches the new hash."
  []
  (obs/receipt (assoc fixture-receipt
                      :observed-at "2026-09-01T12:00:00Z"
                      :content-hash "sha256:4444444444444444444444444444444444444444444444444444444444444444")))

(defn- refusal-of
  "Run `f`; return the refusal code it raised, or :no-refusal when it returned
  normally, or :no-refusal-code when it threw something this contract did not
  raise (which is itself a failure — refusals must be loud and coded)."
  [f]
  (try (f) :no-refusal
       (catch :default e
         (or (obs/refusal-code e) :no-refusal-code))))

(def ^:private nhg-receipt
  (obs/receipt fixture-receipt))

(defn- nhg-observation
  "The frozen NLD/NHG observation built on the live receipt fixture."
  [to & {:keys [refresh-of]}]
  (obs/observation
   {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
    :obs/window {:from "2026-08-01" :to to}
    :obs/receipts [nhg-receipt]
    :obs/figures
    [{:figure/field "guarantee-limit-2026"
      :figure/raw "De NHG-grens per 1 januari 2026 is € 470.000"
      :figure/monetary? true :figure/currency "EUR" :figure/nominal-at "2026-01-01"}
     {:figure/field "guarantee-limit-2026-with-energy-measures"
      :figure/raw "Bij meefinanciering van energiebesparende voorzieningen is de grens € 498.200"
      :figure/monetary? true :figure/currency "EUR" :figure/nominal-at "2026-01-01"}]
    :obs/missingness
    {:flags [:legal-construction-unverified]
     :not-verified ["waarborgfondsconstructie / achtervang detail"]}
    :obs/refresh-of refresh-of}))

;; --- 0. contract identity ----------------------------------------------------

(deftest contract-identity-is-named
  (is (= "mortgage-observation/4" obs/contract-version))
  (is (seq obs/refusals) "the refusal codes are documented, not incidental")
  (is (contains? obs/receipt-classes (:source-class nhg-receipt)))
  (is (contains? obs/unmapped-in-scope (:source-class nhg-receipt))
      "the fixture's programme-operator class IS one of the scope-unmapped classes"))

;; --- 1. provenance (receipts) -------------------------------------------------

(deftest live-receipt-fixture-validates-and-agrees-with-the-catalog
  (is (str/starts-with? (:source-url nhg-receipt) "https://"))
  (is (re-matches #"sha256:[0-9a-f]{64}" (:content-hash nhg-receipt)))
  (is (= "rcpt-0c728b7741b7-2026-09-01" (:receipt/id nhg-receipt))
      "the receipt id derives from the hash prefix + observation date")
  (is (= (->> (get-in facts/catalog ["NLD" :support])
              (filter #(= "nld.nhg" (:id %)))
              first
              :provenance)
         (:source-url nhg-receipt))
      "the receipt must point at the exact URL the catalog entry cites"))

(deftest receipts-refuse-unprovenanced-input
  (let [base {:source-url "https://example.gov/x" :source-class :official-tax-authority
              :source-language "ja" :issuing-entity "国税庁" :jurisdiction "JPN"
              :content-hash "sha256:0000000000000000000000000000000000000000000000000000000000000000"
              :observed-at "2026-09-01T00:00:00Z" :asserted-at "2026-08-01"}]
    (doseq [[code m]
            [[:receipt/missing-field (dissoc base :content-hash)]
             [:receipt/url-not-https (assoc base :source-url "http://example.gov/x")]
             [:receipt/unknown-source-class (assoc base :source-class :someone-blog)]
             [:receipt/unknown-method (assoc base :method :vibes)]
             [:receipt/bad-content-hash (assoc base :content-hash "sha256:abc")]
             [:receipt/bad-observed-at (assoc base :observed-at "2026-09-01 00:00")]
             [:receipt/observed-before-asserted (assoc base :observed-at "2026-07-01T00:00:00Z"
                                                       :asserted-at "2026-08-01")]]]
      (testing (str code)
        (is (= code (refusal-of #(obs/receipt m))))))))

(deftest receipt-method-and-class-vocabularies-are-closed
  (is (contains? obs/receipt-methods :verbatim-citation))
  (is (every? obs/receipt-classes
              #{:official-land-registry :official-tax-authority :official-regulator})
      "the scope's allow classes are carried into the receipt vocabulary"))

;; --- 2. entity separation ------------------------------------------------------

(deftest observations-refuse-phantom-subjects
  (is (= :observation/unknown-subject
         (refusal-of
          #(obs/observation
            {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.starterslening"}
             :obs/window {:from "2026-08-01" :to "2026-09-01"}
             :obs/receipts [nhg-receipt]})))
      "SVn starterslening is a published GAP of the catalog — observing it as if seeded is fabrication"))

(deftest receipts-cannot-evidence-another-jurisdictions-observation
  (is (= :observation/cross-jurisdiction-receipt
         (refusal-of
          #(obs/observation
            {:obs/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
             :obs/window {:from "2026-08-01" :to "2026-09-01"}
             :obs/receipts [nhg-receipt]})))))

(deftest readback-miss-reports-miss-never-neighbours
  (let [history [(nhg-observation "2026-09-01")]
        miss (obs/readback history {:jurisdiction "JPN" :plane :support
                                    :subject-id "jpn.flat35" :as-of "2026-09-01"})]
    (is (:readback/miss miss))
    (is (= "JPN" (get-in miss [:readback/subject :jurisdiction])))
    (is (nil? (:obs/id miss)) "a miss carries no observation body to leak")))

;; --- 3. measurement window + temporal refresh ----------------------------------

(deftest windows-are-required-and-ordered
  (let [base {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
              :obs/receipts [nhg-receipt]}]
    (is (= :observation/bad-window
           (refusal-of #(obs/observation (assoc base :obs/window {:from "01-08-2026" :to "2026-09-01"})))))
    (is (= :observation/window-inverted
           (refusal-of #(obs/observation (assoc base :obs/window {:from "2026-09-01" :to "2026-08-01"})))))
    (is (= :observation/missing-field
           (refusal-of #(obs/observation (dissoc base :obs/window)))))))

(deftest temporal-refresh-two-windows-one-subject
  (let [first-obs (nhg-observation "2026-08-01")
        re-obs (nhg-observation "2026-09-01" :refresh-of (:obs/id first-obs))
        history (-> [] (obs/refresh first-obs) (obs/refresh re-obs))]
    (is (= "obs-NLD-support-nld.nhg-2026-08-01" (:obs/id first-obs)))
    (is (= (:obs/id first-obs) (:obs/refresh-of re-obs))
        "a refresh links to what it refreshes")
    (is (= 2 (count history)) "append-only: both windows remain in the history")
    (is (= (:obs/id first-obs)
           (:obs/id (obs/readback history {:jurisdiction "NLD" :plane :support
                                           :subject-id "nld.nhg" :as-of "2026-08-15"})))
        "as-of inside the first window reads the first observation")
    (is (= (:obs/id re-obs)
           (:obs/id (obs/readback history {:jurisdiction "NLD" :plane :support
                                           :subject-id "nld.nhg" :as-of "2026-09-01"})))
        "as-of at the refresh reads the latest observation")
    (is (= :observation/refresh-of-unknown
           (refusal-of #(obs/refresh [] (nhg-observation "2026-09-01" :refresh-of "obs-nowhere")))))
    (is (= :history/duplicate-observation-id
           (refusal-of #(obs/refresh history re-obs)))
        (str "the same observation id cannot be recorded twice; a re-observation "
             "gets a new window and id"))
    (is (= :observation/refresh-of-self
           (refusal-of #(obs/refresh [] (nhg-observation "2026-09-01"
                                                         :refresh-of "obs-NLD-support-nld.nhg-2026-09-01")))))))

(deftest readback-refuses-tampered-history
  (let [obs (nhg-observation "2026-09-01")
        tampered (update-in obs [:obs/receipts 0 :content-hash] (constantly "sha256:deadbeef"))]
    (is (= :readback/tampered-receipt
           (refusal-of #(obs/readback [tampered] {:jurisdiction "NLD" :plane :support
                                                  :subject-id "nld.nhg" :as-of "2026-09-01"})))
        "a history entry whose receipt no longer validates is refused, never returned")))

;; --- 4. currency / area basis ----------------------------------------------------

(deftest flat35-figure-carries-its-own-basis
  (let [receipt (obs/receipt
                 {:source-url "https://www.flat35.com/loan/lineup/flat35/conditions/index.html"
                  :source-class :official-programme-operator
                  :source-language "ja" :issuing-entity "住宅金融支援機構 (JHF)"
                  :jurisdiction "JPN"
                  :content-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                  :observed-at "2026-09-01T05:10:00Z" :asserted-at "2026-08-01"})
        o (obs/observation
           {:obs/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
            :obs/window {:from "2026-08-01" :to "2026-09-01"}
            :obs/receipts [receipt]
            :obs/figures
            [{:figure/field "loan-amount-range"
              :figure/raw "100万円以上1億2,000万円以下（1万円単位）で、建設費または購入価額以内"
              :figure/monetary? true :figure/currency "JPY" :figure/nominal-at "2026-08-01"}]
            :obs/missingness
            {:flags [:rate-or-ceiling-unverified]
             :not-verified ["current-year 住宅ローン控除 rate/ceiling"]}})]
    (is (= "JPY" (get-in o [:obs/figures 0 :figure/currency])))
    (is (= "2026-08-01" (get-in o [:obs/figures 0 :figure/nominal-at])))
    (let [claims (obs/hyakka-proposal o)
          fig (first (filter #(str/includes? (:claim/id %) "loan-amount-range") claims))]
      (is (= "JPY" (:claim/currency fig)))
      (is (= "2026-08-01" (:claim/nominal-at fig)))
      (is (str/includes? (:claim/value fig) "1億2,000万円")
          "the claim carries the VERBATIM source text")
      (is (not-any? #(contains? % :claim/amount-normalized) claims)
          "no normalized numeric amount exists anywhere in the proposal — amounts at different dates/currencies are not made comparable here"))))

(deftest monetary-and-dimensional-figures-refuse-missing-basis
  (let [base {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
              :obs/window {:from "2026-08-01" :to "2026-09-01"}
              :obs/receipts [nhg-receipt]}]
    (is (= :figure/monetary-without-currency
           (refusal-of
            #(obs/observation
              (assoc base :obs/figures [{:figure/field "x" :figure/raw "€ 470.000"
                                         :figure/monetary? true}]))))
        "a monetary figure without a currency is refused, not guessed")
    (is (= :figure/bad-currency
           (refusal-of
            #(obs/observation
              (assoc base :obs/figures [{:figure/field "x" :figure/raw "€ 470.000"
                                         :figure/monetary? true :figure/currency "euro"
                                         :figure/nominal-at "2026-01-01"}])))))
    (is (= :figure/monetary-without-nominal-at
           (refusal-of
            #(obs/observation
              (assoc base :obs/figures [{:figure/field "x" :figure/raw "€ 470.000"
                                         :figure/monetary? true :figure/currency "EUR"}])))))
    (is (= :figure/dimensional-without-area-unit
           (refusal-of
            #(obs/observation
              (assoc base :obs/figures [{:figure/field "floor-area" :figure/raw "50平方メートル"
                                         :figure/area-value "50"}]))))
        "a dimensional figure without its area unit is refused")))

(deftest dimensional-figure-carries-its-measurement-standard
  (let [receipt (obs/receipt
                 {:source-url "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1213.htm"
                  :source-class :official-tax-authority
                  :source-language "ja" :issuing-entity "国税庁 (National Tax Agency)"
                  :jurisdiction "JPN"
                  :content-hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"
                  :observed-at "2026-09-01T05:12:00Z" :asserted-at "2026-08-01"})
        o (obs/observation
           {:obs/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.housing-loan-tax-deduction"}
            :obs/window {:from "2026-08-01" :to "2026-09-01"}
            :obs/receipts [receipt]
            :obs/figures
            [{:figure/field "floor-area-minimum"
              :figure/raw "床面積が50平方メートル以上"
              :figure/area-value "50" :figure/area-unit "m2"}]
            :obs/missingness
            {:flags [:rate-or-ceiling-unverified]
             :not-verified ["current-year 住宅ローン控除 rate/ceiling"]}})]
    (is (= "m2" (get-in o [:obs/figures 0 :figure/area-unit])))
    (let [claim (first (filter #(str/includes? (:claim/id %) "floor-area-minimum")
                               (obs/hyakka-proposal o)))]
      (is (= "50" (:claim/area-value claim)))
      (is (= "m2" (:claim/area-unit claim)))
      (is (str/includes? (:claim/area-basis-note claim) "not interchangeable")
          "the scope boundary rides on the claim: area standards are not interchangeable"))))

;; --- 5. missingness ---------------------------------------------------------------

(deftest missingness-vocabulary-is-closed
  (is (= :observation/unknown-missingness-flag
         (refusal-of
          #(obs/observation
            {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
             :obs/window {:from "2026-08-01" :to "2026-09-01"}
             :obs/receipts [nhg-receipt]
             :obs/missingness {:flags [:looks-fine-i-guess] :not-verified []}})))))

(deftest silence-never-claims-completeness
  (is (= :observation/silence-claims-completeness
         (refusal-of
          #(obs/observation
            {:obs/subject {:jurisdiction "FRA" :plane :procedure :subject-id "procedure"}
             :obs/window {:from "2026-08-01" :to "2026-09-01"}
             :obs/receipts [(obs/receipt
                             {:source-url "https://www.service-public.gouv.fr/particuliers/vosdroits/F10871"
                              :source-class :official-government-portal
                              :source-language "fr" :issuing-entity "Service-Public.fr / État"
                              :jurisdiction "FRA"
                              :content-hash "sha256:3333333333333333333333333333333333333333333333333333333333333333"
                              :observed-at "2026-09-01T05:15:00Z" :asserted-at "2026-08-01"})]})))
      "FRA publishes not-verified gaps; an observation declaring none is refused")
  ;; declaring the catalog's own gaps passes — the honest path exists
  (is (some? (obs/observation
              {:obs/subject {:jurisdiction "FRA" :plane :procedure :subject-id "procedure"}
               :obs/window {:from "2026-08-01" :to "2026-09-01"}
               :obs/receipts [(obs/receipt
                               {:source-url "https://www.service-public.gouv.fr/particuliers/vosdroits/F10871"
                                :source-class :official-government-portal
                                :source-language "fr" :issuing-entity "Service-Public.fr / État"
                                :jurisdiction "FRA"
                                :content-hash "sha256:3333333333333333333333333333333333333333333333333333333333333333"
                                :observed-at "2026-09-01T05:15:00Z" :asserted-at "2026-08-01"})]
               :obs/missingness {:flags [:source-not-text-extractable]
                                 :not-verified ["Code de la construction et de l'habitation article citation for the PTZ"]}}))
      "carrying the catalog's published gaps is the honest path and is accepted"))

(deftest catalog-gaps-ride-into-proposal-qualifiers
  (let [o (nhg-observation "2026-09-01")
        claims (obs/hyakka-proposal o)]
    (is (seq claims))
    (is (every? #(contains? (:claim/qualifiers %) :missing/not-verified) claims)
        "every claim carries the not-verified gaps — none may drop them")
    (is (every? #(contains? (:claim/qualifiers %) :no-model) claims)
        "every claim carries :no-model — there is no model on this path")
    (is (every? #(= "mortgage-observation/4" (:deterministic-extractor (:claim/qualifiers %))) claims)
        "method/version rides on every claim")))

;; --- 6. derived coverage observation ----------------------------------------------

(def ^:private small-universe
  ["JPN" "USA" "GBR" "DEU" "FRA" "NLD" "BRA" "IDN"])

(deftest coverage-observation-counts-honestly
  (let [cov (obs/coverage-observation small-universe {:from "2026-08-01" :to "2026-09-01"})]
    (is (= 8 (:cov/universe cov)))
    (is (= 6 (:cov/jurisdictions-seeded cov)))
    (is (= {:absent 2 :located 6} (:organizations (:cov/planes cov)))
        "counts against the whole universe passed in, not just the seeded subset")
    (is (seq (:cov/non-goals cov)) "the non-goals statement rides on the observation")
    (is (= "cov-2026-09-01" (:obs/id cov)) "deterministic id from the window"))
  (is (= :coverage/bad-universe
         (refusal-of #(obs/coverage-observation [] {:from "2026-08-01" :to "2026-09-01"}))))
  (is (= :coverage/bad-universe
         (refusal-of #(obs/coverage-observation ["JPN" "not-a-code"]
                                                 {:from "2026-08-01" :to "2026-09-01"})))))

(deftest jurisdiction-observation-publishes-its-own-gaps
  (let [j (obs/jurisdiction-observation "NLD" {:from "2026-08-01" :to "2026-09-01"})]
    (is (= :located (:organizations (:jur/planes j))))
    (is (= :verbatim (:procedure (:jur/planes j))))
    (is (seq (:jur/published-gaps j)) "the jurisdiction's own gaps are carried")
    (is (str/includes? (str (:jur/non-goals j)) "not a valuation, score or ranking")))
  (is (= :observation/unknown-subject
         (refusal-of #(obs/jurisdiction-observation "BRA" {:from "2026-08-01" :to "2026-09-01"})))
      "an unseeded jurisdiction cannot be observed as if it were covered"))

;; --- 7. Hyakka proposal shape -------------------------------------------------------

(deftest proposal-is-data-with-the-receipt-attached
  (let [o (nhg-observation "2026-09-01")
        claims (obs/hyakka-proposal o)]
    (is (= 3 (count claims)) "one subject-level claim + one per figure")
    (is (every? #(= "fudosan" (:claim/corpus %)) claims))
    (is (every? #(= "2026-09-01" (:claim/as-of %)) claims))
    (is (every? #(= (:source-url nhg-receipt) (get-in % [:claim/receipt :source-url])) claims)
        "every claim points at the same receipt")
    (is (= ["world/mortgage-support/nld.nhg"]
           (distinct (map :claim/entity claims))))
    (is (some #(str/includes? (:claim/id %) "guarantee-limit-2026") claims)
        "figure claims are addressable by id")))

(deftest proposal-flags-unmapped-source-class-and-pending-ontology
  (let [claims (obs/hyakka-proposal (nhg-observation "2026-09-01"))]
    (is (every? (comp true? :proposal/source-class-unmapped :claim/qualifiers) claims)
        ":official-programme-operator has NO scope class — surfaced, never relabelled")
    (is (every? (comp true? :proposal/ontology-registration-pending :claim/qualifiers) claims)
        "the props are contract-local and NOT registered in the Hyakka ontology — said so on every claim"))
  (let [tax-receipt (obs/receipt
                     {:source-url "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1213.htm"
                      :source-class :official-tax-authority
                      :source-language "ja" :issuing-entity "国税庁"
                      :jurisdiction "JPN"
                      :content-hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"
                      :observed-at "2026-09-01T05:12:00Z" :asserted-at "2026-08-01"})
        o (obs/observation
           {:obs/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.housing-loan-tax-deduction"}
            :obs/window {:from "2026-08-01" :to "2026-09-01"}
            :obs/receipts [tax-receipt]
            :obs/missingness {:flags [:rate-or-ceiling-unverified]
                              :not-verified ["current-year 住宅ローン控除 rate/ceiling"]}})
        claims (obs/hyakka-proposal o)]
    (is (not-any? #(contains? (:claim/qualifiers %) :proposal/source-class-unmapped) claims)
        "a scope-mapped class is proposed without the unmapped flag")))

(deftest proposal-refuses-anything-that-is-not-a-frozen-observation
  (is (= :proposal/not-an-observation
         (refusal-of #(obs/hyakka-proposal {:obs/subject {:jurisdiction "NLD"}}))))
  (is (= :proposal/not-an-observation
         (refusal-of #(obs/hyakka-proposal {:obs/id "obs-x"})))
      "an id without the frozen method marker is not enough"))

;; --- 8. readback of the coverage plane ----------------------------------------------

(deftest coverage-readback-picks-latest-at-or-before
  (let [h (-> []
              (obs/refresh (obs/coverage-observation small-universe {:from "2026-08-01" :to "2026-08-01"}))
              (obs/refresh (obs/coverage-observation small-universe {:from "2026-08-02" :to "2026-09-01"})))]
    (is (= "cov-2026-08-01"
           (:obs/id (obs/readback-coverage h "2026-08-15"))))
    (is (= "cov-2026-09-01"
           (:obs/id (obs/readback-coverage h "2026-09-01"))))
    (is (:readback/miss (obs/readback-coverage h "2026-07-31"))
        "before the first coverage row there is only a miss")))

;; --- 9. auditable refresh (v2): refresh-delta + readback-chain ----------------

(defn- nhg-observation-varied
  "A second-generation NLD/NHG observation whose fixture text models what a
  refresh LOOKS like on the wire: the 2026 figure moved (verbatim new text),
  the energy-measures figure is unchanged, and a flag was added. The values
  are FIXTURE TEXT — this test asserts nothing about the real programme."
  [to & {:keys [refresh-of moved? drop-energy?]}]
  (obs/observation
   {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
    :obs/window {:from "2026-08-01" :to to}
    :obs/receipts [(varied-receipt)]
    :obs/figures
    (vec (concat
          [{:figure/field "guarantee-limit-2026"
            :figure/raw (if moved?
                          "De NHG-grens per 1 januari 2027 is € 450.000"
                          "De NHG-grens per 1 januari 2026 is € 470.000")
            :figure/monetary? true :figure/currency "EUR" :figure/nominal-at "2026-01-01"}]
          (when-not drop-energy?
            [{:figure/field "guarantee-limit-2026-with-energy-measures"
              :figure/raw "Bij meefinanciering van energiebesparende voorzieningen is de grens € 498.200"
              :figure/monetary? true :figure/currency "EUR" :figure/nominal-at "2026-01-01"}])))
    :obs/missingness
    {:flags (if moved? [:legal-construction-unverified :rate-or-ceiling-unverified]
                [:legal-construction-unverified])
     :not-verified ["waarborgfondsconstructie / achtervang detail"]}
    :obs/refresh-of refresh-of}))

(deftest refresh-delta-reports-what-moved-verbatim-with-both-bases
  (let [g1 (nhg-observation "2026-09-01")
        g2 (nhg-observation-varied "2026-09-02" :moved? true)
        d (obs/refresh-delta g1 g2)]
    (is (= "delta-obs-NLD-support-nld.nhg-2026-09-01-to-obs-NLD-support-nld.nhg-2026-09-02"
           (:delta/id d)) "deterministic id from the two generation ids")
    (is (= "mortgage-observation/4" (:obs/method d)) "the delta carries the contract version")
    (is (= 1 (count (:delta/figures-changed d))))
    (let [c (first (:delta/figures-changed d))]
      (is (= "guarantee-limit-2026" (:delta/field c)))
      (is (str/includes? (get-in c [:delta/prior-figure :figure/raw]) "470.000")
          "the prior verbatim text is carried")
      (is (str/includes? (get-in c [:delta/next-figure :figure/raw]) "450.000")
          "the next verbatim text is carried")
      (is (= "EUR" (get-in c [:delta/prior-figure :figure/currency])))
      (is (= "EUR" (get-in c [:delta/next-figure :figure/currency])))
      (is (= "2026-01-01" (get-in c [:delta/prior-figure :figure/nominal-at]))
          "both bases ride on the delta — neither is dropped or rewritten"))
    (is (= 1 (:delta/figures-unchanged d)) "the unchanged field is counted, not reported")
    (is (= [:rate-or-ceiling-unverified] (:delta/flags-added d)))
    (is (= [] (:delta/figures-added d)) "nothing was added")
    (is (= [] (:delta/figures-removed d)) "nothing was removed")
    (is (= ["rcpt-0c728b7741b7-2026-09-01"] (:delta/prior-receipts d))
        "the prior generation's RECEIPT id is the provenance of one side")
    (is (= ["rcpt-444444444444-2026-09-01"] (:delta/next-receipts d))
        "the next generation's receipt id is the provenance of the other — a new reading is a new receipt")
    (is (not (str/includes? (str d) "amount-normalized"))
        "no normalized amount exists anywhere in the delta")
    (is (seq (:delta/non-goals d)) "the non-goals statement rides on the delta")
    (is (nil? (:delta/kind d)) "something moved, so this is not the unchanged kind")
    (is (= d (obs/refresh-delta g1 g2)) "equal inputs, equal delta — deterministic")))

(deftest refresh-delta-reports-a-removed-figure-instead-of-dropping-it
  (let [g1 (nhg-observation "2026-09-01")
        g2 (nhg-observation-varied "2026-09-02" :drop-energy? true)
        d (obs/refresh-delta g1 g2)]
    (is (= ["guarantee-limit-2026-with-energy-measures"] (:delta/figures-removed d))
        "a figure the source stopped publishing is REPORTED — missing is unmeasured, not gone")
    (is (= [] (:delta/figures-changed d)))
    (is (nil? (:delta/kind d)) "a removal is a change, not 'nothing moved'")))

(deftest refresh-delta-unchanged-kind-for-a-same-subject-re-read
  (let [g1 (nhg-observation "2026-09-01")
        g2 (nhg-observation-varied "2026-09-02")
        d (obs/refresh-delta g1 g2)]
    (is (= :unchanged (:delta/kind d))
        "same subject, same verbatim figures, same flags: 'nothing moved' is itself the audit result")
    (is (not= (:delta/prior-receipts d) (:delta/next-receipts d))
        "receipt ids still differ — a new reading is a new receipt, and the delta says so")))

(deftest refresh-delta-refuses-non-observations-and-cross-subjects
  (let [g1 (nhg-observation "2026-09-01")
        g2 (nhg-observation-varied "2026-09-02")]
    (is (= :delta/not-an-observation
           (refusal-of #(obs/refresh-delta {:obs/id "obs-x"} g2)))
        "a raw map is not a frozen observation")
    (is (= :delta/not-an-observation
           (refusal-of #(obs/refresh-delta g1 (dissoc g2 :obs/method)))))
    (let [jpn-obs (obs/observation
                   {:obs/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
                    :obs/window {:from "2026-08-01" :to "2026-09-01"}
                    :obs/receipts [(obs/receipt
                                    {:source-url "https://www.flat35.com/loan/lineup/flat35/conditions/index.html"
                                     :source-class :official-programme-operator
                                     :source-language "ja" :issuing-entity "住宅金融支援機構 (JHF)"
                                     :jurisdiction "JPN"
                                     :content-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                     :observed-at "2026-09-01T05:10:00Z" :asserted-at "2026-08-01"})]
                    :obs/figures [{:figure/field "loan-amount-range"
                                   :figure/raw "100万円以上1億2,000万円以下"
                                   :figure/monetary? true :figure/currency "JPY"
                                   :figure/nominal-at "2026-08-01"}]
                    :obs/missingness {:flags [:rate-or-ceiling-unverified]
                                      :not-verified ["current-year 住宅ローン控除 rate/ceiling"]}})]
      (is (= :delta/cross-subject
             (refusal-of #(obs/refresh-delta g1 jpn-obs)))
          "a delta between different subjects is refused — they never refreshed each other"))))

(deftest refresh-refuses-a-link-to-another-subjects-observation
  (let [g1 (nhg-observation "2026-09-01")
        jpn-obs (obs/observation
                 {:obs/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
                  :obs/window {:from "2026-08-01" :to "2026-09-02"}
                  :obs/receipts [(obs/receipt
                                  {:source-url "https://www.flat35.com/loan/lineup/flat35/conditions/index.html"
                                   :source-class :official-programme-operator
                                   :source-language "ja" :issuing-entity "住宅金融支援機構 (JHF)"
                                   :jurisdiction "JPN"
                                   :content-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                   :observed-at "2026-09-01T05:10:00Z" :asserted-at "2026-08-01"})]
                  :obs/missingness {:flags [:rate-or-ceiling-unverified]
                                    :not-verified ["current-year 住宅ローン控除 rate/ceiling"]}
                  :obs/refresh-of (:obs/id g1)}
                 )
        history (obs/refresh [] g1)]
    (is (= :observation/refresh-of-cross-subject
           (refusal-of #(obs/refresh history jpn-obs)))
        "lineage is per subject: the append itself refuses, not only the readback")))

(deftest readback-chain-returns-the-lineage-with-aligned-deltas
  (let [g1 (nhg-observation "2026-09-01")
        g2 (obs/observation (assoc (nhg-observation-varied "2026-09-02" :moved? true)
                                   :obs/refresh-of (:obs/id g1)))
        g3 (obs/observation (assoc (nhg-observation-varied "2026-09-03" :moved? true
                                                             :drop-energy? true)
                                   :obs/refresh-of (:obs/id g2)))
        history (-> [] (obs/refresh g1) (obs/refresh g2) (obs/refresh g3))
        {:keys [readback/chain readback/deltas readback/generations]}
        (obs/readback-chain history {:jurisdiction "NLD" :plane :support
                                     :subject-id "nld.nhg" :as-of "2026-09-03"})]
    (is (= 3 generations) "all three generations come back")
    (is (= ["obs-NLD-support-nld.nhg-2026-09-01"
            "obs-NLD-support-nld.nhg-2026-09-02"
            "obs-NLD-support-nld.nhg-2026-09-03"]
           (mapv :obs/id chain)) "oldest first")
    (is (= 3 (count deltas)))
    (is (nil? (first deltas)) "the origin has no predecessor — its delta slot is nil")
    (is (= ["guarantee-limit-2026"] (mapv :delta/field (:delta/figures-changed (second deltas))))
        "generation 1 -> 2 changed the 2026 figure")
    (is (= ["guarantee-limit-2026-with-energy-measures"]
           (:delta/figures-removed (peek deltas)))
        "generation 2 -> 3 lost the energy-measures figure, and the chain says where")
    (let [early (obs/readback-chain history {:jurisdiction "NLD" :plane :support
                                             :subject-id "nld.nhg" :as-of "2026-09-01"})]
      (is (= 1 (:readback/generations early))
          "as-of before the first refresh reads a one-generation chain")
      (is (= [nil] (:readback/deltas early))))
    (is (:readback/miss
         (obs/readback-chain history {:jurisdiction "JPN" :plane :support
                                      :subject-id "jpn.flat35" :as-of "2026-09-03"}))
        "an unobserved subject is a miss, never a neighbouring chain")))

(deftest readback-chain-refuses-a-broken-or-cycling-lineage
  (let [g1 (nhg-observation "2026-09-01")
        g2 (obs/observation (assoc (nhg-observation-varied "2026-09-02" :moved? true)
                                   :obs/refresh-of (:obs/id g1)))]
    ;; a hand-assembled history whose origin is missing: refresh() can never
    ;; produce this, so readback refuses instead of silently truncating
    (is (= :readback/refresh-of-unknown
           (refusal-of #(obs/readback-chain [g2] {:jurisdiction "NLD" :plane :support
                                                  :subject-id "nld.nhg" :as-of "2026-09-02"})))
        "truncated lineage is refused, never cut")
    ;; a cycle: only a hand-assembled history can carry one
    (let [c1 (obs/observation (assoc (nhg-observation-varied "2026-09-02" :moved? true)
                                     :obs/refresh-of "obs-NLD-support-nld.nhg-2026-09-03"))
          c2 (obs/observation (assoc (nhg-observation-varied "2026-09-03" :moved? true)
                                     :obs/refresh-of (:obs/id c1)))]
      (is (= :readback/refresh-cycle
             (refusal-of #(obs/readback-chain [c1 c2] {:jurisdiction "NLD" :plane :support
                                                       :subject-id "nld.nhg" :as-of "2026-09-03"})))))))

(deftest readback-chain-refuses-a-tampered-generation
  (let [g1 (nhg-observation "2026-09-01")
        g2 (obs/observation (assoc (nhg-observation-varied "2026-09-02" :moved? true)
                                   :obs/refresh-of (:obs/id g1)))
        tampered-g1 (update-in g1 [:obs/receipts 0 :content-hash]
                               (constantly "sha256:deadbeef"))]
    (is (= :readback/tampered-receipt
           (refusal-of #(obs/readback-chain [tampered-g1 g2]
                                            {:jurisdiction "NLD" :plane :support
                                             :subject-id "nld.nhg" :as-of "2026-09-02"})))
        "a malformed receipt hash ANYWHERE in the lineage refuses the whole readback")))

(deftest a-receipt-edited-after-freezing-is-refused-not-rebranded
  (let [edited (assoc nhg-receipt :content-hash "sha256:5555555555555555555555555555555555555555555555555555555555555555")]
    (is (= :receipt/stale-id
           (refusal-of #(obs/receipt edited)))
        "the stored :receipt/id no longer derives from the edited hash — refuse")
    (is (= :receipt/stale-id
           (refusal-of #(obs/observation
                         {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
                          :obs/window {:from "2026-08-01" :to "2026-09-01"}
                          :obs/receipts [edited]})))
        "an observation carrying such a receipt is refused too")
    ;; re-freezing a CONSISTENT frozen receipt is fine — same id comes back
    (is (= (:receipt/id nhg-receipt) (:receipt/id (obs/receipt nhg-receipt)))
        "a consistent frozen receipt re-validates to itself")))

(deftest readback-chain-refuses-a-cross-subject-lineage-at-readout
  ;; refresh() refuses a cross-subject link (v2), so this history must be
  ;; hand-assembled — which is exactly the defense-in-depth case: a readout
  ;; over an assembled/imported history re-checks what the append checked.
  (let [foreign (obs/observation
                 {:obs/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
                  :obs/window {:from "2026-08-01" :to "2026-09-01"}
                  :obs/receipts [(obs/receipt
                                  {:source-url "https://www.flat35.com/loan/lineup/flat35/conditions/index.html"
                                   :source-class :official-programme-operator
                                   :source-language "ja" :issuing-entity "住宅金融支援機構 (JHF)"
                                   :jurisdiction "JPN"
                                   :content-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                   :observed-at "2026-09-01T05:10:00Z" :asserted-at "2026-08-01"})]
                  :obs/missingness {:flags [:rate-or-ceiling-unverified]
                                    :not-verified ["current-year 住宅ローン控除 rate/ceiling"]}})
        g2 (obs/observation (assoc (nhg-observation-varied "2026-09-02" :moved? true)
                                   :obs/refresh-of (:obs/id foreign)))]
    (is (= :readback/chain-cross-subject
           (refusal-of #(obs/readback-chain [foreign g2]
                                            {:jurisdiction "NLD" :plane :support
                                             :subject-id "nld.nhg" :as-of "2026-09-02"})))
        "the queried subject's latest observation links to a lineage element
         about another subject — refused at readout, never returned")))

;; --- 10. figure-level attribution (v3, part 13) -------------------------------

(defn- second-receipt
  "A second, DIFFERENT-source receipt for the same NLD subject (the regulator
  page that carries the 2027 limit, say). Fixture identity only — the URL is
  invented fixture text, the hash is a placeholder constant, and no test
  asserts anything about the real source."
  []
  (obs/receipt
   {:source-url "https://example-fixtures.test/nld/regulator-page"
    :source-class :official-regulator
    :source-language "nl" :issuing-entity "Autoriteit Financiële Markten"
    :jurisdiction "NLD"
    :content-hash "sha256:6666666666666666666666666666666666666666666666666666666666666666"
    :observed-at "2026-09-01T09:00:00Z" :asserted-at "2026-08-01"}))

(deftest sole-receipt-observation-attributes-every-figure
  (let [o (nhg-observation "2026-09-01")]
    (is (every? #(= "rcpt-0c728b7741b7-2026-09-01" (:figure/receipt-id %))
                (:obs/figures o))
        "with one receipt, its id is the attribution — deterministic, not optional")
    (is (every? #(some? (obs/figure-receipt o %)) (:obs/figures o))
        "figure-receipt resolves every attribution back to the carried receipt")
    (is (= (:source-url nhg-receipt)
           (:source-url (obs/figure-receipt o (first (:obs/figures o))))))))

(deftest multi-receipt-observation-without-attribution-is-refused
  (let [r2 (second-receipt)
        base {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
              :obs/window {:from "2026-08-01" :to "2026-09-01"}
              :obs/receipts [nhg-receipt r2]
              :obs/figures
              [{:figure/field "guarantee-limit-2026"
                :figure/raw "De NHG-grens per 1 januari 2026 is € 470.000"
                :figure/monetary? true :figure/currency "EUR"
                :figure/nominal-at "2026-01-01"}]
              :obs/missingness {:flags [:legal-construction-unverified]
                                :not-verified ["waarborgfondsconstructie / achtervang detail"]}}]
    (is (= :figure/ambiguous-receipt-attribution
           (refusal-of #(obs/observation base)))
        "two receipts and a figure with no :figure/receipt-id is ambiguous
         provenance — refused, never guessed")
    (is (= :figure/unattributable-receipt
           (refusal-of
            #(obs/observation
              (assoc base :obs/figures
                     [{:figure/field "guarantee-limit-2026"
                       :figure/raw "De NHG-grens per 1 januari 2026 is € 470.000"
                       :figure/monetary? true :figure/currency "EUR"
                       :figure/nominal-at "2026-01-01"
                       :figure/receipt-id "rcpt-nowhere-0000-0000-00"}]))))
        "an explicit id no carried receipt answers to is refused")
    ;; naming the receipt that evidenced each figure is the honest path
    (is (some?
         (obs/observation
          (assoc base :obs/figures
                 [{:figure/field "guarantee-limit-2026"
                   :figure/raw "De NHG-grens per 1 januari 2026 is € 470.000"
                   :figure/monetary? true :figure/currency "EUR"
                   :figure/nominal-at "2026-01-01"
                   :figure/receipt-id (:receipt/id nhg-receipt)}
                  {:figure/field "guarantee-limit-2027"
                   :figure/raw "De NHG-grens per 1 januari 2027 is € 450.000"
                   :figure/monetary? true :figure/currency "EUR"
                   :figure/nominal-at "2027-01-01"
                   :figure/receipt-id (:receipt/id r2)}])))
        "explicit per-figure attribution under several receipts is accepted")))

(deftest figure-claims-cite-their-own-receipt
  (let [r2 (second-receipt)
        o (obs/observation
           {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
            :obs/window {:from "2026-08-01" :to "2026-09-01"}
            :obs/receipts [nhg-receipt r2]
            :obs/figures
            [{:figure/field "guarantee-limit-2026"
              :figure/raw "De NHG-grens per 1 januari 2026 is € 470.000"
              :figure/monetary? true :figure/currency "EUR"
              :figure/nominal-at "2026-01-01"
              :figure/receipt-id (:receipt/id nhg-receipt)}
             {:figure/field "guarantee-limit-2027"
              :figure/raw "De NHG-grens per 1 januari 2027 is € 450.000"
              :figure/monetary? true :figure/currency "EUR"
              :figure/nominal-at "2027-01-01"
              :figure/receipt-id (:receipt/id r2)}]
            :obs/missingness {:flags [:legal-construction-unverified]
                              :not-verified ["waarborgfondsconstructie / achtervang detail"]}})
        claims (obs/hyakka-proposal o)
        c2026 (first (filter #(str/includes? (:claim/id %) "guarantee-limit-2026") claims))
        c2027 (first (filter #(str/includes? (:claim/id %) "guarantee-limit-2027") claims))]
    (is (= (:receipt/id nhg-receipt) (:claim/receipt-id c2026)))
    (is (= (:source-url nhg-receipt) (get-in c2026 [:claim/receipt :source-url]))
        "the 2026 claim cites the receipt that evidenced IT")
    (is (= (:receipt/id r2) (:claim/receipt-id c2027)))
    (is (= (:source-url r2) (get-in c2027 [:claim/receipt :source-url]))
        "the 2027 claim cites a DIFFERENT receipt — per-figure provenance")
    (is (= 2 (count (distinct (map :claim/receipt-id
                                   [c2026 c2027]))))
        "two figures, two evidencing readings — no claim hangs on the wrong source")))

(deftest pre-attribution-frozen-figures-fall-back-to-the-sole-receipt
  ;; a frozen /1 or /2 artifact predates :figure/receipt-id; its claims must
  ;; still cite a basis — the sole receipt — and must NOT inherit the peek
  ;; basis when the observation carries several receipts (ambiguity is
  ;; carried as ambiguity).
  (let [o (nhg-observation "2026-09-01")
        stripped (update o :obs/figures
                         #(mapv (fn [f] (dissoc f :figure/receipt-id)) %))
        claims (obs/hyakka-proposal stripped)
        fig (first (filter #(str/includes? (:claim/id %) "guarantee-limit-2026") claims))]
    (is (= (:source-url nhg-receipt) (get-in fig [:claim/receipt :source-url]))
        "sole-receipt fallback: the claim still cites THE receipt")
    (is (nil? (:claim/receipt-id fig))
        "no invented :claim/receipt-id — the attribution predates the field")
    (let [r2 (second-receipt)
          multi (assoc stripped :obs/receipts [nhg-receipt r2])
          claims2 (obs/hyakka-proposal multi)
          fig2 (first (filter #(str/includes? (:claim/id %) "guarantee-limit-2026") claims2))]
      (is (nil? (:claim/receipt fig2))
          "several receipts and no attribution: NO single basis is claimed —
           not whichever receipt sorted last")
      (is (seq (:claim/receipts fig2))
          "the full receipt set still rides on the claim for the reader"))))

(deftest refresh-delta-reports-re-attribution-as-its-own-row
  ;; same verbatim text, different evidencing source: the content did not
  ;; move, the ATTRIBUTION did — and the delta must say which.
  (let [r2 (second-receipt)
        g1 (nhg-observation "2026-09-01")
        g2 (obs/observation
            {:obs/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
             :obs/window {:from "2026-08-01" :to "2026-09-02"}
             :obs/receipts [nhg-receipt r2]
             :obs/figures
             [{:figure/field "guarantee-limit-2026"
               :figure/raw "De NHG-grens per 1 januari 2026 is € 470.000"
               :figure/monetary? true :figure/currency "EUR"
               :figure/nominal-at "2026-01-01"
               :figure/receipt-id (:receipt/id r2)}
              {:figure/field "guarantee-limit-2026-with-energy-measures"
               :figure/raw "Bij meefinanciering van energiebesparende voorzieningen is de grens € 498.200"
               :figure/monetary? true :figure/currency "EUR"
               :figure/nominal-at "2026-01-01"
               :figure/receipt-id (:receipt/id nhg-receipt)}]
             :obs/missingness {:flags [:legal-construction-unverified]
                               :not-verified ["waarborgfondsconstructie / achtervang detail"]}
             :obs/refresh-of (:obs/id g1)})
        d (obs/refresh-delta g1 g2)]
    (is (= [] (:delta/figures-changed d))
        "no verbatim text moved — this is NOT a content change")
    (is (= ["guarantee-limit-2026"] (mapv :delta/field (:delta/re-attributed d)))
        "the re-attribution is reported as itself")
    (let [ra (first (:delta/re-attributed d))]
      (is (= "rcpt-0c728b7741b7-2026-09-01" (:delta/prior-receipt-id ra)))
      (is (= (:receipt/id r2) (:delta/next-receipt-id ra)))
      (is (= (:source-url nhg-receipt) (:delta/prior-source-url ra)))
      (is (= (:source-url r2) (:delta/next-source-url ra))
          "both sides carried in full — the auditor sees both sources"))
    (is (nil? (:delta/kind d)) "an attribution move is a move — not :unchanged")
    (is (= d (obs/refresh-delta g1 g2)) "equal inputs, equal delta")))

(deftest refresh-delta-a-new-receipt-of-the-same-source-is-not-a-re-attribution
  ;; g1's figures are attributed to the NHG receipt; g2 re-reads the SAME
  ;; page (same URL, new bytes — the v2 varied fixture) and attributes to the
  ;; new receipt: the evidencing SOURCE did not move, so no re-attribution
  ;; row — and the v2 :unchanged kind is preserved.
  (let [g1 (nhg-observation "2026-09-01")
        g2 (nhg-observation-varied "2026-09-02")
        d (obs/refresh-delta g1 g2)]
    (is (= [] (:delta/re-attributed d)) "no source moved — receipt ids differ, sources do not")
    (is (= :unchanged (:delta/kind d)) "the v2 unchanged kind is preserved")))

;; --- 11. typed event inputs (v4, part 14) --------------------------------------
;;
;; FIXTURE TEXT ONLY. Every announcement string below is invented fixture
;; prose for the mechanics test — no test asserts anything about the real
;; NHG programme, its real future, or any real publication. A real event
;; run records what the source actually published, under its own receipt.

(defn- nhg-event
  "A frozen event over the NLD/NHG subject, built on the live receipt fixture
  (same posture as the observation fixtures: the receipt is real, the
  announcement text is fixture prose)."
  [effective-at & {:keys [announcement-raw kind figures flags refresh-of]}]
  (obs/event
   {:event/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
    :event/kind (or kind :parameter-change-announced)
    :event/announcement-raw
    (or announcement-raw
        "De NHG-grens per 1 januari 2026 is € 470.000 (fixture announcement text)")
    :event/effective-at effective-at
    :event/receipts [nhg-receipt]
    :event/figures (or figures [])
    :event/missingness {:flags (or flags []) :not-verified []}
    :event/refresh-of refresh-of}))

(deftest event-freezes-with-a-deterministic-id-and-the-source-stated-effective-date
  (let [ev (nhg-event "2026-01-01")]
    (is (= "evt-NLD-support-nld.nhg-:parameter-change-announced-2026-01-01" (:event/id ev))
        "deterministic id: subject + kind + source-stated effective date")
    (is (= "mortgage-observation/4" (:event/method ev)) "events carry the contract version")
    (is (= "2026-01-01" (:event/effective-at ev))
        "the effective date is the SOURCE's own stated date — carried, never computed")
    (is (contains? obs/event-kinds (:event/kind ev)) "the kind is from the closed vocabulary")))

(deftest events-refuse-unprovenanced-and-phantom-input
  (is (= :event/unknown-kind
         (refusal-of #(nhg-event "2026-01-01" :kind :a-good-feeling-about-rates)))
      "event kinds are closed — an ad-hoc kind is refused")
  (is (= :event/unknown-subject
         (refusal-of
          #(obs/event
            {:event/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.starterslening"}
             :event/kind :programme-terminated
             :event/announcement-raw "the scheme ends (fixture text)"
             :event/effective-at "2026-01-01"
             :event/receipts [nhg-receipt]})))
      "SVn is a published GAP — announcing an event about it as if seeded is fabrication")
  (is (= :event/bad-effective-at
         (refusal-of #(nhg-event "01-01-2026")))
      "effective-at is a typed ISO date, not a formatted guess")
  (is (= :event/missing-field
         (refusal-of #(nhg-event nil)))
      "an event without its effective date is incomplete")
  (is (= :event/empty-announcement
         (refusal-of #(nhg-event "2026-01-01" :announcement-raw "   ")))
      "an event without the source's own words is a rumor")
  (is (= :event/no-receipts
         (refusal-of
          #(obs/event
            {:event/subject {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"}
             :event/kind :parameter-change-announced
             :event/announcement-raw "fixture text"
             :event/effective-at "2026-01-01"
             :event/receipts []})))
      "an event without a receipt is a rumor")
  (is (= :event/cross-jurisdiction-receipt
         (refusal-of
          #(obs/event
            {:event/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
             :event/kind :parameter-change-announced
             :event/announcement-raw "fixture text"
             :event/effective-at "2026-01-01"
             :event/receipts [nhg-receipt]})))
      "a NLD receipt cannot evidence a JPN announcement — entity separation")
  (is (= :event/unknown-missingness-flag
         (refusal-of #(nhg-event "2026-01-01" :flags [:vibes-unverified])))
      "missingness flags stay in the closed vocabulary"))

(deftest event-figures-carry-the-same-basis-discipline
  (is (= :figure/monetary-without-currency
         (refusal-of
          #(nhg-event "2026-01-01"
                      :figures [{:figure/field "new-limit"
                                 :figure/raw "€ 450.000"
                                 :figure/monetary? true}])))
      "a figure an announcement publishes carries its currency or is refused")
  (let [ev (nhg-event "2026-01-01"
                      :figures [{:figure/field "new-limit"
                                 :figure/raw "€ 450.000"
                                 :figure/monetary? true
                                 :figure/currency "EUR"
                                 :figure/nominal-at "2027-01-01"}])]
    (is (= "EUR" (get-in ev [:event/figures 0 :figure/currency])))
    (is (= "2027-01-01" (get-in ev [:event/figures 0 :figure/nominal-at]))
        "the figure keeps its OWN nominal date — the announcement date and the
         nominal date are different facts and both ride")))

(deftest event-lineage-events-refresh-events-never-observations
  (let [g1 (nhg-event "2026-01-01")
        g2 (nhg-event "2027-01-01" :refresh-of (:event/id g1)
                      :announcement-raw "De NHG-grens per 1 januari 2027 is € 450.000 (fixture)")
        history (-> [] (obs/refresh-event g1) (obs/refresh-event g2))
        obs (nhg-observation "2026-09-01")]
    (is (= 2 (count history)) "append-only: both announcements remain")
    (is (= :event/duplicate-event-id
           (refusal-of #(obs/refresh-event history g1)))
        "the same event id cannot be recorded twice")
    (is (= :event/refresh-of-unknown
           (refusal-of #(obs/refresh-event [] (nhg-event "2027-01-01" :refresh-of "evt-nowhere"))))
        "a link to an unrecorded event is refused")
    (is (= :event/refresh-of-self
           (refusal-of #(obs/refresh-event [] (nhg-event "2026-01-01"
                                                         :refresh-of "evt-NLD-support-nld.nhg-:parameter-change-announced-2026-01-01")))))
    (is (= :event/refresh-of-observation
           (refusal-of #(obs/refresh-event (obs/refresh [] obs)
                                           (nhg-event "2027-01-01" :refresh-of (:obs/id obs)))))
        "events refresh events, observations refresh observations — never across kinds")
    (is (= :event/not-an-event
           (refusal-of #(obs/refresh-event history {:event/id "evt-x"})))
        "a raw map is not a frozen event")))

(deftest event-readback-dates-by-effective-at-and-revalidates
  (let [g1 (nhg-event "2026-01-01")
        g2 (nhg-event "2027-01-01" :refresh-of (:event/id g1)
                      :announcement-raw "De NHG-grens per 1 januari 2027 is € 450.000 (fixture)")
        history (-> [] (obs/refresh-event g1) (obs/refresh-event g2))]
    (is (= (:event/id g1) (:event/id (obs/readback-events history
                                    {:jurisdiction "NLD" :plane :support
                                     :subject-id "nld.nhg" :as-of "2026-06-30"})))
        "as-of between the two effective dates reads the first announcement —
         an announcement takes effect when the source says, not when we read it")
    (is (= (:event/id g2) (:event/id (obs/readback-events history
                                    {:jurisdiction "NLD" :plane :support
                                     :subject-id "nld.nhg" :as-of "2027-01-01"}))))
    (is (= (:event/id g2)
           (:event/id (obs/readback-events history
                        {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"
                         :as-of "2027-06-30" :kind :parameter-change-announced})))
        "the kind filter keeps the matching kind's latest announcement")
    (is (:event/miss (obs/readback-events history
                          {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"
                           :as-of "2027-06-30" :kind :programme-terminated}))
        "no termination was ever announced — the miss is a miss, not a default")
    (is (:event/miss (obs/readback-events history
                          {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"
                           :as-of "2027-06-30"})))
    (let [tampered (update-in g1 [:event/receipts 0 :content-hash]
                              (constantly "sha256:deadbeef"))]
      (is (= :readback/tampered-receipt
             (refusal-of #(obs/readback-events (obs/refresh-event [] tampered)
                            {:jurisdiction "NLD" :plane :support :subject-id "nld.nhg"
                             :as-of "2026-06-30"})))
          "a tampered receipt refuses the event readback, never returns"))))

(deftest event-delta-reports-what-moved-verbatim
  (let [g1 (nhg-event "2026-01-01")
        g2 (nhg-event "2027-01-01" :refresh-of (:event/id g1)
                      :announcement-raw "De NHG-grens per 1 januari 2027 is € 450.000 (fixture)")
        d (obs/event-delta g1 g2)]
    (is (= "edlt-evt-NLD-support-nld.nhg-:parameter-change-announced-2026-01-01-to-evt-NLD-support-nld.nhg-:parameter-change-announced-2027-01-01"
           (:event-delta/id d)) "deterministic id from the two event ids")
    (is (true? (:event-delta/announcement-moved d))
        "the announcement text moved — said as itself, at the verbatim level")
    (is (str/includes? (:event-delta/prior-announcement d) "470.000")
        "both announcement texts ride in full")
    (is (str/includes? (:event-delta/next-announcement d) "450.000"))
    (is (= [] (:event-delta/figures-added d)) "no figures were added")
    (is (= "mortgage-observation/4" (:obs/method d)) "the delta carries the contract version")
    (is (nil? (:event-delta/kind d)) "something moved — not :unchanged")
    (is (= d (obs/event-delta g1 g2)) "equal inputs, equal delta — deterministic")
    ;; the same announcement re-observed (new receipt, same text): :unchanged
    (let [g3 (obs/event (assoc (nhg-event "2026-01-01")
                               :event/refresh-of (:event/id g1)
                               :event/receipts [(varied-receipt)]))
          d2 (obs/event-delta g1 g3)]
      (is (= :unchanged (:event-delta/kind d2))
          "a re-read of the same announcement is the 'nothing moved' audit result")
      (is (not= (:event-delta/prior-receipts d2) (:event-delta/next-receipts d2))
          "receipt ids still differ — a new reading is a new receipt, and the delta says so"))
    ;; cross-subject refusal
    (let [jpn (obs/event
               {:event/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
                :event/kind :parameter-change-announced
                :event/announcement-raw "fixture text"
                :event/effective-at "2026-01-01"
                :event/receipts [(obs/receipt
                                  {:source-url "https://www.flat35.com/loan/lineup/flat35/conditions/index.html"
                                   :source-class :official-programme-operator
                                   :source-language "ja" :issuing-entity "住宅金融支援機構 (JHF)"
                                   :jurisdiction "JPN"
                                   :content-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                   :observed-at "2026-09-01T05:10:00Z" :asserted-at "2026-08-01"})]})]
      (is (= :event/cross-subject (refusal-of #(obs/event-delta g1 jpn)))
          "a delta between different subjects is refused — they never refreshed each other"))
    (is (= :event/not-an-event (refusal-of #(obs/event-delta g1 {:event/id "evt-x"})))
        "a raw map is not a frozen event")))

(deftest event-chain-walks-lineage-and-refuses-broken-ones
  (let [g1 (nhg-event "2026-01-01")
        g2 (nhg-event "2027-01-01" :refresh-of (:event/id g1)
                      :announcement-raw "De NHG-grens per 1 januari 2027 is € 450.000 (fixture)")
        g3 (nhg-event "2028-01-01" :refresh-of (:event/id g2)
                      :announcement-raw "De NHG-grens per 1 januari 2028 is € 460.000 (fixture)")
        history (-> [] (obs/refresh-event g1) (obs/refresh-event g2) (obs/refresh-event g3))
        {:keys [event/chain chain/deltas event/generations]}
        (obs/readback-event-chain history {:jurisdiction "NLD" :plane :support
                                           :subject-id "nld.nhg" :as-of "2028-06-30"})]
    (is (= 3 generations) "all three announcements come back")
    (is (= ["evt-NLD-support-nld.nhg-:parameter-change-announced-2026-01-01"
            "evt-NLD-support-nld.nhg-:parameter-change-announced-2027-01-01"
            "evt-NLD-support-nld.nhg-:parameter-change-announced-2028-01-01"]
           (mapv :event/id chain)) "oldest effective-at first")
    (is (= 3 (count deltas)))
    (is (nil? (first deltas)) "the origin has no predecessor — its delta slot is nil")
    (is (true? (:event-delta/announcement-moved (second deltas))))
    (let [early (obs/readback-event-chain history {:jurisdiction "NLD" :plane :support
                                                   :subject-id "nld.nhg" :as-of "2026-06-30"})]
      (is (= 1 (:event/generations early)) "as-of before later announcements reads a one-generation chain")
      (is (= [nil] (:chain/deltas early))))
    ;; broken lineages — hand-assembled histories only
    (is (= :event-chain/refresh-of-unknown
           (refusal-of #(obs/readback-event-chain [g2] {:jurisdiction "NLD" :plane :support
                                                        :subject-id "nld.nhg" :as-of "2027-06-30"})))
        "truncated lineage is refused, never cut")
    (let [c1 (nhg-event "2027-01-01" :refresh-of "evt-NLD-support-nld.nhg-:parameter-change-announced-2028-01-01"
                        :announcement-raw "fixture")
          c2 (nhg-event "2028-01-01" :refresh-of (:event/id c1) :announcement-raw "fixture")]
      (is (= :event-chain/refresh-cycle
             (refusal-of #(obs/readback-event-chain [c1 c2] {:jurisdiction "NLD" :plane :support
                                                             :subject-id "nld.nhg" :as-of "2028-06-30"})))
          "a cycle is refused"))))

(deftest event-chain-refuses-a-cross-subject-lineage-at-readout
  ;; refresh-event refuses a cross-subject link at append, so this history
  ;; must be hand-assembled — defense in depth: a readout over an assembled
  ;; or imported history re-checks what the append checked.
  (let [foreign (obs/event
                 {:event/subject {:jurisdiction "JPN" :plane :support :subject-id "jpn.flat35"}
                  :event/kind :parameter-change-announced
                  :event/announcement-raw "fixture text"
                  :event/effective-at "2026-01-01"
                  :event/receipts [(obs/receipt
                                    {:source-url "https://www.flat35.com/loan/lineup/flat35/conditions/index.html"
                                     :source-class :official-programme-operator
                                     :source-language "ja" :issuing-entity "住宅金融支援機構 (JHF)"
                                     :jurisdiction "JPN"
                                     :content-hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                                     :observed-at "2026-09-01T05:10:00Z" :asserted-at "2026-08-01"})]})
        g2 (nhg-event "2027-01-01" :refresh-of (:event/id foreign) :announcement-raw "fixture")]
    (is (= :event-chain/chain-cross-subject
           (refusal-of #(obs/readback-event-chain [foreign g2]
                            {:jurisdiction "NLD" :plane :support
                             :subject-id "nld.nhg" :as-of "2027-06-30"})))
        "the queried subject's latest event links to a lineage element about
         another subject — refused at readout, never returned")))

(deftest event-proposal-is-data-with-announcement-not-prediction-qualifiers
  (let [ev (nhg-event "2027-01-01"
                      :announcement-raw "De NHG-grens per 1 januari 2027 is € 450.000 (fixture)"
                      :figures [{:figure/field "announced-limit-2027"
                                 :figure/raw "€ 450.000"
                                 :figure/monetary? true
                                 :figure/currency "EUR"
                                 :figure/nominal-at "2027-01-01"}])
        claims (obs/hyakka-event-proposal ev)]
    (is (= 2 (count claims)) "one announcement-level claim + one per published figure")
    (is (every? #(= "fudosan" (:claim/corpus %)) claims))
    (is (every? #(= "world/mortgage-event/mortgage-support/nld.nhg" (:claim/entity %)) claims)
        "event claims mint event-realm entities — never observation entities")
    (is (every? #(str/starts-with? (:claim/entity %) "world/mortgage-event/") claims))
    (let [head (first claims)]
      (is (= "prop/mortgage-support-programme-event" (:claim/prop head)))
      (is (= "parameter-change-announced" (:claim/event-kind head)))
      (is (= "2027-01-01" (:claim/effective-at head)))
      (is (str/includes? (:claim/effective-at-note head) "never computed")
          "the effective-date boundary rides on the claim itself")
      (is (str/includes? (:claim/value head) "450.000") "the VERBATIM announcement is the value"))
    (let [fig (first (filter #(contains? % :claim/currency) claims))]
      (is (= "EUR" (:claim/currency fig)))
      (is (= "2027-01-01" (:claim/nominal-at fig))))
    (is (every? (comp true? :announcement-not-prediction :claim/qualifiers) claims)
        "every claim carries announcement-not-prediction — an observed announcement
         is an observation of a publication, never a claim about what will happen")
    (is (every? (comp true? :no-model :claim/qualifiers) claims))
    (is (every? (comp true? :proposal/ontology-registration-pending :claim/qualifiers) claims)
        "the event props are contract-local and NOT registered in the Hyakka ontology")
    (is (every? (comp true? :proposal/source-class-unmapped :claim/qualifiers) claims)
        "the fixture's programme-operator class is a scope-unmapped class — surfaced"))
  (is (= :proposal-event/not-an-event
         (refusal-of #(obs/hyakka-event-proposal {:event/id "evt-x"})))
      "a raw map is not a frozen event"))
