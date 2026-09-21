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
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.time Instant]))

(def ^:private year-start (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private year-end (Instant/parse "2027-01-01T00:00:00Z"))
(def ^:private vote-meeting (Instant/parse "2026-03-04T19:00:00Z"))
(def ^:private next-meeting (Instant/parse "2026-04-01T19:00:00Z"))

(defn- populate! [node]
  (store/put-model! node "model/identity.cto"))

(defn- provision! [node]
  (let [town (identity/provision-town! node {:slug "china-grove" :name "China Grove"})
        clerk (identity/provision-officeholder! node {:email "clerk@china-grove.example.gov"
                                                      :name "Jane Clerk" :office "Town Clerk"})]
    (cert/grant-office! node (:xt/id town)
                        {:officeholder-did (:xt/id clerk) :name "Jane Clerk" :office "Town Clerk"
                         :authority ["CERTIFY_MINUTES"]
                         :valid-from year-start :valid-until year-end})
    {:town town :clerk clerk}))

(deftest certify-refuses-when-not-authorized
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (testing "an unknown email"
      (is (thrown? clojure.lang.ExceptionInfo
                   (cert/certify! node {:email "nobody@example.gov" :statement "x" :document-hash "h"
                                        :required-authority "CERTIFY_MINUTES"
                                        :valid-from vote-meeting :valid-until year-end}))))

    (testing "an officeholder with no office grant at all"
      (identity/provision-officeholder! node {:email "nobody@china-grove.example.gov"
                                              :name "No One" :office "Nobody"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (cert/certify! node {:email "nobody@china-grove.example.gov" :statement "x"
                                        :document-hash "h" :required-authority "CERTIFY_MINUTES"
                                        :valid-from vote-meeting :valid-until year-end}))))

    (testing "a grant that does not name the required authority"
      (let [{:keys [town]} (provision! node)
            zoning (identity/provision-officeholder! node {:email "zoning@china-grove.example.gov"
                                                           :name "Sam Zoning" :office "Zoning Officer"})]
        (cert/grant-office! node (:xt/id town)
                            {:officeholder-did (:xt/id zoning) :name "Sam Zoning" :office "Zoning Officer"
                             :authority ["ATTEST_ORDINANCES"]
                             :valid-from year-start :valid-until year-end})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not include the required authority"
                              (cert/certify! node {:email "zoning@china-grove.example.gov" :statement "x"
                                                   :document-hash "h" :required-authority "CERTIFY_MINUTES"
                                                   :valid-from vote-meeting :valid-until year-end})))))))

(deftest certify-checks-authority-as-of-the-events-own-time-not-now
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [{:keys [clerk]} (provision! node)
          [{grant-id :xt/id}] (credential/current-for-subject node (:xt/id clerk))]
      ;; Revoke partway through the year: a meeting before the revocation is
      ;; still validly certified; one after is not, even though both calls
      ;; happen "now", long after either date.
      (credential/revoke! node grant-id (Instant/parse "2026-06-01T00:00:00Z"))
      (testing "a meeting before the revocation still checks out"
        (is (string? (cert/certify! node {:email "clerk@china-grove.example.gov"
                                          :statement "Vote certified" :document-hash "h1"
                                          :required-authority "CERTIFY_MINUTES"
                                          :valid-from vote-meeting :valid-until year-end}))))
      (testing "a meeting after the revocation does not"
        (is (thrown? clojure.lang.ExceptionInfo
                     (cert/certify! node {:email "clerk@china-grove.example.gov"
                                          :statement "Vote certified" :document-hash "h2"
                                          :required-authority "CERTIFY_MINUTES"
                                          :valid-from (Instant/parse "2026-09-01T00:00:00Z")
                                          :valid-until year-end})))))))

(deftest the-vote-then-minutes-case
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (let [{:keys [clerk]} (provision! node)
          did (:xt/id clerk)

          vote-jwt (cert/certify! node {:email "clerk@china-grove.example.gov"
                                        :statement "The council voted 4-1 to approve the manager's contract"
                                        :document-hash "sha256:vote"
                                        :contract-id "china-grove-gover-2025"
                                        :required-authority "CERTIFY_MINUTES"
                                        :valid-from vote-meeting :valid-until year-end})

          minutes-jwt (cert/certify! node {:email "clerk@china-grove.example.gov"
                                           :statement "Minutes of the March 4 meeting were approved as written"
                                           :document-hash "sha256:minutes"
                                           :contract-id "china-grove-gover-2025"
                                           :required-authority "CERTIFY_MINUTES"
                                           :valid-from next-meeting :valid-until year-end
                                           :evidence vote-jwt})

          clerk-public-key (:identity/public-key (identity/by-did node did))
          vote-subject (vc/verify clerk-public-key vote-jwt did did vote-meeting)
          minutes-subject (vc/verify clerk-public-key minutes-jwt did did next-meeting)]
      (testing "the vote certification"
        (is (= "china-grove-gover-2025" (get vote-subject "contractId"))))
      (testing "the minutes approval names the vote certification as evidence"
        (is (= vote-jwt (get minutes-subject "evidence"))))
      (testing "both attestations are recorded, alongside the office grant that authorized them"
        (let [records (credential/for-subject node did)]
          (is (= [cert/office-grant-class cert/attestation-class cert/attestation-class]
                 (map :concerto/class records))))))))
