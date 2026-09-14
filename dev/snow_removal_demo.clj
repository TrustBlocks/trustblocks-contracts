(ns snow-removal-demo
  "The synthetic snow-removal example walked through in docs/walkthrough.md,
  as a runnable script rather than only prose: a municipal plowing contract
  that pays a fixed rate per visit when snowfall clears a trigger depth,
  with three visit reports triggering it across a winter.

    bin/clj -M:xtdb-jvm-opts -i dev/snow_removal_demo.clj"
  (:require [com.trustblocks.xtdb.execute :as ex]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def fqn "com.trustblocks.snow@1.0.0.SnowRemoval")
(def contract-id "snow-2026-014")

(def contract-data
  {"$class" fqn
   "contractId" contract-id "$identifier" contract-id
   "contractor" "Glenwood Plowing LLC" "route" "Ward 3 arterials"
   "inchesTrigger" 2.0
   "ratePerVisit" {"$class" "com.trustblocks.snow@1.0.0.Money"
                   "amount" 480.0 "currency" "USD"}})

(def clojure-clause
  "(let [inches  (get request \"inchesFallen\")
         payable (>= inches (get data \"inchesTrigger\"))
         rate    (get-in data [\"ratePerVisit\" \"amount\"])
         due     (if payable rate 0.0)
         paid    (if state (get state \"visitsPaid\") 0)
         total   (if state (get-in state [\"totalPaid\" \"amount\"]) 0.0)]
     {:response {\"$class\" \"com.trustblocks.snow@1.0.0.VisitOutcome\"
                 \"outcomeId\" (get request \"reportId\")
                 \"$identifier\" (get request \"reportId\")
                 \"$timestamp\" now
                 \"outcome\" (if payable \"PAYABLE\" \"BELOW_TRIGGER\")
                 \"amountDue\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                              \"amount\" due \"currency\" \"USD\"}}
      :state {\"$class\" \"com.trustblocks.snow@1.0.0.PaymentState\"
              \"contractId\" (get data \"contractId\")
              \"$identifier\" (get data \"contractId\")
              \"visitsPaid\" (if payable (+ paid 1) paid)
              \"totalPaid\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                           \"amount\" (+ total due) \"currency\" \"USD\"}}})")

(defn visit! [node id at inches]
  (ex/trigger! node contract-id
              {"$class" "com.trustblocks.snow@1.0.0.VisitReport"
               "reportId" id "$identifier" id "$timestamp" at
               "visitedAt" at "inchesFallen" inches}
              at))

(defn populate!
  "Loads the contract, its clause, and three winter visits into node."
  [node]
  (store/put-model! node "docs/examples/snow-removal.cto")
  (store/put-instance! node nil contract-data)
  (ex/put-clause! node fqn clojure-clause)
  (visit! node "v-1" "2026-01-15T06:00:00Z" 3.5)
  (visit! node "v-2" "2026-02-02T06:00:00Z" 1.0)
  (visit! node "v-3" "2026-02-20T06:00:00Z" 4.0))

(defn run []
  (let [node (xt.node/start-node)]
    (try
      (populate! node)
      (println "visitsPaid / totalPaid:" (ex/current-state node contract-id))
      (finally
        (.close node)))))

(run)

