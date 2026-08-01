#!/usr/bin/env nbb
;; scripts/emit_tx.cljs — project `mortgage.facts/catalog` into DataScript
;; tx-data at data/datascript-tx.edn. GENERATED FILE: never hand-edit
;; data/datascript-tx.edn, edit src/mortgage/facts.cljc and re-run:
;;
;;   nbb --classpath src scripts/emit_tx.cljs
;;
;; nbb (not bb): script host policy is nbb-only, ADR-2607173000.

(ns emit-tx
  (:require [clojure.string :as str]
            [mortgage.facts :as facts]
            ["fs" :as fs]))

(defn jurisdiction-datoms [[iso3 {:keys [name procedure verification]}]]
  (cond-> {:mortgage.jurisdiction/iso3 iso3
           :mortgage.jurisdiction/name name
           :source/dataset "mortgage-registry"}
    (:owner-authority procedure)
    (assoc :mortgage.jurisdiction/owner-authority (:owner-authority procedure))
    (:legal-basis procedure)
    (assoc :mortgage.jurisdiction/legal-basis (:legal-basis procedure))
    (:security-instrument procedure)
    (assoc :mortgage.jurisdiction/security-instrument (:security-instrument procedure))
    (:provenance procedure)
    (assoc :mortgage.jurisdiction/provenance (:provenance procedure))
    (seq (:steps procedure))
    (assoc :mortgage.jurisdiction/step (vec (:steps procedure)))
    (seq (:not-verified verification))
    (assoc :mortgage.jurisdiction/not-verified (vec (:not-verified verification)))))

(defn support-datoms [[iso3 {:keys [support]}]]
  (for [{:keys [id name kind operator status provenance retrieved-at eligibility-signals]} support]
    (cond-> {:mortgage.support/id id
             :mortgage.support/jurisdiction iso3
             :mortgage.support/name name
             :mortgage.support/kind kind
             :mortgage.support/operator operator
             :mortgage.support/provenance provenance
             :mortgage.support/retrieved-at retrieved-at
             :source/dataset "mortgage-registry"}
      status (assoc :mortgage.support/status status)
      (seq eligibility-signals)
      (assoc :mortgage.support/eligibility-signal (vec eligibility-signals)))))

(defn org-datoms [[iso3 {:keys [organizations]}]]
  (for [{:keys [id name-en name-local role url isic country]} organizations]
    {:mortgage.org/id (str iso3 "/" id)
     :mortgage.org/name-en name-en
     :mortgage.org/name-local name-local
     :mortgage.org/role role
     :mortgage.org/url url
     :mortgage.org/isic isic
     :mortgage.org/country country
     :source/dataset "mortgage-registry"}))

(def tx-data
  (vec (concat (map jurisdiction-datoms facts/catalog)
               (mapcat support-datoms facts/catalog)
               (mapcat org-datoms facts/catalog))))

(def header
  (str ";; data/datascript-tx.edn — GENERATED from src/mortgage/facts.cljc by\n"
       ";; scripts/emit_tx.cljs. Do not hand-edit: edit the catalog and re-run\n"
       ";;   nbb --classpath src scripts/emit_tx.cljs\n"
       ";;\n"
       ";; " (count facts/catalog) " jurisdiction(s), "
       (count (mapcat support-datoms facts/catalog)) " support programme(s), "
       (count (mapcat org-datoms facts/catalog)) " organization(s).\n"
       ";; Seeded coverage, NOT complete coverage — every jurisdiction entity\n"
       ";; carries its own :mortgage.jurisdiction/not-verified list.\n\n"))

(fs/writeFileSync "data/datascript-tx.edn"
                  (str header (str/join "\n" (map pr-str tx-data)) "\n"))

(println "wrote data/datascript-tx.edn:" (count tx-data) "entities")
