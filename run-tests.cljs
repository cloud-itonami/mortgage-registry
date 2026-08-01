#!/usr/bin/env nbb
;; run-tests.cljs — nbb test entry point (script host policy: nbb only,
;; ADR-2607173000; no bb.edn, no shell script).
;;
;;   nbb --classpath src:test run-tests.cljs

(ns run-tests
  (:require [cljs.test :as t]
            [mortgage.facts-test]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'mortgage.facts-test)
