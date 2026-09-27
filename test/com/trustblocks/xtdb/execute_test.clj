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
            [xtdb.api :as xt]
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

(deftest list-clauses-exposes-the-stored-runtime-and-source
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-list"])
    (is (= [{:class fqn :runtime :clojure :source clause}]
           (execute/list-clauses node)))))

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

;; ------------------------------------------------------------ valid time

(defn- visit-at
  "A visit report taking effect at `at`, with `inches` of snow."
  [report-id at inches]
  {"$class"       "com.trustblocks.snow@1.0.0.VisitReport"
   "reportId"     report-id
   "$identifier"  report-id
   "$timestamp"   at
   "visitedAt"    at
   "inchesFallen" inches})

(defn- visits-paid-at [node id at]
  (get (execute/current-state node id :at (java.time.Instant/parse at)) "visitsPaid"))

(deftest state-changes-more-than-once-and-reads-back-at-each-date
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-e"])
    (doseq [[id at] [["v-1" "2026-01-15T06:00:00Z"]
                     ["v-2" "2026-02-02T06:00:00Z"]
                     ["v-3" "2026-02-20T06:00:00Z"]]]
      (execute/trigger! node "snow-e" (visit-at id at 3.5) at))
    (is (nil? (visits-paid-at node "snow-e" "2026-01-01T00:00:00Z")))
    (is (= 1 (visits-paid-at node "snow-e" "2026-01-20T00:00:00Z")))
    (is (= 2 (visits-paid-at node "snow-e" "2026-02-10T00:00:00Z")))
    (is (= 3 (visits-paid-at node "snow-e" "2026-03-01T00:00:00Z")))
    (is (= 3 (visits-paid node "snow-e")))))

(deftest a-late-state-change-is-refused-rather-than-overwriting-later-ones
  ;; Before this guard, a Jan 15 report arriving after Feb 2 and Feb 20 read
  ;; today's state (two visits), and wrote "three visits" valid from Jan 15
  ;; with no end -- so Jan 20 and Feb 10 both read back as three.
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-f"])
    (execute/trigger! node "snow-f" (visit-at "v-2" "2026-02-02T06:00:00Z" 3.5)
                      "2026-02-02T06:00:00Z")
    (execute/trigger! node "snow-f" (visit-at "v-3" "2026-02-20T06:00:00Z" 3.5)
                      "2026-02-20T06:00:00Z")
    (is (= :out-of-order
           (error-tag #(execute/trigger! node "snow-f"
                                         (visit-at "v-1" "2026-01-15T06:00:00Z" 3.5)
                                         "2026-01-15T06:00:00Z" :request-id "late-1"))))
    (testing "nothing was written: the history, and every date, are as before"
      (is (= 2 (count (execute/history node "snow-f"))))
      (is (nil? (visits-paid-at node "snow-f" "2026-01-20T00:00:00Z")))
      (is (= 1 (visits-paid-at node "snow-f" "2026-02-10T00:00:00Z")))
      (is (= 2 (visits-paid node "snow-f"))))
    (testing "a refused request id was not consumed"
      (is (nil? (execute/execution-for-request node "snow-f" "late-1"))))
    (testing "a state change at the same instant as the latest one still lands"
      (execute/trigger! node "snow-f" (visit-at "v-4" "2026-02-20T06:00:00Z" 3.5)
                        "2026-02-20T06:00:00Z")
      (is (= 3 (visits-paid node "snow-f"))))))

(def ^:private answer-below-trigger-clause
  "Like `clause`, but a visit below the trigger depth writes no state -- it
  only answers, reporting in amountDue how many visits had been paid as of
  its own time, so a test can see which state the clause was handed."
  "(let [paid (if state (get state \"visitsPaid\") 0)
         rate (get-in data [\"ratePerVisit\" \"amount\"])]
     (if (< (get request \"inchesFallen\") (get data \"inchesTrigger\"))
       {:response {\"$class\" \"com.trustblocks.snow@1.0.0.VisitOutcome\"
                   \"outcomeId\" (get request \"reportId\")
                   \"$identifier\" (get request \"reportId\")
                   \"$timestamp\" now
                   \"outcome\" \"BELOW_TRIGGER\"
                   \"amountDue\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                                \"amount\" (* 1.0 paid) \"currency\" \"USD\"}}}
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
                             \"amount\" (* rate (+ paid 1)) \"currency\" \"USD\"}}}))")

(deftest a-back-dated-trigger-that-writes-no-state-runs-against-its-own-time
  ;; The attestation case: certifying a months-old event after the contract
  ;; has moved on is legitimate, because it only answers.
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-g"])
    (execute/put-clause! node fqn answer-below-trigger-clause)
    (execute/trigger! node "snow-g" (visit-at "v-2" "2026-02-20T06:00:00Z" 4.0)
                      "2026-02-20T06:00:00Z")
    (let [{:keys [response state]}
          (execute/trigger! node "snow-g" (visit-at "v-1" "2026-01-15T06:00:00Z" 1.0)
                            "2026-01-15T06:00:00Z")]
      (is (= "BELOW_TRIGGER" (get response "outcome")))
      (is (nil? state))
      (is (= 0.0 (get-in response ["amountDue" "amount"]))
          "the clause saw the state as of Jan 15 (nothing paid), not today's")
      (is (= 1 (visits-paid node "snow-g"))))))

(deftest the-in-transaction-guard-catches-any-state-written-since-the-read
  ;; trigger! checks the order before writing, so this ASSERT is only reached
  ;; when triggers race. Exercise it directly, so a mistake in its SQL cannot
  ;; hide behind the query that usually runs first.
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-h"])
    (let [guard   #'execute/assert-state-unchanged
          version #(#'execute/state-version node "snow-h")]
      (testing "before any state, any state write since fails it"
        (is (nil? (version)))
        (is (some? (xt/execute-tx node [(guard "snow-h" nil)]))))
      (execute/trigger! node "snow-h" (visit-at "v-2" "2026-02-20T06:00:00Z" 3.5)
                        "2026-02-20T06:00:00Z")
      (is (thrown? Exception (xt/execute-tx node [(guard "snow-h" nil)])))
      (let [read-version (version)]
        (is (some? (xt/execute-tx node [(guard "snow-h" read-version)])))
        (testing "a write dated earlier than the latest still counts as a change"
          (execute/trigger! node "snow-h" (visit-at "v-3" "2026-02-20T06:00:00Z" 3.5)
                            "2026-02-20T06:00:00Z")
          (is (thrown? Exception (xt/execute-tx node [(guard "snow-h" read-version)]))))))))

(deftest concurrent-state-changes-are-never-lost
  ;; Triggers dated at or before one another, all reading the same state at
  ;; once: without a guard, several compute "one more than what I read" and
  ;; the last write wins, dropping visits. Every execution that lands must be
  ;; counted; the rest are refused, never silently lost -- as out of order,
  ;; or, when a trigger loses the optimistic race max-attempts times running
  ;; (eight writers on one contract can do that), as :state-conflict.
  ;;
  ;; It also exercises XTDB 2.1's :xtdb/unindexed-tx, which failed this test
  ;; about one run in several before trigger! learned to wait it out -- see
  ;; execute/unindexed? and dev/xtdb_unindexed_tx_repro.clj.
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-i"])
    (let [attempts (->> (range 8)
                        (mapv (fn [i]
                                (let [at (format "2026-02-%02dT06:00:00Z" (inc i))]
                                  (future
                                    (error-tag
                                     #(execute/trigger! node "snow-i"
                                                        (visit-at (str "v-" i) at 3.5)
                                                        at :request-id (str "r-" i)))))))
                        (mapv deref))
          landed   (count (execute/history node "snow-i"))]
      (is (every? #{:out-of-order :state-conflict} (filter some? attempts)) (pr-str attempts))
      (is (= landed (count (remove some? attempts))))
      (is (= landed (visits-paid node "snow-i"))))))

(deftest a-java-util-date-is-accepted-as-now
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-j"])
    (execute/trigger! node "snow-j" (visit-at "v-1" "2026-01-15T06:00:00Z" 3.5)
                      #inst "2026-01-15T06:00:00Z")
    (execute/trigger! node "snow-j" (visit-at "v-2" "2026-02-02T06:00:00Z" 3.5)
                      #inst "2026-02-02T06:00:00Z")
    (is (= 2 (visits-paid node "snow-j")))))

(deftest a-retried-request-is-a-duplicate-even-behind-a-later-state-change
  ;; A resent request that already landed is answered as a duplicate, with
  ;; when it ran -- not as out of order, which would suggest it never did.
  (with-open [node (xt.node/start-node)]
    (populate! node ["snow-k"])
    (execute/trigger! node "snow-k" (visit-at "v-1" "2026-01-15T06:00:00Z" 3.5)
                      "2026-01-15T06:00:00Z" :request-id "r-1")
    (execute/trigger! node "snow-k" (visit-at "v-2" "2026-02-02T06:00:00Z" 3.5)
                      "2026-02-02T06:00:00Z" :request-id "r-2")
    (is (= :duplicate-request
           (error-tag #(execute/trigger! node "snow-k" (visit-at "v-1" "2026-01-15T06:00:00Z" 3.5)
                                         "2026-01-15T06:00:00Z" :request-id "r-1"))))))
