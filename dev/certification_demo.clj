(ns certification-demo
  "The vote-then-minutes certification case, layered onto the real China
  Grove manager-employment contract (dev/manager_employment_demo.clj):
  the Town Clerk certifies the council's own vote at the Board's actual
  councilActionDate, then certifies the minutes approving it at the next
  meeting, naming the vote certification as evidence.

  Provisions its own Town and officeholder identity rather than reusing
  anything from manager-employment-demo -- an Attestation only names a
  contract by id (see model/identity.cto's own docstring), it does not
  require or read the contract instance itself, so this demo works even
  run against a node manager-employment-demo has never touched.

    bin/clj -M:xtdb-jvm-opts -i dev/certification_demo.clj"
  (:require [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.time Instant]))

(def town-slug "china-grove")
(def clerk-email "clerk@china-grove.example.gov")
(def contract-id "china-grove-gover-2025")

;; The vote's own date is the real councilActionDate from
;; docs/examples/manager-employment-contract/sample.json. The sample has
;; no second meeting date on file, so next-meeting is this demo's own
;; addition, not something the source contract records. The office
;; grant's window is the manager's own contract term (termStartDate/
;; termEndDate in that same sample) -- a Clerk's authority to certify
;; minutes isn't really scoped to one contract's term in reality, but
;; nothing here has a second contract to make that distinction matter
;; yet, and reusing dates already in the sample beats inventing new ones.
(def vote-meeting (Instant/parse "2025-12-02T00:00:00Z"))
(def next-meeting (Instant/parse "2026-01-06T00:00:00Z"))
(def grant-valid-from (Instant/parse "2025-07-01T00:00:00Z"))
(def grant-valid-until (Instant/parse "2029-06-30T00:00:00Z"))

(defn- provision! [node]
  (let [town  (identity/provision-town! node {:slug town-slug :name "China Grove"})
        clerk (identity/provision-officeholder! node {:email clerk-email :name "Jane Clerk"
                                                      :office "Town Clerk"})]
    (cert/grant-office! node (:xt/id town)
                        {:officeholder-did (:xt/id clerk) :name "Jane Clerk" :office "Town Clerk"
                         :authority ["CERTIFY_MINUTES"]
                         :valid-from grant-valid-from :valid-until grant-valid-until})
    clerk))

(defn load!
  "Provisions the Town and Clerk once -- safe to call repeatedly, since
  identity/by-email is checked first. Unlike a contract instance's plain
  upsert, provisioning twice would mint a second, different did for the
  same person (a fresh keypair every time -- see
  com.trustblocks.xtdb.identity), not the same identity re-saved."
  [node]
  (store/put-model! node "model/identity.cto")
  (or (identity/by-email node clerk-email)
      (provision! node)))

(defn certify!
  "The vote, then the minutes approving it. Not redo-safe the way a
  Concerto request/response trigger is (see com.trustblocks.xtdb.execute's
  own duplicate-request guard) -- certify! has none, by design; see
  com.trustblocks.app.certify's docstring. dev/seed.clj guards this the
  same way it guards manager-employment-demo's own trigger, by checking
  whether it has already run first, rather than by an operation that is
  itself safe to repeat."
  [node]
  (let [vote-jwt (cert/certify! node {:email clerk-email
                                      :statement "The council voted to approve the manager's employment contract"
                                      :document-hash "sha256:china-grove-vote-2025-12-02"
                                      :contract-id contract-id
                                      :required-authority "CERTIFY_MINUTES"
                                      :valid-from vote-meeting :valid-until grant-valid-until})]
    (cert/certify! node {:email clerk-email
                         :statement "Minutes of the December 2 meeting were approved as written"
                         :document-hash "sha256:china-grove-minutes-2026-01-06"
                         :contract-id contract-id
                         :required-authority "CERTIFY_MINUTES"
                         :valid-from next-meeting :valid-until grant-valid-until
                         :evidence vote-jwt})))

(defn run []
  (let [node (xt.node/start-node)]
    (try
      (load! node)
      (certify! node)
      (println "Certifications for" contract-id ":")
      (doseq [a (cert/attestations-for-contract node contract-id)]
        (println " " (get a "valid-from") "--" (get a "statement")))
      (finally
        (.close node)))))

(run)
