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
  (is (= "mortgage-observation/2" obs/contract-version))
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
    (is (every? #(= "mortgage-observation/2" (:deterministic-extractor (:claim/qualifiers %))) claims)
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
    (is (= "mortgage-observation/2" (:obs/method d)) "the delta carries the contract version")
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
