(ns certification-demo
  "The vote-then-minutes certification case, layered onto the real China
  Grove manager-employment contract (dev/manager_employment_demo.clj):
  the Town Clerk certifies the council's own vote at the Board's actual
  councilActionDate, then certifies the minutes approving it at the next
  meeting, naming the vote certification as evidence.

  certify! runs execute/trigger! against the contract itself now (see
  com.trustblocks.xtdb.certification's own docstring), so unlike its
  first version, this demo does depend on manager-employment-demo: the
  contract has to actually exist, carry the attestation branch in its
  own clause, and already be Board-approved -- 'I certify this was
  approved' can't be true otherwise -- before certify! will do
  anything. load! below ensures all of that, idempotently, whether run
  standalone or as part of dev/seed.clj's larger sequence.

    bin/clj -M:xtdb-jvm-opts -i dev/certification_demo.clj"
  (:require [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.execute :as ex]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [manager-employment-demo :as me]
            [xtdb.node :as xt.node])
  (:import [java.time Instant]))

(def town-slug "china-grove")
(def town-did "did:web:trustblocks.com:towns:china-grove")
(def clerk-email "pmills@chinagrovenc.gov")
(def contract-id me/contract-id)

(def officeholders
  "The email-linked demo authorization ledger requested for China Grove.

  These authorities are test policy, not a claim that the titles alone confer
  these powers under North Carolina law. They make each role visible through
  the signed OfficeGrant UI while preserving the one real application rule in
  use today: only the Town Clerk receives CERTIFY_MINUTES."
  [{:email "fgover@chinagrovenc.gov"
    :name "Franklin Gover"
    :office "Town Manager"
    :authority ["SIGN_CONTRACTS"]}
   {:email clerk-email
    :name "Pamela Mills"
    :office "Town Clerk"
    :authority ["CERTIFY_MINUTES" "ATTEST_ORDINANCES"]}
   {:email "rnewsome@chinagrovenc.gov"
    :name "Rebekah Newsome"
    :office "Finance Director"
    :authority ["ATTEST_FINANCIALS"]}
   {:email "rphillips@chinagrovenc.gov"
    :name "Rodney Phillips"
    :office "Mayor"
    :authority ["SIGN_CONTRACTS" "ATTEST_ORDINANCES"]}])

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

(defn- ensure-town! [node]
  (or (identity/by-did node town-did)
      (identity/provision-town! node {:slug town-slug :name "China Grove"})))

(defn- office-grant? [record]
  (= cert/office-grant-class (:concerto/class record)))

(defn- ensure-officeholder! [node town {:keys [email name office authority]}]
  (let [officeholder (or (identity/by-email node email)
                         (identity/provision-officeholder!
                          node {:email email :name name :office office}))]
    (when-not (some office-grant? (credential/for-subject node (:xt/id officeholder)))
      (cert/grant-office! node (:xt/id town)
                          {:officeholder-did (:xt/id officeholder)
                           :name name
                           :office office
                           :authority authority
                           :valid-from grant-valid-from
                           :valid-until grant-valid-until}))
    officeholder))

(defn load!
  "Ensures the contract exists and is already Board-approved (see the
  ns docstring), then provisions the Town and Clerk once -- safe to
  call repeatedly. Provisioning specifically, unlike a contract
  instance's plain upsert, is not something to redo without the guard
  above: it would mint a second, different did for the same person (a
  fresh keypair every time -- see com.trustblocks.xtdb.identity), not
  the same identity re-saved."
  [node]
  (me/load! node)
  (when-not (ex/current-state node me/contract-id)
    (me/approve! node))
  (store/put-model! node "model/identity.cto")
  (store/put-model! node "model/attestation.cto")
  (let [town (ensure-town! node)]
    (doseq [officeholder officeholders]
      (ensure-officeholder! node town officeholder))
    (identity/by-email node clerk-email)))

(defn certify!
  "The vote, then the minutes approving it. Redo-safe now the same way
  every other trigger! call is (each certify! derives its own
  request-id from its contract/authority/documentHash/valid-from), but
  dev/seed.clj still checks whether this has already run first rather
  than relying on that -- cert/certify!'s second call would otherwise
  throw a :duplicate-request refusal (correct, but noisy) instead of
  quietly doing nothing."
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
