(ns manager-employment-demo
  "Ingests the real China Grove / Gover manager employment contract -- the
  manager-employment-contract template from TrustBlocks/trustblocks-templates
  (see com.trustblocks.template.library): its model, sample, and the clause
  in its logic/clause.clj -- and approves it: the Town Clerk certifies the
  Council's vote, which the contract's lifecycle requires before anything
  else can happen to it (an Attestation under CERTIFY_MINUTES). An
  authorization/certification execution, not a monetary one.

  Also surfaces a real bitemporal wrinkle sitting in the source data: the
  contract's own effectiveDate (2025-07-01, when its terms -- including
  retroactive salary -- take hold) precedes councilActionDate (2025-12-02,
  when the Board actually approved it). Not a bug in the source contract --
  retroactiveSalary is explicitly true -- but it means \"when this became
  effective\" and \"when it was approved\" are two different facts, and this
  demo only tracks the second one as the state's valid time. Modeling the
  first properly is future work; see the template's ManagerEmploymentState
  in model/model.cto.

    bin/clj -M:xtdb-jvm-opts -i dev/manager_employment_demo.clj"
  (:require [cheshire.core :as json]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.advance :as advance]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.execute :as ex]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def contract-id  "china-grove-gover-2025")
;; The real councilActionDate from sample.json -- the Board's own meeting
;; date, and so also this trigger's own valid time (see approve!'s
;; docstring). Named here rather than left as a literal at each call site,
;; since dev/certification_demo.clj now also needs the contract already
;; approved before it can attest to that fact.
(def board-meeting-date "2025-12-02T00:00:00.000Z")

;; The record of the Council's vote the Clerk's certification names. The
;; minutes themselves are not on file here, so this is a reference, not a
;; stored document's hash.
(def vote-reference "sha256:china-grove-vote-2025-12-02")

(defn load!
  "Ingests the model + logic archive, and loads the contract instance and
  its clause, into node -- a plain upsert of whatever's currently on disk,
  safe to call repeatedly. dev/seed.clj relies on that: it calls this
  unconditionally on every run, including against a china-grove-gover-2025
  already sitting in storage/, so an archive change (a new grammar.tem.md,
  say) still takes effect without a full rm -rf storage. Only trigger! --
  the actual approval event -- is the part that isn't safe to redo."
  [node]
  (println "Ingesting the manager-employment-contract template ...")
  (println (archive/ingest-dir! node (library/dir "manager-employment-contract")))
  ;; No $identifier fixup any more: the model extends
  ;; org.accordproject.contract.Contract, so it inherits `identified by
  ;; contractId` and the sample carries a real contractId. Identity is the
  ;; model's, not something the loader bolts on.
  ;; The clause arrives with the template: ingest-dir! installs its
  ;; logic/clause.clj for ManagerEmployment. That clause is a certification,
  ;; not a calculation -- it refuses a second approval, and answers the
  ;; standard AttestationRequest (see com.trustblocks.xtdb.certification)
  ;; only once the Board has approved.
  (store/put-instance!
   node nil (json/parse-string (library/sample "manager-employment-contract"))))

(defn approve!
  "The Board's approval, certified by the Town Clerk signed in as
  `clerk-email` -- who must hold CERTIFY_MINUTES on the Board's meeting
  date, as the contract's lifecycle requires. Its own function because
  dev/seed.clj, dev/certification_demo.clj and the tests all need the
  contract approved before anything can be attested about it. `:at`
  defaults to the Board's meeting date."
  [node clerk-email & {:keys [at] :or {at board-meeting-date}}]
  (advance/advance! node {:email clerk-email
                          :document contract-id
                          :credential "Attestation"
                          :event {"$class" "com.trustblocks.municipal.employment@1.0.0.BoardApprovalRequest"
                                  "requestedAt" at}
                          :at (ex/->instant at)
                          :statement "The Town Council approved the Town Manager's employment contract"
                          :document-hash vote-reference}))

(defn- provision-clerk!
  "A Town and a Clerk holding CERTIFY_MINUTES, for this demo's own
  throwaway node; certification-demo provisions the real ledger."
  [node]
  (store/put-model! node "model/identity.cto")
  (let [town  (identity/provision-town! node {:slug "china-grove" :name "China Grove"})
        clerk (identity/provision-officeholder! node {:email "pmills@chinagrovenc.gov"
                                                      :name "Pamela Mills" :office "Town Clerk"})]
    (cert/grant-office! node (:xt/id town)
                        {:officeholder-did (:xt/id clerk) :name "Pamela Mills" :office "Town Clerk"
                         :authority ["CERTIFY_MINUTES"]
                         :valid-from (ex/->instant "2025-07-01T00:00:00Z")
                         :valid-until (ex/->instant "2029-06-30T00:00:00Z")})
    "pmills@chinagrovenc.gov"))

(defn run []
  (let [node (xt.node/start-node)]
    (try
      (load! node)

      (println "\nBefore any trigger, state:" (ex/current-state node contract-id))

      ;; now = the Board's own meeting date, not wall-clock time -- see
      ;; the ns docstring.
      (let [clerk (provision-clerk! node)
            {:keys [response state]} (approve! node clerk)]
        (println "\nBoard-approval response:" response)
        (println "New state:" state))

      (println "\nAs of the Board meeting (2025-12-02):"
               (ex/state-as-of node contract-id #inst "2025-12-02T00:00:01"))
      (println "As of the day before the Board meeting (2025-12-01):"
               (ex/state-as-of node contract-id #inst "2025-12-01T00:00:00"))

      (println "\nTriggering a second Board approval on the same contract...")
      (try
        (approve! node "pmills@chinagrovenc.gov" :at "2026-01-05T00:00:00.000Z")
        (println "BUG: a second approval should have been refused")
        (catch Exception e
          (println "Refused, as it should be:" (ex-message e))))
      (println "\nTriggering Board approval with no certification, as the trigger endpoint would...")
      (try
        (ex/trigger! node contract-id
                     {"$class"      "com.trustblocks.municipal.employment@1.0.0.BoardApprovalRequest"
                      "requestedAt" "2026-01-05T00:00:00.000Z"}
                     "2026-01-05T00:00:00.000Z")
        (println "BUG: an uncertified approval should have been refused")
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
