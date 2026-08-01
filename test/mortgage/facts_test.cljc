(ns mortgage.facts-test
  "Honesty invariants for the catalog. These tests do NOT check that the seeded
  figures are correct -- no test can do that, only a fetch from the official
  source can. What they check is that the catalog cannot LOOK more complete
  than it is: nothing is asserted without a provenance URL and a retrieval
  date, every jurisdiction publishes its own not-verified list, and an
  unseeded jurisdiction resolves to nil rather than to a permissive default."
  (:require [clojure.string :as str]
            [mortgage.facts :as facts]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(defn- http-url? [s]
  (and (string? s) (str/starts-with? s "https://")))

(deftest unseeded-jurisdiction-is-nil-not-default
  (testing "an uncovered jurisdiction has NO spec-basis, never a default"
    (is (nil? (facts/spec-basis "XXX")))
    (is (nil? (facts/spec-basis "")))
    (is (= [] (facts/support-programmes "XXX")))
    (is (= [] (facts/organizations "XXX")))))

(deftest every-jurisdiction-cites-an-official-source
  (doseq [[iso3 entry] facts/catalog]
    (testing (str iso3 " procedure plane")
      (is (http-url? (get-in entry [:procedure :provenance]))
          (str iso3 " procedure must cite an https provenance URL"))
      (is (seq (get-in entry [:procedure :owner-authority]))
          (str iso3 " procedure must name the owning authority")))))

(deftest every-support-programme-is-sourced-and-dated
  (doseq [[iso3 entry] facts/catalog
          programme (:support entry)]
    (testing (str iso3 " / " (:id programme))
      (is (http-url? (:provenance programme))
          "a support programme must cite the page it was read from")
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (str (:retrieved-at programme)))
          "a support programme must record WHEN it was read")
      (is (keyword? (:kind programme)) "programme kind must be a keyword")
      (is (seq (:operator programme))
          "a support programme must name whoever actually operates it"))))

(deftest every-jurisdiction-publishes-its-own-gaps
  (doseq [[iso3 entry] facts/catalog]
    (testing (str iso3 " verification block")
      (is (seq (get-in entry [:verification :fetched-this-session]))
          (str iso3 " must record what was actually fetched"))
      (is (seq (get-in entry [:verification :not-verified]))
          (str iso3 " must record what was NOT verified -- an empty gap list "
               "would claim completeness no seeded catalog has")))))

(deftest organizations-carry-the-assoc-join-key
  (doseq [org (facts/all-organizations)]
    (testing (:id org)
      (is (seq (:isic org)) "org must carry :isic (join key to cloud-itonami-assoc-*)")
      (is (re-matches #"[A-Z]{3}" (str (:country org)))
          "org must carry an iso3 :country (join key to cloud-itonami-assoc-*)")
      (is (http-url? (:url org)) "org must cite its official URL")
      (is (keyword? (:role org)) "org role must be a keyword"))))

(deftest coverage-never-overstates
  (let [report (facts/coverage ["JPN" "USA" "XXX" "ZZZ"])]
    (is (= ["JPN" "USA"] (:covered-jurisdictions report)))
    (is (= ["XXX" "ZZZ"] (:missing-jurisdictions report)))
    (is (str/includes? (:note report) "never")
        "the coverage note must keep the never-fabricate instruction attached"))
  (testing "coverage of an empty request is empty, not universal"
    (is (= [] (:covered-jurisdictions (facts/coverage []))))))

(deftest unverified-claims-are-enumerable
  (let [claims (facts/unverified-claims)]
    (is (seq claims) "the catalog must be able to list its own gaps")
    (is (every? #(and (:jurisdiction %) (:unverified %)) claims))
    (is (= (set (map :jurisdiction claims)) (set (keys facts/catalog)))
        "every seeded jurisdiction contributes at least one known gap")))

(deftest summary-covers-every-jurisdiction
  (is (= (count facts/catalog) (count (facts/summary)))))
