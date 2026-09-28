(ns com.trustblocks.clause-parity-test
  "The clause vocabulary gives the same answer on the JVM and in
  ClojureScript: every case in clause_parity.edn -- the date and decimal host
  functions over generated inputs, the number rules, and the templates' own
  clauses -- run here against the answer recorded from the JVM.

  The same file runs as a JVM test (bin/clj -M:run test) and as a Node test
  of the compiled ClojureScript (bin/cljs-test). Regenerate the fixture,
  from the JVM, with dev/clause_parity_fixtures.clj -- only when the
  vocabulary is meant to change."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [com.trustblocks.clause :as clause]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])))

(def fixture
  (edn/read-string #?(:clj  (slurp (io/resource "com/trustblocks/clause_parity.edn"))
                      :cljs (rc/inline "com/trustblocks/clause_parity.edn"))))

(defn normalize
  "Numbers as doubles: a JSON number is a long or a double on the JVM and
  one kind of number in JavaScript, so the answers are compared by value."
  [x]
  (walk/postwalk #(if (number? %) (double %) %) x))

(defn run-case
  "One case's answer: {:ok result} or {:error message}."
  [ctx {:keys [src data request state now]}]
  (try
    {:ok (normalize (clause/evaluate ctx src {:data data :request request :state state :now now}))}
    (catch #?(:clj Exception :cljs :default) e
      {:error (ex-message e)})))

(deftest every-case-answers-as-on-the-jvm
  (let [ctx (clause/context)]
    (is (< 1000 (count fixture)))
    (doseq [{:keys [name expect] :as c} fixture]
      (testing name
        (is (= (normalize expect) (run-case ctx c)))))))
