(ns com.trustblocks.app.trigger-test
  "Exercises the trigger handler directly, as com.trustblocks.app.certify-test
  does -- a plain ctx map and a function call, no HTTP server. Covers the
  status each refusal reaches a caller with, starting with the one most
  likely to surprise one: a report dated before a state change already
  recorded."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.auth :as auth]
            [com.trustblocks.app.trigger :as trigger]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def ^:private fqn "com.trustblocks.snow@1.0.0.SnowRemoval")
(def ^:private contract-id "snow-trigger")

(def ^:private clause
  "(let [paid (if state (get state \"visitsPaid\") 0)]
     {:response {\"$class\" \"com.trustblocks.snow@1.0.0.VisitOutcome\"
                 \"outcomeId\" (get request \"reportId\")
                 \"$identifier\" (get request \"reportId\")
                 \"$timestamp\" now
                 \"outcome\" \"PAYABLE\"
                 \"amountDue\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                              \"amount\" 480.0 \"currency\" \"USD\"}}
      :state {\"$class\" \"com.trustblocks.snow@1.0.0.PaymentState\"
              \"contractId\" (get data \"contractId\")
              \"$identifier\" (get data \"contractId\")
              \"visitsPaid\" (+ paid 1)
              \"totalPaid\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                           \"amount\" (* 480.0 (+ paid 1)) \"currency\" \"USD\"}}})")

(defn- populate! [node]
  (store/put-model! node "docs/examples/snow-removal.cto")
  (execute/put-clause! node fqn clause)
  (store/put-instance! node nil {"$class"        fqn
                                 "contractId"    contract-id
                                 "$identifier"   contract-id
                                 "contractor"    "Glenwood Plowing LLC"
                                 "route"         "Ward 3 arterials"
                                 "inchesTrigger" 2.0
                                 "ratePerVisit"  {"$class"   "com.trustblocks.snow@1.0.0.Money"
                                                  "amount"   480.0
                                                  "currency" "USD"}}))

(defn- post!
  "The handler's reply to a visit report taking effect at `at`, with its
  JSON body parsed. body-params are keyword-keyed, as muuntaja leaves them."
  [node uid request-id at]
  (let [{:keys [status body]}
        (trigger/trigger {:biff.xtdb/node node
                          :uri            (str "/contracts/" contract-id "/trigger")
                          :session        {:uid uid}
                          :body-params    {:requestId request-id
                                           :at        at
                                           :request   {:$class       "com.trustblocks.snow@1.0.0.VisitReport"
                                                       :reportId     request-id
                                                       :$identifier  request-id
                                                       :$timestamp   at
                                                       :visitedAt    at
                                                       :inchesFallen 3.5}}})]
    {:status status :body (json/parse-string body)}))

(deftest a-late-report-is-refused-with-409
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [uid (auth/create-user! {:biff.xtdb/node node} {:email "ops@example.gov"})]
      (testing "an on-time report runs"
        (is (= 200 (:status (post! node uid "v-2" "2026-02-20T06:00:00Z")))))
      (testing "one dated before it is refused, naming the later state change"
        (let [{:keys [status body]} (post! node uid "v-1" "2026-01-15T06:00:00Z")]
          (is (= 409 status))
          (is (= "out-of-order" (get body "error")))))
      (testing "a repeat of the on-time report is still a duplicate, not out of order"
        (let [{:keys [status body]} (post! node uid "v-2" "2026-02-20T06:00:00Z")]
          (is (= 409 status))
          (is (= "duplicate-request" (get body "error"))))))))

(deftest a-request-of-an-unknown-type-is-refused-with-400
  ;; No model declares it -- the caller's mistake, not the platform's.
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [uid (auth/create-user! {:biff.xtdb/node node} {:email "ops@example.gov"})

          {:keys [status body]}
          (trigger/trigger {:biff.xtdb/node node
                            :uri            (str "/contracts/" contract-id "/trigger")
                            :session        {:uid uid}
                            :body-params    {:requestId "r-1" :at "2026-02-20T06:00:00Z"
                                             :request   {:$class "com.trustblocks.snow@1.0.0.NoSuchRequest"}}})]
      (is (= 400 status))
      (is (= "invalid-request" (get (json/parse-string body) "error"))))))
