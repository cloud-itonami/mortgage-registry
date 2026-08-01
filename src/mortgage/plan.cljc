(ns mortgage.plan
  "Worldwide coverage plan for `mortgage.facts/catalog`.

  The catalog seeds 6 jurisdictions. This namespace is the plan for the other
  ~188, expressed as DATA so that progress is measured rather than asserted,
  and so a batch run can pull work off a queue instead of a human choosing
  what looks easy.

  THREE THINGS THIS NAMESPACE REFUSES TO DO, because each is how a coverage
  plan usually starts lying:

  1. It does not compute a jurisdiction's status from a self-declared level.
     `plane-status` reads the actual catalog entry: a plane is `:verbatim`
     only if the evidence that defines it is physically present. Nobody can
     mark a jurisdiction done.
  2. It does not treat a legal-family resemblance as coverage. MCD, OHADA and
     Torrens genuinely reduce RESEARCH cost for their members (one shared
     instrument to read first), and `shared-instruments` records that. They
     never reduce CITATION duty: each jurisdiction still needs its own
     transposition/statute and its own source URL.
  3. It does not promise a completion date. `throughput` records what one
     measured session actually cost; the waves are ordered work, not a
     schedule.

  ONE CATALOG, NOT 194 REPOSITORIES. The whole value here is cross-country
  queries ('which jurisdictions guarantee a first-time buyer loan through a
  state fund?'), and the workspace rule that things you want to join must
  live on one plane (ADR-260726-kotobase-query-plane-is-one-ref) applies
  directly: sharding this catalog by country would make exactly the questions
  it exists to answer unanswerable."
  (:require [clojure.string :as str]
            [mortgage.facts :as facts]))

;; --- measuring where a jurisdiction actually is ---------------------------

;; `status` needs a jurisdiction's wave target; the waves are defined below so
;; that the reader meets the measurement before the plan.
(declare waves wave-of)

(defn plane-status
  "Status of one plane for `iso3`, computed from the entry itself:
     :absent   nothing seeded
     :located  a source is named, but its content was not read into the entry
     :verbatim the entry carries content that could only come from reading it

  Deliberately cumulative-free: a jurisdiction can be :verbatim on support and
  :located on procedure (that is exactly FRA and GBR today), and flattening
  that into one number would hide which half is missing."
  [iso3 plane]
  (let [entry (facts/spec-basis iso3)]
    (case plane
      :organizations (cond (nil? entry) :absent
                           (seq (:organizations entry)) :located
                           :else :absent)
      :procedure (let [p (:procedure entry)]
                   (cond (or (nil? entry) (nil? p)) :absent
                         (seq (:steps p)) :verbatim
                         (:provenance p) :located
                         :else :absent))
      :support (let [s (:support entry)]
                 (cond (or (nil? entry) (empty? s)) :absent
                       (some (comp seq :eligibility-signals) s) :verbatim
                       :else :located)))))

(def planes [:organizations :procedure :support])

(def ^:private rank {:absent 0 :located 1 :verbatim 2})

(defn status
  "Per-plane status map for `iso3`. `:next` is the first plane that has not
  reached its WAVE'S target -- not the first plane short of the theoretical
  maximum. Wave 4 deliberately targets `:absent` procedure and support, so a
  tail jurisdiction with its regulator located is finished for now and must
  not sit in the queue forever pretending otherwise."
  ([iso3] (status iso3 nil))
  ([iso3 target]
   (let [m (zipmap planes (map #(plane-status iso3 %) planes))
         target (or target (:target (nth waves (or (wave-of iso3) 4))))]
     (assoc m :next (or (some (fn [p]
                                (when (< (rank (m p)) (rank (get target p :located)))
                                  p))
                              planes)
                        :none)))))

(defn progress
  "Fleet-wide progress: how many jurisdictions are at each status, per plane.
  Counts against the whole universe, not against the seeded subset -- a
  denominator of 6 would make 6/6 look finished."
  [universe]
  (into {:universe (count universe)}
        (for [p planes]
          [p (frequencies (map #(plane-status % p) universe))])))

;; --- ordered work ----------------------------------------------------------

(def waves
  "Ordered work. Each wave states WHY it is where it is; an ordering nobody can
  justify gets reordered by whoever is bored."
  [{:wave 0
    :name "seed"
    :rationale "Six jurisdictions with a live public-support programme and a fetchable official source, chosen to prove the three-plane shape end to end."
    :members ["JPN" "USA" "GBR" "DEU" "FRA" "NLD"]
    :target {:organizations :located :procedure :verbatim :support :verbatim}
    :done true}

   {:wave 1
    :name "sibling parity"
    :rationale "Jurisdictions the neighbouring actors already cite but this catalog does not. Closing this first stops the fleet from disagreeing with itself: cloud-itonami-isic-6492 covers IDN, and cloud-itonami-isic-6810 covers AUS-NSW / CAN-ON / USA-CA as sub-national exemplars. Cheapest possible wave -- the sources are already located in a sibling repository."
    :members ["IDN" "AUS" "CAN"]
    :sub-national-exemplars ["AUS-NSW" "CAN-ON" "USA-CA"]
    :target {:organizations :located :procedure :verbatim :support :verbatim}}

   {:wave 2
    :name "EEA under the Mortgage Credit Directive"
    :rationale "Directive 2014/17/EU harmonises pre-contractual information (ESIS), APRC calculation and the reflection/withdrawal right across the EEA. That collapses the per-country research question from 'what is the mortgage disclosure regime' to three narrower ones: the national transposition act, the national land register, and the national support programme. It is the single largest reduction in cost per jurisdiction available anywhere in this plan -- and it reduces research, never citation: each member still needs its own transposition and its own URL."
    :members ["AUT" "BEL" "BGR" "HRV" "CYP" "CZE" "DNK" "EST" "FIN" "GRC"
              "HUN" "ISL" "IRL" "ITA" "LVA" "LIE" "LTU" "LUX" "MLT" "NOR"
              "POL" "PRT" "ROU" "SVK" "SVN" "ESP" "SWE"]
    :target {:organizations :located :procedure :verbatim :support :verbatim}
    :precondition "Read and cite Directive 2014/17/EU from EUR-Lex ONCE, into `shared-instruments`, before the first member entry. Without that anchor each of the 27 entries invents its own description of the same directive."}

   {:wave 3
    :name "large markets outside the EEA"
    :rationale "Ordered by residential mortgage market size, which must be SOURCED before this wave starts (see `open-decisions`) -- not by population, and not by which official websites happen to be in English."
    :members ["CHN" "IND" "BRA" "MEX" "KOR" "SGP" "CHE" "TUR" "RUS" "SAU"
              "ARE" "ZAF" "NZL" "ISR" "THA" "MYS" "PHL" "VNM" "EGY" "NGA"]
    :target {:organizations :located :procedure :verbatim :support :verbatim}}

   {:wave 4
    :name "long tail at organization level"
    :rationale "Every remaining sovereign jurisdiction, to :located organizations only -- the financial regulator, the land registry, and the housing/mortgage agency if one exists, each with an official URL. This is the honest ceiling for the tail: it makes the catalog exhaustive in WHO to ask without pretending to know what the answer is. A jurisdiction at :located organizations and :absent procedure reads correctly; a fabricated procedure does not."
    :members :remainder
    :target {:organizations :located :procedure :absent :support :absent}}])

(defn wave-of [iso3]
  (some (fn [{:keys [wave members]}]
          (when (and (vector? members) (some #{iso3} members)) wave))
        waves))

(defn queue
  "The work queue: every jurisdiction in `universe` that is not yet at its
  wave's target, earliest wave first. This is what a batch run consumes."
  [universe]
  (->> universe
       (map (fn [iso3]
              (let [w (or (wave-of iso3) 4)
                    st (status iso3)]
                {:iso3 iso3 :wave w :status (dissoc st :next) :next (:next st)})))
       (remove #(= :none (:next %)))
       (sort-by (juxt :wave :iso3))
       vec))

(defn next-batch
  "The next `n` units of work. A batch run should take a small n and land a
  reviewable diff, not attempt a wave in one commit."
  [universe n]
  (vec (take n (queue universe))))

;; --- what makes some jurisdictions cheaper than others --------------------

(def shared-instruments
  "Instruments that genuinely reduce research cost across a group. Each must be
  read and cited ONCE, at the URL below, before its members are seeded --
  otherwise every member entry paraphrases it slightly differently and the
  catalog acquires 27 subtly disagreeing descriptions of one directive.

  `:reduces` says exactly what it does and does not buy."
  [{:id :mcd-2014-17-eu
    :name "Directive 2014/17/EU on credit agreements for consumers relating to residential immovable property (Mortgage Credit Directive)"
    :members-wave 2
    :source "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32014L0017"
    :status :not-yet-read
    :reduces "Pre-contractual disclosure (ESIS), APRC calculation and the reflection/withdrawal right are harmonised across the EEA, so those need reading once rather than 27 times."
    :does-not-reduce "National transposition act, land-register procedure, security instrument and public support programme remain fully per-country, and each member entry still needs its own source URL."}

   {:id :ohada-uniform-act-securities
    :name "OHADA Acte uniforme portant organisation des sûretés"
    :members-wave 4
    :source "https://www.ohada.org/"
    :status :not-yet-read
    :reduces "The hypothèque as a security instrument is governed by one uniform act across the OHADA member states, so the security-instrument field of the procedure plane is shared."
    :does-not-reduce "Land registration practice, the lending regime and any national support programme remain per-country. Membership must be verified from OHADA's own site before any member is seeded -- do not assume a country is a member from its region."}

   {:id :torrens-title-systems
    :name "Torrens title registration (AUS, NZL, SGP, MYS and others)"
    :members-wave 3
    :source nil
    :status :not-an-instrument
    :reduces "NOTHING legally. Recorded here only to stop a future contributor from treating a shared registration STYLE as a shared legal basis."
    :does-not-reduce "Every Torrens jurisdiction has its own statute and its own registry; the resemblance is structural, not normative."}])

;; --- measured cost, so the plan is not wishful ----------------------------

(def throughput
  "What one jurisdiction actually cost, measured on the 2026-08-01 seeding
  session rather than estimated. Recorded so that a wave's size can be chosen
  against evidence."
  {:measured-on "2026-08-01"
   :jurisdictions-seeded 6
   :web-calls-spent 24
   :calls-per-jurisdiction-at-verbatim 4
   :failed-call-rate "~25% (403 bot protection, client-side rendering, PDF text extraction, guessed URLs)"
   :implication "A verbatim-level wave costs roughly 4 successful fetches per jurisdiction plus a quarter again in failures. Wave 4 (:located organizations only) is roughly 2. These are the numbers to size a batch against -- not a completion date, which this plan deliberately does not give."})

(def source-access-hazards
  "Access methods that FAILED in the seeding session, recorded so nobody
  re-burns fetches discovering them again. Extend this whenever a source
  resists a method; it is the plan's most reusable artefact."
  [{:host "mba.org" :method :web-fetch :result :http-403
    :note "Trade-association sites commonly sit behind bot protection. The official-page search index still returns quotable text; provenance must then say so explicitly."}
   {:host "ukfinance.org.uk" :method :web-fetch :result :http-403}
   {:host "pfandbrief.de" :method :web-fetch :result :http-404
    :note "Guessed path. Locate the real path via search before fetching."}
   {:host "handbook.fca.org.uk" :method :web-fetch :result :client-side-rendered
    :note "Rule text is rendered client-side and never appears in the fetched HTML. The FCA Handbook is not directly quotable this way -- use the official-page search index and say so, or do not quote."}
   {:host "hypo.org" :method :pdf-text-extract :result :failed
    :note "4 MB image-heavy PDF: the URL resolves and downloads but yields no text. A resolving URL is NOT a read source."}
   {:host "kfw.de" :method :pdf-read :result :ok
    :note "Text-native PDF read page by page. This is the method that works for German Merkblätter and is worth trying first for any official programme leaflet."}
   {:host "flat35.com" :method :web-fetch :result :redirect-then-ok}
   {:host "service-public.fr" :method :web-fetch :result :redirect-to-gouv-fr
    :note "Cross-host redirect; refetch at the .gouv.fr host."}
   {:host "nhg.nl" :method :web-fetch :result :404-on-guessed-paths
    :note "Content lives on paths that are not guessable from the nav; the site search index resolved them."}])

(def open-decisions
  "Decisions this plan deliberately does not make on its own, with the reason
  each one needs an answer before the wave it blocks."
  [{:id :wave-3-ordering-source
    :question "Which sourced dataset orders wave 3 by residential mortgage market size?"
    :blocks 3
    :why "The wave claims to be ordered by market size. Without a citable source that ordering is a guess wearing a rationale, and the honest fallback (population, from UN WPP) should then be stated as such rather than described as market size."}
   {:id :sub-national-key-convention
    :question "Does this catalog adopt cloud-itonami-isic-6810's `USA-CA` / `AUS-NSW` / `CAN-ON` exemplar keys, or model federal and sub-national layers as separate fields?"
    :blocks 1
    :why "Recording a state exemplar under a bare `USA` key silently promotes one state to national authority. 6810 already chose exemplar keys; diverging from it breaks the cross-repo join this catalog exists to support."}
   {:id :automation-boundary
    :question "Should a scheduled fleet routine consume `next-batch`, or does every entry stay hand-driven?"
    :blocks 4
    :why "Wave 4 is ~140 jurisdictions at 2 calls each; hand-driving it is unlikely to finish. The test suite already makes fabrication fail closed (no entry without a fetched provenance URL and a retrieval date), which is what makes automation defensible here -- but a routine that cannot fetch will otherwise write plausible-looking regulators, so the boundary must be an explicit decision rather than a default."}])
