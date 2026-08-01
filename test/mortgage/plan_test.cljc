(ns mortgage.plan-test
  "Invariants for the coverage plan. The plan's job is to stop coverage from
  being overstated, so these tests mostly check that it cannot flatter itself:
  status is computed from evidence, waves justify their own ordering, and the
  shortcuts state what they do NOT buy."
  (:require [clojure.string :as str]
            [mortgage.plan :as plan]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(deftest status-is-computed-from-evidence-not-declared
  (testing "a plane is :verbatim only where the evidence that defines it exists"
    (is (= :verbatim (plan/plane-status "JPN" :procedure))
        "JPN has procedure steps")
    (is (= :located (plan/plane-status "GBR" :procedure))
        "GBR cites the FCA Handbook but has no steps -- MCOB rule text was never extracted")
    (is (= :located (plan/plane-status "FRA" :procedure))
        "FRA seeded PTZ only; the general French mortgage procedure was never fetched")
    (is (= :verbatim (plan/plane-status "NLD" :support))
        "NHG carries eligibility signals")))

(deftest an-unseeded-jurisdiction-is-absent-everywhere
  (doseq [p plan/planes]
    (is (= :absent (plan/plane-status "BRA" p)))
    (is (= :absent (plan/plane-status "XXX" p)))))

(deftest a-jurisdiction-at-its-wave-target-leaves-the-queue
  (let [q (plan/queue ["JPN" "GBR" "BRA"])
        by-iso (into {} (map (juxt :iso3 identity) q))]
    (is (nil? (by-iso "JPN")) "JPN meets its wave-0 target on every plane")
    (is (= :procedure (:next (by-iso "GBR"))) "GBR still owes a verbatim procedure")
    (is (= :organizations (:next (by-iso "BRA"))) "an unseeded jurisdiction starts at organizations")))

(deftest queue-is-ordered-by-wave-then-code
  (let [q (plan/queue ["ZWE" "IDN" "AUT" "GBR"])]
    (is (= ["GBR" "IDN" "AUT" "ZWE"] (mapv :iso3 q))
        "wave 0 remainder, then wave 1, then wave 2, then the tail")
    (is (apply <= (map :wave q)))))

(deftest next-batch-is-bounded
  (is (= 3 (count (plan/next-batch ["BRA" "ARG" "CHL" "PER" "COL"] 3)))))

(deftest every-wave-justifies-its-own-position
  (doseq [{:keys [wave name rationale target members]} plan/waves]
    (testing (str "wave " wave " " name)
      (is (seq rationale) "a wave without a rationale gets reordered by whoever is bored")
      (is (< 80 (count rationale)) "the rationale must actually argue, not label")
      (is (map? target))
      (is (= #{:organizations :procedure :support} (set (keys target))))
      (is (or (vector? members) (= :remainder members))))))

(deftest waves-do-not-overlap
  (let [listed (mapcat #(when (vector? (:members %)) (:members %)) plan/waves)]
    (is (= (count listed) (count (set listed)))
        "a jurisdiction in two waves would be worked twice and counted twice")))

(deftest the-seeded-six-are-exactly-wave-zero
  (is (= #{"JPN" "USA" "GBR" "DEU" "FRA" "NLD"}
         (set (:members (first plan/waves))))))

(deftest shared-instruments-state-what-they-do-not-buy
  (doseq [{:keys [id reduces does-not-reduce status]} plan/shared-instruments]
    (testing (str id)
      (is (seq reduces))
      (is (seq does-not-reduce)
          "a shortcut that does not say what it fails to cover is how 27 fabricated entries get justified by one directive")
      (is (contains? #{:not-yet-read :read :not-an-instrument} status)))))

(deftest mcd-must-be-read-before-wave-two-is-claimed-cheap
  (let [mcd (first (filter #(= :mcd-2014-17-eu (:id %)) plan/shared-instruments))]
    (is (some? mcd))
    (is (str/starts-with? (:source mcd) "https://eur-lex.europa.eu/")
        "the anchor must be the directive itself, not a summary")
    (is (= 2 (:members-wave mcd)))))

(deftest open-decisions-name-what-they-block
  (is (seq plan/open-decisions))
  (doseq [{:keys [id question blocks why]} plan/open-decisions]
    (testing (str id)
      (is (str/ends-with? question "?"))
      (is (integer? blocks))
      (is (seq why) "a decision without a stated cost of deferring it never gets made"))))

(deftest cost-is-measured-not-estimated
  (is (= 6 (:jurisdictions-seeded plan/throughput)))
  (is (pos? (:web-calls-spent plan/throughput)))
  (is (str/includes? (:implication plan/throughput) "not a completion date")))

(deftest access-hazards-are-recorded-so-they-are-not-rediscovered
  (is (<= 5 (count plan/source-access-hazards)))
  (doseq [h plan/source-access-hazards]
    (is (seq (:host h)))
    (is (keyword? (:method h)))
    (is (keyword? (:result h))))
  (is (some #(= :ok (:result %)) plan/source-access-hazards)
      "record what WORKED too, or the ledger only teaches despair"))
