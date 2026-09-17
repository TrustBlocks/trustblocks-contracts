(ns manager-employment-demo
  "Ingests the real China Grove / Gover manager employment contract (model +
  sample drawn from ~/Code/Accord/municipal-templates, copied into
  docs/examples/manager-employment-contract/) and runs a Board-approval
  trigger against it -- an authorization/certification execution, not a
  monetary one, exercising a different shape of `trigger!` than
  dev/late_delivery_demo.clj's penalty calculation.

  Also surfaces a real bitemporal wrinkle sitting in the source data: the
  contract's own effectiveDate (2025-07-01, when its terms -- including
  retroactive salary -- take hold) precedes councilActionDate (2025-12-02,
  when the Board actually approved it). Not a bug in the source contract --
  retroactiveSalary is explicitly true -- but it means \"when this became
  effective\" and \"when it was approved\" are two different facts, and this
  demo only tracks the second one as the state's valid time. Modeling the
  first properly is future work; see model/execution.cto's docstring.

    bin/clj -M:xtdb-jvm-opts -i dev/manager_employment_demo.clj"
  (:require [cheshire.core :as json]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.xtdb.execute :as ex]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def contract-fqn "com.trustblocks.municipal.employment@1.0.0.ManagerEmployment")
(def contract-id  "china-grove-gover-2025")

;; A certification clause, not a calculation: it refuses a second approval
;; rather than computing anything, and it reads the request only for its
;; own sake -- the dates that matter (councilActionDate, effectiveDate) are
;; already on the contract itself, because this is a completed, signed
;; instrument, not a live workflow.
(def clojure-clause
  "(if (and state (= (get state \"status\") \"APPROVED\"))
     (throw (ex-info \"Already approved by the Board\" {}))
     (let [approved-at (get data \"councilActionDate\")
           effective   (get data \"effectiveDate\")]
       {:response {\"$class\" \"com.trustblocks.municipal.employment.execution@1.0.0.BoardApprovalResponse\"
                   \"$timestamp\" now
                   \"status\" \"APPROVED\"
                   \"approvedAt\" approved-at
                   \"effectiveDate\" effective}
        :state {\"$class\" \"com.trustblocks.municipal.employment.execution@1.0.0.ManagerEmploymentState\"
                \"contractId\" (get data \"contractId\")
                \"status\" \"APPROVED\"
                \"approvedAt\" approved-at
                \"effectiveDate\" effective}}))")

(defn load!
  "Ingests the model + logic archive, and loads the contract instance and
  its clause, into node -- a plain upsert of whatever's currently on disk,
  safe to call repeatedly. dev/seed.clj relies on that: it calls this
  unconditionally on every run, including against a china-grove-gover-2025
  already sitting in storage/, so an archive change (a new grammar.tem.md,
  say) still takes effect without a full rm -rf storage. Only trigger! --
  the actual approval event -- is the part that isn't safe to redo."
  [node]
  (println "Ingesting docs/examples/manager-employment-contract ...")
  (println (archive/ingest-dir! node "docs/examples/manager-employment-contract"))
  ;; No $identifier fixup any more: the model extends
  ;; org.accordproject.contract.Contract, so it inherits `identified by
  ;; contractId` and the sample carries a real contractId. Identity is the
  ;; model's, not something the loader bolts on.
  (store/put-instance!
   node nil (json/parse-string
             (slurp "docs/examples/manager-employment-contract/sample.json")))
  (ex/put-clause! node contract-fqn clojure-clause))

(defn run []
  (let [node (xt.node/start-node)]
    (try
      (load! node)

      (println "\nBefore any trigger, state:" (ex/current-state node contract-id))

      (let [{:keys [response state]}
            (ex/trigger! node contract-id
                         {"$class"      "com.trustblocks.municipal.employment.execution@1.0.0.BoardApprovalRequest"
                          "requestedAt" "2025-12-02T00:00:00.000Z"}
                        ;; now = the Board's own meeting date, not wall-clock
                        ;; time -- see the ns docstring.
                         "2025-12-02T00:00:00.000Z")]
        (println "\nBoard-approval response:" response)
        (println "New state:" state))

      (println "\nAs of the Board meeting (2025-12-02):"
               (ex/state-as-of node contract-id #inst "2025-12-02T00:00:01"))
      (println "As of the day before the Board meeting (2025-12-01):"
               (ex/state-as-of node contract-id #inst "2025-12-01T00:00:00"))

      (println "\nTriggering a second Board approval on the same contract...")
      (try
        (ex/trigger! node contract-id
                     {"$class"      "com.trustblocks.municipal.employment.execution@1.0.0.BoardApprovalRequest"
                      "requestedAt" "2026-01-05T00:00:00.000Z"}
                     "2026-01-05T00:00:00.000Z")
        (println "BUG: a second approval should have been refused")
        (catch Exception e
          (println "Refused, as it should be:" (ex-message e))))

      (println "\n--- the bitemporal wrinkle ---")
      (println "effectiveDate (terms, incl. retroactive salary) start: 2025-07-01")
      (println "councilActionDate (Board approval; this trigger's valid time): 2025-12-02")
      (println "The contract's terms were in force ~5 months before the Board approved")
      (println "it -- legitimate here (retroactiveSalary: true), but \"effective\" and")
      (println "\"approved\" are different facts with different valid times, and this")
      (println "demo only tracks the second one. Worth a real pass later, not now.")
      (finally
        (.close node)))))

(run)
