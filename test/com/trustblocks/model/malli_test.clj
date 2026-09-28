(ns com.trustblocks.model.malli-test
  "The Malli tab's model: every contract type the app runs, compiled from
  Concerto to Malli, validates its own sample, generates instances that
  validate, and exports to JSON Schema and DOT."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.contracts :as contracts]
            [com.trustblocks.model.malli :as model.malli]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def ^:private contract-types
  {"com.trustblocks.municipal.employment@1.0.0.ManagerEmployment" "manager-employment-contract"
   "com.trustblocks.municipal.construction@1.0.0.StreetResurfacingContract" "street-resurfacing/contract"
   "com.trustblocks.municipal.construction.payapplication@1.0.0.ContractorPayRequest" "street-resurfacing/pay-application"
   "com.trustblocks.snow@1.0.0.SnowRemoval" nil})

(defn- load! [node]
  (doseq [t (remove nil? (vals contract-types))]
    (archive/ingest-dir! node (library/dir t)))
  (store/put-model! node "docs/examples/snow-removal.cto"))

(deftest every-contract-type-as-malli
  (with-open [node (xt.node/start-node)]
    (load! node)
    (let [reg (store/registry-from-xtdb node)]
      (doseq [[fqn template] contract-types
              :let [schema (model.malli/schema reg fqn)]]
        (testing fqn
          (when template
            (testing "its template's own sample conforms"
              (is (nil? (model.malli/explain schema (json/parse-string (library/sample template)))))))
          (testing "a generated instance conforms -- as JSON, the way a contract arrives"
            (doseq [seed [1 2 3]]
              (is (nil? (model.malli/explain schema (json/parse-string
                                                     (json/generate-string (model.malli/sample schema seed))))))))
          (testing "the same seed, the same instance"
            (is (= (model.malli/sample schema 7) (model.malli/sample schema 7))))
          (testing "JSON Schema and DOT"
            (is (map? (model.malli/json-schema schema)))
            (is (str/starts-with? (model.malli/dot reg fqn) "digraph"))
            (is (str/includes? (model.malli/dot reg fqn) (subs fqn (inc (str/last-index-of fqn "."))))))))
      (testing "the diagram draws each type as its own box, linked to the types it refers to"
        (let [d (model.malli/dot reg "com.trustblocks.municipal.construction.payapplication@1.0.0.ContractorPayRequest")]
          (is (str/includes? d "\"PayItemLine\" ["))
          (is (str/includes? d "\"ContractorPayRequest\" -> \"PayItemLine\""))
          (is (not (str/includes? d ":registry")))
          (is (not (str/includes? d ":$class")))))
      (testing "a long enum is abbreviated, not drawn in full"
        (let [d (model.malli/dot reg "com.trustblocks.municipal.construction@1.0.0.StreetResurfacingContract")]
          (is (str/includes? d "… 46 more"))
          (is (not (str/includes? d "\"WY\""))))))))

(deftest a-contract-that-does-not-conform-is-told-why
  (with-open [node (xt.node/start-node)]
    (load! node)
    (let [schema (model.malli/schema (store/registry-from-xtdb node)
                                     "com.trustblocks.municipal.construction.payapplication@1.0.0.ContractorPayRequest")
          bad    (assoc (json/parse-string (library/sample "street-resurfacing/pay-application"))
                        "applicationNumber" "one" "periodFrom" "last Tuesday")]
      (is (= {:applicationNumber ["should be an integer"]
              :periodFrom        ["should be a date-time"]}
             (model.malli/explain schema bad))))))

(deftest the-malli-tab
  (with-open [node (xt.node/start-node)]
    (load! node)
    (store/put-instance! node nil (json/parse-string (library/sample "street-resurfacing/pay-application")))
    (let [page #(:body (contracts/model-malli-page {:biff.xtdb/node node :uri "/contracts/china-grove-str-27-pay-request/model/malli"
                                                    :params (if % {:seed (str %)} {})}))
          body (page nil)]
      (doseq [section ["1. The schema" "2. This contract, validated" "3. A contract made up from the schema"
                       "4. As JSON Schema" "5. As a diagram"]]
        (is (str/includes? body section)))
      (is (str/includes? body "china-grove-str-27-pay-request conforms to the schema."))
      (is (str/includes? body ":concerto/date-time"))
      (is (str/includes? body "viz-standalone.js"))
      (is (str/includes? body "?seed=2"))
      (testing "another seed, another contract"
        (is (not= (re-find #"(?s)A contract made up.*?seed \d+" body)
                  (re-find #"(?s)A contract made up.*?seed \d+" (page 5))))))))
