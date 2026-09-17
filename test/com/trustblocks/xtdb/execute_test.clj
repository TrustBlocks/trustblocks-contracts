(ns com.trustblocks.xtdb.execute-test
  "Covers the at-most-once guarantee on trigger!.

  Running a trigger twice is not a harmless repeat -- the clause below counts
  visits and adds up money, so a second run of the same visit report pays the
  contractor twice and nothing downstream would notice. The guarantee is that
  a request id, once used on a contract, is refused.

  Uses docs/examples/snow-removal.cto because counting is the shape of
  contract where a double execution is visible in the answer rather than
  merely recorded twice."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def ^:private fqn "com.trustblocks.snow@1.0.0.SnowRemoval")

(defn- contract [id]
  {"$class"        fqn
   "contractId"    id
   "$identifier"   id
   "contractor"    "Glenwood Plowing LLC"
   "route"         "Ward 3 arterials"
   "inchesTrigger" 2.0
   "ratePerVisit"  {"$class"   "com.trustblocks.snow@1.0.0.Money"
                    "amount"   480.0
                    "currency" "USD"}})

(def ^:private clause
  "(let [rate  (get-in data [\"ratePerVisit\" \"amount\"])
         paid  (if state (get state \"visitsPaid\") 0)
         total (if state (get-in state [\"totalPaid\" \"amount\"]) 0.0)]
     {:response {\"$class\" \"com.trustblocks.snow@1.0.0.VisitOutcome\"
                 \"outcomeId\" (get request \"reportId\")
                 \"$identifier\" (get request \"reportId\")
                 \"$timestamp\" now
                 \"outcome\" \"PAYABLE\"
                 \"amountDue\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                              \"amount\" rate \"currency\" \"USD\"}}
      :state {\"$class\" \"com.trustblocks.snow@1.0.0.PaymentState\"
              \"contractId\" (get data \"contractId\")
              \"$identifier\" (get data \"contractId\")
              \"visitsPaid\" (+ paid 1)
              \"totalPaid\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                           \"amount\" (+ total rate) \"currency\" \"USD\"}}})")

(def ^:private visited-at "2026-01-15T06:00:00Z")

(defn- visit [report-id]
  {"$class"       "com.trustblocks.snow@1.0.0.VisitReport"
   "reportId"     report-id
   "$identifier"  report-id
   "$timestamp"   visited-at
   "visitedAt"    visited-at
   "inchesFallen" 3.5})

(defn- populate!
  "A node holding the snow model, the clause, and one contract per id."
  [node ids]
  (store/put-model! node "docs/examples/snow-removal.cto")
  (execute/put-clause! node fqn clause)
  (doseq [id ids]
    (store/put-instance! node nil (contract id))))

(defn- visits-paid [node id]
  (get (execute/current-state node id) "visitsPaid"))

(defn- error-tag [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(deftest a-used-request-id-is-refused
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-a" "snow-b"])

    (testing "the first trigger runs"
      (is (= "PAYABLE" (get (:response (execute/trigger! node "snow-a" (visit "v-1")
                                                         visited-at :request-id "r-1"))
                            "outcome")))
      (is (= 1 (visits-paid node "snow-a"))))

    (testing "the same request id is refused, and nothing is counted twice"
      (is (= :duplicate-request
             (error-tag #(execute/trigger! node "snow-a" (visit "v-1")
                                           visited-at :request-id "r-1"))))
      (is (= 1 (visits-paid node "snow-a")))
      (is (= 1 (count (execute/history node "snow-a")))))

    (testing "a different request id runs, even with identical content"
      (execute/trigger! node "snow-a" (visit "v-1") visited-at :request-id "r-2")
      (is (= 2 (visits-paid node "snow-a"))))

    (testing "request ids are scoped to their contract"
      (execute/trigger! node "snow-b" (visit "v-1") visited-at :request-id "r-1")
      (is (= 1 (visits-paid node "snow-b"))))))

(deftest concurrent-copies-of-one-request-execute-once
  ;; The query in execution-for-request cannot carry this on its own: copies
  ;; arriving together all read an empty table before any of them writes.
  ;; What makes it hold is the ASSERT inside the transaction -- see
  ;; execute/assert-unused. Whether the threads below actually collide is up
  ;; to the scheduler, so this never fails spuriously; it only ever catches a
  ;; guard that isn't there.
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-c"])
    (let [attempts (->> (range 8)
                        (mapv (fn [_]
                                (future
                                  (error-tag
                                   #(execute/trigger! node "snow-c" (visit "v-1")
                                                      visited-at :request-id "r-1")))))
                        (mapv deref))]
      (is (= 1 (count (remove some? attempts)))
          (str "exactly one attempt should have executed; got " (pr-str attempts)))
      (is (every? #{:duplicate-request} (filter some? attempts)))
      (is (= 1 (visits-paid node "snow-c")))
      (is (= 1 (count (execute/history node "snow-c")))))))

(deftest a-trigger-records-who-submitted-it
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-d"])
    (execute/trigger! node "snow-d" (visit "v-1") visited-at
                      :request-id "r-1" :by "clerk@example.gov")
    (let [[execution] (execute/history node "snow-d")]
      (is (= "clerk@example.gov" (:by execution)))
      (is (= "r-1" (:request-id execution))))))
