(ns com.trustblocks.xtdb.defaults-test
  "Default data: values a model's fields start with, versioned apart from the
  model, layered under a contract's own data, and snapshotted into a release."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.templatemark.parse :as parse]
            [com.trustblocks.xtdb.defaults :as defaults]
            [com.trustblocks.xtdb.model-repository :as repo]
            [com.trustblocks.xtdb.release :as rel]
            [com.trustblocks.xtdb.template-tree :as tt]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(def m "com.trustblocks.municipal.agreement@1.0.0")
(def applies {:model m :type "MunicipalAgreement"})
(defn- inst [s] (java.time.Instant/parse s))
(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(def town {"stateCode" "NC" "stateName" "North Carolina" "countyName" "Rowan"
           "municipalityName" "China Grove" "municipalityType" "Town"
           "governingBodyName" "Town Council"})

(defn- node-with-models []
  (let [node (xt.node/start-node)]
    (when-let [r (io/resource "manager-employment-contract/model/agreement.cto")]
      (repo/import-dir! node (.getParentFile (io/file r))))
    node))

(defn- have-models? [] (some? (io/resource "manager-employment-contract/model/agreement.cto")))

(deftest defaults-are-held-to-the-type-they-are-for
  (when (have-models?)
    (with-open [node (node-with-models)]
      (let [v1 (defaults/put-version! node #:defaults{:id "town" :applies applies :data town} :by "clerk" :note "first")]
        (is (= 1 (:defaults/version v1)))
        (is (re-matches #"[0-9a-f]{64}" (:defaults/hash v1)))
        (is (= v1 (defaults/get-version node "town" 1)))
        (testing "a name the type does not have, and a value of the wrong kind, are refused"
          (is (= :invalid-defaults (tag #(defaults/put-version! node #:defaults{:id "bad" :applies applies :data {"townName" "x"}}))))
          (is (= :invalid-defaults (tag #(defaults/put-version! node #:defaults{:id "bad" :applies applies :data {"stateCode" "ZZ"}}))))
          (is (= :invalid-defaults (tag #(defaults/put-version! node #:defaults{:id "bad" :applies applies :data {"countyName" 7}}))))
          (is (nil? (defaults/latest node "bad")))
          (let [e (try (defaults/put-version! node #:defaults{:id "bad" :applies applies :data {"townName" "x" "countyName" 7}})
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= #{:unknown-property :bad-value} (set (map :rule (:problems (ex-data e))))))))
        (testing "a model that is not stored, or a type it does not have"
          (is (= :model-missing (tag #(defaults/put-version! node #:defaults{:id "x" :applies {:model "no.such@1.0.0" :type "A"} :data {}}))))
          (is (= :invalid-defaults (tag #(defaults/put-version! node #:defaults{:id "x" :applies {:model m :type "Nope"} :data {}})))))))))

(deftest every-change-is-a-new-version-with-its-own-effective-date
  (when (have-models?)
    (with-open [node (node-with-models)]
      (defaults/put-version! node #:defaults{:id "town" :applies applies :data town} :valid-from (inst "2025-01-01T00:00:00Z"))
      (is (= 1 (:defaults/version (defaults/put-version! node #:defaults{:id "town" :applies applies :data town})))
          "saving the same values is not a change")
      (let [v2 (defaults/put-version! node #:defaults{:id "town" :applies applies :data (assoc town "governingBodyName" "Board of Aldermen")}
                                      :valid-from (inst "2026-01-01T00:00:00Z") :note "renamed")]
        (is (= 2 (:defaults/version v2)))
        (is (= (:defaults/hash (defaults/get-version node "town" 1)) (:defaults/parent-hash v2)))
        (is (= ["Town Council" "Board of Aldermen"]
               (map #(get-in (defaults/get-version node "town" %) [:defaults/data "governingBodyName"]) [1 2])))
        (testing "which applied when"
          (is (= 1 (:defaults/version (defaults/in-force node "town" :at (inst "2025-06-01T00:00:00Z")))))
          (is (= 2 (:defaults/version (defaults/in-force node "town" :at (inst "2026-06-01T00:00:00Z")))))
          (is (nil? (defaults/in-force node "town" :at (inst "2024-06-01T00:00:00Z")))))
        (is (= ["town"] (defaults/defaults-ids node)))))))

(deftest defaults-sit-beneath-a-contracts-own-data
  (when (have-models?)
    (with-open [node (node-with-models)]
      (defaults/put-version! node #:defaults{:id "town" :applies applies :data town})
      (let [{:keys [data applied]} (defaults/prefill node [["town" :latest]]
                                                     {"municipalityName" "Other Town" "contractNumber" "STR-27"})]
        (is (= "Other Town" (data "municipalityName")) "the contract's own value wins")
        (is (= "Rowan" (data "countyName")))
        (is (= "STR-27" (data "contractNumber")) "its own fields are untouched")
        (is (= [{:defaults "town" :version 1 :fields (disj (set (keys town)) "municipalityName")}]
               (map #(select-keys % [:defaults :version :fields]) applied))
            "the fields the defaults actually supplied"))
      (testing "later layers override earlier ones"
        (defaults/put-version! node #:defaults{:id "water-dept" :applies applies :data {"governingBodyName" "Water Board"}})
        (let [{:keys [data applied]} (defaults/prefill node [["town" 1] ["water-dept" :latest]] {})]
          (is (= "Water Board" (data "governingBodyName")))
          (is (not (contains? (:fields (first applied)) "governingBodyName")))
          (is (= #{"governingBodyName"} (:fields (second applied))))))
      (is (= :no-such-defaults (tag #(defaults/prefill node [["none" :latest]] {}))))
      (is (= :no-such-defaults (tag #(defaults/prefill node [["town" 9]] {})))))))

(deftest a-release-keeps-the-defaults-it-was-drafted-from
  (when (have-models?)
    (with-open [node (node-with-models)]
      (defaults/put-version! node #:defaults{:id "town" :applies applies :data town})
      (tt/save! node "street" (parse/parse "Contract between the {{municipalityType}} and a contractor."))
      (let [plain (rel/release! node "street")
            r1    (rel/release! node "street" :defaults [["town" :latest]])]
        (is (not= (:sha plain) (:sha r1)) "default data is part of what was released")
        (is (= [{:defaults "town" :version 1}] (map #(select-keys % [:defaults :version]) (:defaults r1))))
        (is (= town (:data (first (:defaults r1)))) "the release holds the values themselves")
        (is (:ok? (rel/verify node (:sha r1))))
        (testing "changing the Town's defaults later does not touch it"
          (defaults/put-version! node #:defaults{:id "town" :applies applies :data (assoc town "countyName" "Cabarrus")})
          (is (= "Rowan" (get-in (rel/get-release node (:sha r1)) [:defaults 0 :data "countyName"])))
          (is (= (:sha r1) (:sha (rel/release! node "street" :defaults [["town" 1]]))) "the same pinned values are the same release")
          (is (not= (:sha r1) (:sha (rel/release! node "street" :defaults [["town" :latest]])))
              "while the new values are a different release")
          (is (:ok? (rel/verify node (:sha r1))) "and the old release still verifies"))
        (testing "altering what was stored is noticed"
          (xt/execute-tx node [[:patch-docs :defaults-version {:xt/id "town@1"
                                                               :data (pr-str (assoc town "countyName" "Elsewhere"))}]])
          (let [{:keys [ok? problems]} (rel/verify node (:sha r1))]
            (is (not ok?))
            (is (= #{:defaults-corrupt} (set (map :rule problems))))))
        (testing "altering the release's own copy is noticed"
          (xt/execute-tx node [[:patch-docs :defaults-version {:xt/id "town@1" :data (pr-str town)}]])
          (xt/execute-tx node [[:patch-docs :release {:xt/id (str "sha256:" (:sha r1))
                                                      :defaults (pr-str [(assoc (first (:defaults r1)) :data (assoc town "countyName" "X"))])}]])
          (is (contains? (set (map :rule (:problems (rel/verify node (:sha r1))))) :defaults-altered)))))))
