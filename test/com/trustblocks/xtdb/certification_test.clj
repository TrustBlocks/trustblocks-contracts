(ns com.trustblocks.xtdb.certification-test
  "The vote-then-minutes case from the actual ask: a Clerk certifies that
  a council vote took place, at the vote's own bitemporal time; at the
  next meeting, a second certification approves the minutes recording
  it, naming the first as evidence.

  This file's own flakiness, before it was tracked down, had nothing to
  do with xtdb node lifetimes or a race -- it was a bug in
  com.trustblocks.xtdb.credential/for-subject, which sorted results by
  :xt/id (a random UUID, so no order at all) while its docstring
  claimed 'oldest first'. The office grant and the two attestations
  have no fixed relative :xt/id order, so for-subject's result was
  shuffled about a third of the time, and the-vote-then-minutes-case's
  own assertion on that order failed along with it -- there was never
  a duplicate or a missing row, only a reordering that a values-only
  diff renders as if an element were removed from one spot and
  inserted at another. Fixed by sorting on the credential's own
  :xt/valid-from instead."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.vc :as vc]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [manager-employment-demo :as me]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node])
  (:import [java.time Instant]))

(def ^:private year-start (Instant/parse "2025-07-01T00:00:00Z"))
(def ^:private year-end (Instant/parse "2027-01-01T00:00:00Z"))
(def ^:private vote-meeting (Instant/parse "2026-03-04T19:00:00Z"))
(def ^:private next-meeting (Instant/parse "2026-04-01T19:00:00Z"))
(def ^:private contract-id me/contract-id)

(defn- populate! [node]
  ;; certify! runs execute/trigger! against contract-id (see
  ;; com.trustblocks.xtdb.certification's own docstring), so this
  ;; namespace's tests need the real employment contract on file, not just
  ;; the identity model. Approving it takes the Clerk -- see approved! --
  ;; so a test that certifies provisions first, then approves.
  (me/load! node)
  (store/put-model! node "model/identity.cto"))

(defn- approved!
  "The Board's approval, certified by the provisioned Clerk: the contract's
  lifecycle allows no attestation before it."
  [node]
  (me/approve! node "clerk@china-grove.example.gov"))

(defn- provision! [node]
  (let [town (identity/provision-town! node {:slug "china-grove" :name "China Grove"})
        clerk (identity/provision-officeholder! node {:email "clerk@china-grove.example.gov"
                                                      :name "Jane Clerk" :office "Town Clerk"})]
    (cert/grant-office! node (:xt/id town)
                        {:officeholder-did (:xt/id clerk) :name "Jane Clerk" :office "Town Clerk"
                         :authority ["CERTIFY_MINUTES"]
                         :valid-from year-start :valid-until year-end})
    {:town town :clerk clerk}))

(defn- refusal
  "The :trustblocks/error tag `f` throws, or nil if it doesn't throw at all
  -- so a test can assert on the tag rather than a message string, the same
  thing com.trustblocks.app.certify does to pick a status code."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(deftest certify-refuses-when-not-authorized
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (testing "an unknown email"
      (is (= :no-such-officeholder
             (refusal #(cert/certify! node {:email "nobody@example.gov" :statement "x" :document-hash "h"
                                            :contract-id contract-id :required-authority "CERTIFY_MINUTES"
                                            :valid-from vote-meeting :valid-until year-end})))))

    (testing "an officeholder with no office grant at all"
      (identity/provision-officeholder! node {:email "nobody@china-grove.example.gov"
                                              :name "No One" :office "Nobody"})
      (is (= :no-office-grant
             (refusal #(cert/certify! node {:email "nobody@china-grove.example.gov" :statement "x"
                                            :document-hash "h" :contract-id contract-id
                                            :required-authority "CERTIFY_MINUTES"
                                            :valid-from vote-meeting :valid-until year-end})))))

    (testing "a grant that does not name the required authority"
      (let [{:keys [town]} (provision! node)
            zoning (identity/provision-officeholder! node {:email "zoning@china-grove.example.gov"
                                                           :name "Sam Zoning" :office "Zoning Officer"})]
        (cert/grant-office! node (:xt/id town)
                            {:officeholder-did (:xt/id zoning) :name "Sam Zoning" :office "Zoning Officer"
                             :authority ["ATTEST_ORDINANCES"]
                             :valid-from year-start :valid-until year-end})
        (is (= :missing-authority
               (refusal #(cert/certify! node {:email "zoning@china-grove.example.gov" :statement "x"
                                              :document-hash "h" :contract-id contract-id
                                              :required-authority "CERTIFY_MINUTES"
                                              :valid-from vote-meeting :valid-until year-end}))))))))

(deftest signature-authorizations-verifies-and-decodes-office-grants
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [{:keys [clerk]} (provision! node)
          [authorization] (cert/signature-authorizations node)
          [grant] (:grants authorization)]
      (is (= (:xt/id clerk) (:did authorization)))
      (is (= "Jane Clerk" (:name authorization)))
      (is (= ["CERTIFY_MINUTES"] (:authority grant)))
      (is (true? (:verified grant)))
      (is (= year-start (:valid-from grant)))
      (is (= year-end (:valid-until grant))))))

(deftest signature-authorizations-verifies-a-nanosecond-precise-grant
  ;; XTDB stores valid time to the microsecond; the signed credential keeps
  ;; the full nanosecond Instant. Verifying at the stored bound would check
  ;; a hair before the credential's own window opens.
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [town  (identity/provision-town! node {:slug "china-grove" :name "China Grove"})
          clerk (identity/provision-officeholder! node {:email "clerk@china-grove.example.gov"
                                                        :name "Jane Clerk" :office "Town Clerk"})]
      (cert/grant-office! node (:xt/id town)
                          {:officeholder-did (:xt/id clerk) :name "Jane Clerk" :office "Town Clerk"
                           :authority ["CERTIFY_MINUTES"]
                           :valid-from (Instant/parse "2026-01-01T00:00:00.123456789Z")
                           :valid-until year-end})
      (let [[grant] (:grants (first (cert/signature-authorizations node)))]
        (is (true? (:verified grant)))
        (is (nil? (:error grant)))))))

(deftest signature-authorizations-distinguishes-revoked-from-expired
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [{:keys [town clerk]} (provision! node)
          [{grant-id :xt/id}] (credential/current-for-subject node (:xt/id clerk))]
      (credential/revoke! node grant-id (Instant/parse "2026-06-01T00:00:00Z"))
      ;; A second grant that simply ran out on its own signed validUntil.
      (cert/grant-office! node (:xt/id town)
                          {:officeholder-did (:xt/id clerk) :name "Jane Clerk" :office "Town Clerk"
                           :authority ["CERTIFY_MINUTES"]
                           :valid-from (Instant/parse "2024-01-01T00:00:00Z")
                           :valid-until (Instant/parse "2025-01-01T00:00:00Z")})
      (let [grants (:grants (first (cert/signature-authorizations node)))]
        (is (= [:expired :revoked] (mapv :status grants)))
        (is (every? :verified grants))))))

(deftest signature-authorizations-survives-a-malformed-credential
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [{:keys [town clerk]} (provision! node)]
      (xt/execute-tx node [[:put-docs {:into credential/credential-table
                                       :valid-from year-start :valid-to year-end}
                            {:xt/id          (random-uuid)
                             :issuer         (:xt/id town)
                             :subject        (:xt/id clerk)
                             :concerto/class cert/office-grant-class
                             :jwt            "not-a-jwt"}]])
      (let [grants (:grants (first (cert/signature-authorizations node)))]
        (is (= 2 (count grants)))
        (is (= 1 (count (filter :verified grants))))
        (is (some #(re-find #"could not be read" (or (:error %) "")) grants))))))

(deftest certify-checks-authority-as-of-the-events-own-time-not-now
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [{:keys [clerk]} (provision! node)
          _ (approved! node)
          [{grant-id :xt/id}] (filter #(= cert/office-grant-class (:concerto/class %))
                                      (credential/current-for-subject node (:xt/id clerk)))]
      ;; Revoke partway through the year: a meeting before the revocation is
      ;; still validly certified; one after is not, even though both calls
      ;; happen "now", long after either date.
      (credential/revoke! node grant-id (Instant/parse "2026-06-01T00:00:00Z"))
      (testing "a meeting before the revocation still checks out"
        (is (string? (cert/certify! node {:email "clerk@china-grove.example.gov"
                                          :statement "Vote certified" :document-hash "h1"
                                          :contract-id contract-id :required-authority "CERTIFY_MINUTES"
                                          :valid-from vote-meeting :valid-until year-end}))))
      (testing "a meeting after the revocation does not"
        (is (thrown? clojure.lang.ExceptionInfo
                     (cert/certify! node {:email "clerk@china-grove.example.gov"
                                          :statement "Vote certified" :document-hash "h2"
                                          :contract-id contract-id :required-authority "CERTIFY_MINUTES"
                                          :valid-from (Instant/parse "2026-09-01T00:00:00Z")
                                          :valid-until year-end})))))))

(deftest the-vote-then-minutes-case
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [{:keys [clerk]} (provision! node)
          did (:xt/id clerk)
          _ (approved! node)

          vote-jwt (cert/certify! node {:email "clerk@china-grove.example.gov"
                                        :statement "The council voted 4-1 to approve the manager's contract"
                                        :document-hash "sha256:vote"
                                        :contract-id contract-id
                                        :required-authority "CERTIFY_MINUTES"
                                        :valid-from vote-meeting :valid-until year-end})

          minutes-jwt (cert/certify! node {:email "clerk@china-grove.example.gov"
                                           :statement "Minutes of the March 4 meeting were approved as written"
                                           :document-hash "sha256:minutes"
                                           :contract-id contract-id
                                           :required-authority "CERTIFY_MINUTES"
                                           :valid-from next-meeting :valid-until year-end
                                           :evidence vote-jwt})

          clerk-public-key (:identity/public-key (identity/by-did node did))
          vote-subject (vc/verify clerk-public-key vote-jwt did did vote-meeting)
          minutes-subject (vc/verify clerk-public-key minutes-jwt did did next-meeting)]
      (testing "the vote certification"
        (is (= contract-id (get vote-subject "contractId"))))
      (testing "the minutes approval names the vote certification as evidence"
        (is (= vote-jwt (get minutes-subject "evidence"))))
      (testing "the approval and both attestations are recorded, alongside the office grant that authorized them"
        (let [records (credential/for-subject node did)]
          (is (= [cert/office-grant-class cert/attestation-class cert/attestation-class cert/attestation-class]
                 (map :concerto/class records)))))

      (testing "attestations-for-contract shows the Board approval, then both, decoded, oldest first, evidence intact"
        (let [[approval vote minutes] (cert/attestations-for-contract node contract-id)]
          (is (= "The Town Council approved the Town Manager's employment contract" (get approval "statement")))
          (is (= "The council voted 4-1 to approve the manager's contract" (get vote "statement")))
          (is (= "Minutes of the March 4 meeting were approved as written" (get minutes "statement")))
          (is (= vote-jwt (get minutes "evidence")))
          (is (= did (get vote "issuer") (get minutes "issuer")))
          (is (= vote-meeting (get vote "valid-from")))
          (is (= next-meeting (get minutes "valid-from")))
          (is (= vote-jwt (get vote "jwt")))
          (is (= minutes-jwt (get minutes "jwt"))))))))

(deftest the-board-approval-is-certified-by-the-clerk
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (provision! node)
    (testing "nothing can be attested before the Board has approved"
      (is (= :no-transition
             (refusal #(cert/certify! node {:email "clerk@china-grove.example.gov" :statement "x"
                                            :document-hash "h" :contract-id contract-id
                                            :required-authority "CERTIFY_MINUTES"
                                            :valid-from vote-meeting :valid-until year-end})))))
    (testing "an uncertified approval -- as the trigger endpoint would send it -- is refused"
      (is (= :certification-required
             (refusal #(execute/trigger! node contract-id
                                         {"$class" "com.trustblocks.municipal.employment@1.0.0.BoardApprovalRequest"
                                          "requestedAt" me/board-meeting-date}
                                         me/board-meeting-date)))))
    (testing "an officeholder without CERTIFY_MINUTES cannot approve it"
      (identity/provision-officeholder! node {:email "nobody@china-grove.example.gov"
                                              :name "No One" :office "Nobody"})
      (is (= :missing-authority (refusal #(me/approve! node "nobody@china-grove.example.gov")))))
    (testing "the Clerk can, once"
      (is (= "APPROVED" (get-in (approved! node) [:state "status"])))
      (is (= :no-transition
             (refusal #(me/approve! node "clerk@china-grove.example.gov" :at "2026-01-05T00:00:00.000Z")))))))

(deftest an-attestation-can-be-required-to-rest-on-something-the-system-holds
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (provision! node)
    (approved! node)
    (let [root (str (java.nio.file.Files/createTempDirectory "docs" (make-array java.nio.file.attribute.FileAttribute 0)))
          doc  (document/store! node root (.getBytes "inspection report" "UTF-8") :filename "report.pdf")
          args {:email "clerk@china-grove.example.gov" :statement "Received"
                :contract-id contract-id :required-authority "CERTIFY_MINUTES"
                :valid-from vote-meeting :valid-until year-end}]
      (testing "what names a stored file or release is known; anything else is not"
        (is (= :document (cert/subject-kind node (:xt/id doc))))
        (is (nil? (cert/subject-kind node "typed by hand")))
        (is (nil? (cert/subject-kind node nil))))
      (testing "strict, an unknown reference is refused before anything is signed"
        (is (= :unknown-subject
               (:trustblocks/error (ex-data (try (cert/certify! node (assoc args :document-hash "typed by hand" :require-known-subject? true))
                                                 (catch clojure.lang.ExceptionInfo e e)))))))
      (testing "a stored file is accepted, and the default still takes a typed reference"
        (is (string? (cert/certify! node (assoc args :document-hash (:xt/id doc) :require-known-subject? true))))
        (is (string? (cert/certify! node (assoc args :document-hash "typed by hand"))))))))
