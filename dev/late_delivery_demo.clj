(ns late-delivery-demo
  "Ingests the real late-delivery.cta Template Archive (Accord's own
  latedeliveryandpenalty-typescript example -- see docs/examples/late-delivery.cta
  and ~/Code/Accord/WORKSPACE.md), runs a hand-ported Clojure clause against
  it, and checks the result against the number Accord's own APAP reference
  server already produced for this exact scenario: `penalty: 4` on $200 of
  goods, delayed 19 days against a 9-day/7%-penalty/2%-cap/2-week-termination
  clause.

  This is not an automatic TypeScript -> Clojure translator -- there isn't
  one, and com.trustblocks.xtdb.execute's :clause/runtime seam exists so
  there never has to be. This is a human port, checked against a known-good
  answer from the original runtime, which is what proves the port is
  faithful rather than merely self-consistent.

    bin/clj -M:xtdb-jvm-opts -i dev/late_delivery_demo.clj"
  (:require [com.trustblocks.template.archive :as archive]
            [com.trustblocks.xtdb.execute :as ex]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

;; Ported from logic/logic.ts in the archive. `divide` takes an explicit
;; scale because `/` isn't in the clause vocabulary at all (see
;; com.trustblocks.clause) -- an exact ratio on the JVM would silently
;; diverge from a float elsewhere, which is exactly the class of bug a
;; penalty calculation cannot afford.
(def clojure-clause
  "(let [agreed    (get request \"agreedDelivery\")
         delivered (or (get request \"deliveredAt\") now)]
     (if (after? agreed delivered)
       (throw (ex-info \"Cannot exercise late delivery before delivery date\" {}))
       (let [force-majeure?  (and (get data \"forceMajeure\") (get request \"forceMajeure\"))
             delay-days      (days-between agreed delivered)
             penalty-days    (duration-days (get data \"penaltyDuration\"))
             termination-days (duration-days (get data \"termination\"))
             ratio           (divide delay-days penalty-days 6)
             goods-value     (get request \"goodsValue\")
             penalty-raw     (* ratio (divide (get data \"penaltyPercentage\") 100 6) goods-value)
             max-penalty     (* (divide (get data \"capPercentage\") 100 6) goods-value)
             penalty         (cond
                               force-majeure? 0.0
                               (pos? ratio)   (min penalty-raw max-penalty)
                               :else          0.0)
             buyer-may-terminate? (cond
                                    force-majeure?                   true
                                    (> delay-days termination-days) true
                                    :else                            false)
             identifier      (if state (get state \"$identifier\") (get data \"$identifier\"))
             prior-count     (if state (get state \"count\") 0)
             prior-total     (if state (get state \"totalPenalties\") 0.0)
             prior-processed (if state (get state \"lateDeliveryProcessed\") false)]
         {:response {\"$class\" \"io.clause.latedeliveryandpenalty@0.1.0.LateDeliveryAndPenaltyResponse\"
                     \"$timestamp\" now
                     \"penalty\" penalty
                     \"buyerMayTerminate\" buyer-may-terminate?}
          :state {\"$class\" \"io.clause.latedeliveryandpenalty@0.1.0.LateDeliveryAndPenaltyState\"
                  \"$identifier\" identifier
                  \"count\" (+ prior-count 1)
                  \"lateDeliveryProcessed\" (or (> penalty 0) prior-processed)
                  \"totalPenalties\" (+ prior-total penalty)}})))")

(def fqn "io.clause.latedeliveryandpenalty@0.1.0.TemplateModel")

;; Reconstructed from package.json's description ("every 9 DAY of delay
;; penalty amounting to 7.0%... shall not exceed 2.0%... delay is more than
;; 2 WEEK") -- the archive itself ships a sample *request*, not a sample
;; contract, so the contract data is rebuilt from the terms the template's
;; own metadata states.
(def contract-data
  {"$class"           fqn
   "clauseId"         "late-delivery-1"
   "$identifier"      "late-delivery-1"
   "forceMajeure"     false
   "penaltyDuration"  {"$class" "org.accordproject.time@0.3.0.Duration" "amount" 9 "unit" "days"}
   "penaltyPercentage" 7.0
   "capPercentage"    2.0
   "termination"      {"$class" "org.accordproject.time@0.3.0.Duration" "amount" 2 "unit" "weeks"}
   "fractionalPart"   "days"})

;; The exact request WORKSPACE.md's verified curl used against the running
;; APAP reference server, producing `penalty: 4`.
(def sample-request
  {"$class"         "io.clause.latedeliveryandpenalty@0.1.0.LateDeliveryAndPenaltyRequest"
   "forceMajeure"   false
   "agreedDelivery" "2017-12-01T03:24:00Z"
   "deliveredAt"    "2017-12-20T03:24:00Z"
   "goodsValue"     200.0})

(defn run []
  (let [node (xt.node/start-node)]
    (try
      (println "Ingesting docs/examples/late-delivery.cta ...")
      (println (archive/ingest-cta! node "docs/examples/late-delivery.cta"))

      (store/put-instance! node nil contract-data)
      (ex/put-clause! node fqn clojure-clause)

      (let [{:keys [response]} (ex/trigger! node "late-delivery-1" sample-request
                                            "2017-12-20T03:24:00Z")
            penalty (get response "penalty")]
        (println "\nresponse:" response)
        (if (= 4.0 penalty)
          (println "\nMATCH -- penalty 4.0, same as Accord's own APAP reference run.")
          (println "\nMISMATCH -- expected penalty 4.0, got" penalty)))
      (finally
        (.close node)))))

(run)
