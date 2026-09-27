(ns com.trustblocks.app.operations-test
  "Direct handler tests for the signed-in operational pages. The UI is server-
  rendered Hiccup, so inspecting the returned HTML catches missing routes,
  missing modules, and action labels without introducing a browser harness."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.trustblocks.app.authorizations :as authorizations]
            [com.trustblocks.app.clauses :as clauses]
            [com.trustblocks.app.contracts :as contracts]
            [com.trustblocks.xtdb.certification :as certification]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.time Instant]))

(def ^:private fqn "com.trustblocks.snow@1.0.0.SnowRemoval")
(def ^:private contract-id "snow-ui")
(def ^:private clause "{:response request}")

(def ^:private instance
  {"$class" fqn
   "$identifier" contract-id
   "contractId" contract-id
   "contractor" "Glenwood Plowing LLC"
   "route" "Ward 3 arterials"
   "inchesTrigger" 2.0
   "ratePerVisit" {"$class" "com.trustblocks.snow@1.0.0.Money"
                   "amount" 480.0
                   "currency" "USD"}})

(defn- populate! [node]
  (store/put-model! node "docs/examples/snow-removal.cto")
  (store/put-model! node "model/identity.cto")
  (store/put-instance! node nil instance)
  (execute/put-clause! node fqn clause)
  (let [town (identity/provision-town! node {:slug "china-grove" :name "China Grove"})
        clerk (identity/provision-officeholder!
               node {:email "pmills@chinagrovenc.gov"
                     :name "Pamela Mills"
                     :office "Town Clerk"})]
    (certification/grant-office!
     node (:xt/id town)
     {:officeholder-did (:xt/id clerk)
      :name "Pamela Mills"
      :office "Town Clerk"
      :authority ["CERTIFY_MINUTES"]
      :valid-from (Instant/parse "2025-01-01T00:00:00Z")
      :valid-until (Instant/parse "2029-01-01T00:00:00Z")})))

(deftest operational-pages-render-the-navigation-and-records
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [ctx {:biff.xtdb/node node :session {:uid (random-uuid)}}
          contracts-html (:body (contracts/list-page ctx))
          clauses-html (:body (clauses/list-page ctx))
          authorizations-html (:body (authorizations/list-page ctx))
          document-html (:body (contracts/document-page
                                (assoc ctx :uri (str "/contracts/" contract-id "/document"))))]
      (is (str/includes? contracts-html "Trustblocks"))
      (is (str/includes? contracts-html "Municipal Contracts"))
      (is (str/includes? contracts-html "View Contract"))
      (is (str/includes? contracts-html "Template Playground"))
      (is (str/includes? contracts-html "Concerto Viewer"))
      (is (str/includes? clauses-html "Installed Logic"))
      (is (str/includes? clauses-html fqn))
      (is (str/includes? authorizations-html "Pamela Mills"))
      (is (str/includes? authorizations-html "pmills@chinagrovenc.gov"))
      (is (str/includes? authorizations-html "Certify minutes"))
      ;; A contract with no template text has nothing to export.
      (is (str/includes? document-html "No contract template text is on file"))
      (is (not (str/includes? document-html "Export to Word"))))))
