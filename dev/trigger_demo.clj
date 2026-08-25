
;; Contract execution end to end: store a contract and its clause, fire
;; requests at it, and read back both the result and the reason.
;;
;;   bin/clj -M:xtdb-jvm-opts -i dev/trigger_demo.clj

(require '[com.trustblocks.xtdb.execute :as ex]
         '[com.trustblocks.xtdb.store :as store]
         '[xtdb.node :as xt.node])

(def lib (str (System/getProperty "user.home") "/Code/Accord/cicero-template-library/src/"))
(def fqn "org.accordproject.acceptanceofdelivery@0.1.0.TemplateModel")

(def contract
  {"$class" fqn "clauseId" "glenwood-2025" "$identifier" "glenwood-2025"
   "shipper" "Piedmont Paving LLC" "receiver" "City of Raleigh"
   "deliverable" "resurfacing of Glenwood Ave" "businessDays" 10
   "attachment" "DOT inspection report 2025-114"})

(def aod-clause
  "(let [deadline (plus-days (get request \"deliverableReceivedAt\") (get data \"businessDays\"))
         status   (cond (after? now deadline)            \"OUTSIDE_INSPECTION_PERIOD\"
                        (get request \"inspectionPassed\") \"PASSED_TESTING\"
                        :else                            \"FAILED_TESTING\")]
     {:response {\"$class\"     \"org.accordproject.acceptanceofdelivery@0.1.0.InspectionResponse\"
                 \"$timestamp\" now
                 \"status\"     status
                 \"shipper\"    (get data \"shipper\")
                 \"receiver\"   (get data \"receiver\")}})")

(def node (xt.node/start-node))
(try
  (doseq [f (store/model-dir-files (str lib "acceptance-of-delivery/model"))]
    (store/put-model! node f))
  (def reg (store/registry-from-xtdb node))
  (store/put-instance! node reg contract)
  (ex/put-clause! node fqn aod-clause)
  (println "contract and clause stored\n")

  (defn fire [when- passed?]
    (let [r (ex/trigger! node "glenwood-2025"
                         {"$class" "org.accordproject.acceptanceofdelivery@0.1.0.InspectDeliverable"
                          "$identifier" (str "req-" when-) "$timestamp" when-
                          "deliverableReceivedAt" "2025-06-01T00:00:00Z"
                          "inspectionPassed" passed?}
                         when- :reg reg)]
      (printf "  %s  passed=%-5s -> %s\n" when- passed? (get-in r [:response "status"]))))

  (fire "2025-06-05T00:00:00Z" true)
  (fire "2025-06-08T00:00:00Z" false)
  (fire "2025-07-01T00:00:00Z" true)

  (println "\nexecution log (the reason, not just the result)")
  (doseq [row (ex/history node "glenwood-2025")]
    (printf "  %s  passed=%-5s -> %s\n" (:at row)
            (get-in row [:request :inspection-passed])
            (get-in row [:response :status])))

  (println "\nreplayable? re-running the first request with the same `now`:")
  (let [again (ex/trigger! node "glenwood-2025"
                           {"$class" "org.accordproject.acceptanceofdelivery@0.1.0.InspectDeliverable"
                            "$identifier" "req-replay" "$timestamp" "2025-06-05T00:00:00Z"
                            "deliverableReceivedAt" "2025-06-01T00:00:00Z"
                            "inspectionPassed" true}
                           "2025-06-05T00:00:00Z" :reg reg)]
    (println "  same verdict:" (= "PASSED_TESTING" (get-in again [:response "status"]))))

  (println "\nan invalid request is refused before the clause runs:")
  (try (ex/trigger! node "glenwood-2025"
                    {"$class" "org.accordproject.acceptanceofdelivery@0.1.0.InspectDeliverable"
                     "$identifier" "bad" "deliverableReceivedAt" "not-a-date"
                     "inspectionPassed" true}
                    "2025-06-05T00:00:00Z" :reg reg)
       (println "  NOT REFUSED")
       (catch Exception e (println "  " (ex-message e) (pr-str (:errors (ex-data e))))))

  (println "\na clause reaching outside the vocabulary is refused at ingestion:")
  (try (ex/put-clause! node "x@1.0.0.Y" "(slurp \"/etc/passwd\")")
       (println "  NOT REFUSED")
       (catch Exception e (println "  " (ex-message e))))
  (finally (.close node)))
