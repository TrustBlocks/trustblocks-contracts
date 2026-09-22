(ns com.trustblocks.xtdb.credential-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.interop :as interop]
            [com.trustblocks.did.key :as key]
            [com.trustblocks.did.vc :as vc]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.time Instant]))

(def ^:private town-kp (interop/generate-keypair))
(def ^:private town-did "did:web:trustblocks.com:towns:china-grove")
(def ^:private clerk-kp (interop/generate-keypair))
(def ^:private clerk-did (key/from-public-key (:public clerk-kp)))

(def ^:private start (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private end (Instant/parse "2027-01-01T00:00:00Z"))
(def ^:private during (Instant/parse "2026-06-01T00:00:00Z"))

(defn- office-grant []
  {"$class" "com.trustblocks.identity@1.0.0.OfficeGrant"
   "id" clerk-did "name" "Jane Clerk" "office" "Town Clerk"
   "authority" ["CERTIFY_MINUTES" "ATTEST_ORDINANCES"]})

(defn- issue-office-grant! [node]
  (credential/issue! node town-kp
                     {:issuer town-did :subject clerk-did
                      :credential-subject (office-grant)
                      :valid-from start :valid-until end}))

(defn- populate! [node]
  (store/put-model! node "model/identity.cto"))

(deftest issue-validates-the-credential-subject-against-its-own-model
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (testing "a valid OfficeGrant issues cleanly"
      (is (string? (issue-office-grant! node))))
    (testing "an invalid one is refused before it is ever signed"
      (is (thrown? clojure.lang.ExceptionInfo
                   (credential/issue! node town-kp
                                      {:issuer town-did :subject clerk-did
                                       :credential-subject (dissoc (office-grant) "office")
                                       :valid-from start :valid-until end}))))))

(deftest current-and-as-of-follow-the-credentials-own-validity-window
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (issue-office-grant! node)
    (testing "before validFrom, nothing is current"
      (is (empty? (credential/as-of-for-subject node clerk-did (Instant/parse "2025-06-01T00:00:00Z")))))
    (testing "during the window, it is current"
      (let [[record] (credential/as-of-for-subject node clerk-did during)]
        (is (= "com.trustblocks.identity@1.0.0.OfficeGrant" (:concerto/class record)))
        (is (= town-did (:issuer record)))
        (is (= clerk-did (:subject record)))))
    (testing "after validUntil, it is no longer current"
      (is (empty? (credential/as-of-for-subject node clerk-did (Instant/parse "2027-06-01T00:00:00Z")))))
    (testing "for-subject sees it regardless of valid time"
      (is (= 1 (count (credential/for-subject node clerk-did)))))))

(deftest the-stored-jwt-actually-verifies
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (issue-office-grant! node)
    (let [[record] (credential/current-for-subject node clerk-did)
          subject (vc/verify (:public town-kp) (:jwt record) town-did clerk-did during)]
      (is (= "Town Clerk" (get subject "office"))))))

(deftest revoke-ends-validity-early-without-erasing-history
  (with-open [node (xt.node/start-node)]
    (populate! node)
    (issue-office-grant! node)
    (let [[{credential-id :xt/id}] (credential/current-for-subject node clerk-did)
          revoked-at (Instant/parse "2026-07-01T00:00:00Z")]
      (credential/revoke! node credential-id revoked-at)
      (testing "still valid just before the revocation"
        (is (= 1 (count (credential/as-of-for-subject node clerk-did
                                                      (Instant/parse "2026-06-30T23:59:59Z"))))))
      (testing "gone as of the revocation instant"
        (is (empty? (credential/as-of-for-subject node clerk-did revoked-at))))
      (testing "gone for the rest of what would have been its validity"
        (is (empty? (credential/current-for-subject node clerk-did))))
      (testing "for-subject (all valid time) still shows it existed"
        (is (= 1 (count (credential/for-subject node clerk-did))))))))

(deftest for-contract-finds-only-credentials-naming-that-contract
  (with-open [node (xt.node/start-node)]
    (populate! node)
    ;; The office grant names no contract at all -- it should never appear.
    (issue-office-grant! node)
    (credential/issue! node clerk-kp
                       {:issuer clerk-did :subject clerk-did
                        :credential-subject {"$class" "com.trustblocks.identity@1.0.0.Attestation"
                                             "id" clerk-did "documentHash" "sha256:x"
                                             "statement" "the vote happened"
                                             "contractId" "china-grove-gover-2025"
                                             "certifiedAt" (str during)}
                        :valid-from during :valid-until end})
    (testing "the attestation shows up under its contract"
      (let [[record] (credential/for-contract node "china-grove-gover-2025")]
        (is (= "com.trustblocks.identity@1.0.0.Attestation" (:concerto/class record)))
        (is (= during (:valid-from record)))))
    (testing "an unrelated contract id finds nothing"
      (is (empty? (credential/for-contract node "some-other-contract"))))))
