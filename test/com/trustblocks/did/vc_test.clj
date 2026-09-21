(ns com.trustblocks.did.vc-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.interop :as interop]
            [com.trustblocks.did.key :as key]
            [com.trustblocks.did.vc :as vc])
  (:import [java.time Instant]))

(def ^:private issuer-kp (interop/generate-keypair))
(def ^:private issuer-did "did:web:trustblocks.com:towns:china-grove")
(def ^:private subject-kp (interop/generate-keypair))
(def ^:private subject-did (key/from-public-key (:public subject-kp)))

(def ^:private start (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private end (Instant/parse "2027-01-01T00:00:00Z"))
(def ^:private during (Instant/parse "2026-06-01T00:00:00Z"))

(defn- office-grant [did]
  {"id" did "name" "Jane Clerk" "office" "Town Clerk"
   "authority" ["certify-minutes" "attest-ordinances"]})

(defn- issue-office-grant []
  (vc/issue issuer-kp {:issuer issuer-did :subject subject-did
                       :credential-subject (office-grant subject-did)
                       :valid-from start :valid-until end
                       :types ["OfficeGrant"]}))

(deftest issues-and-verifies
  (let [jwt (issue-office-grant)
        subject (vc/verify (:public issuer-kp) jwt issuer-did subject-did during)]
    (is (= "Town Clerk" (get subject "office")))
    (is (= ["certify-minutes" "attest-ordinances"] (seq (get subject "authority"))))))

(deftest refuses-a-credential-subject-id-that-does-not-match-the-subject
  (is (thrown? clojure.lang.ExceptionInfo
               (vc/issue issuer-kp {:issuer issuer-did :subject subject-did
                                    :credential-subject (office-grant "did:key:zSomeoneElse")
                                    :valid-from start :valid-until end}))))

(deftest verify-checks-the-signature-not-just-the-shape
  (let [jwt (issue-office-grant)
        an-impostor (interop/generate-keypair)]
    (is (= {:error "signature does not verify"}
           (vc/verify (:public an-impostor) jwt issuer-did subject-did during)))))

(deftest verify-checks-issuer-and-subject-against-the-envelope-not-just-the-body
  (let [jwt (issue-office-grant)]
    (testing "wrong issuer"
      (is (= {:error "issuer does not match the JWT's iss claim"}
             (vc/verify (:public issuer-kp) jwt "did:web:trustblocks.com:towns:someone-else" subject-did during))))
    (testing "wrong subject"
      (is (= {:error "subject does not match the JWT's sub claim"}
             (vc/verify (:public issuer-kp) jwt issuer-did "did:key:zSomeoneElse" during))))))

(deftest evidence-carries-a-referenced-credentials-jwt-through
  ;; The Clerk's certification act, naming the office grant behind it --
  ;; the two-credential pattern from model/identity.cto's own docstring.
  (let [office-jwt (issue-office-grant)
        attestation-jwt (vc/issue subject-kp
                                  {:issuer subject-did :subject subject-did
                                   :credential-subject {"id" subject-did
                                                        "documentHash" "sha256:abc"
                                                        "statement" "Minutes certified as accurate"}
                                   :valid-from start :valid-until end
                                   :types ["Attestation"]
                                   :evidence office-jwt})
        subject (vc/verify (:public subject-kp) attestation-jwt subject-did subject-did during)]
    (is (= "Minutes certified as accurate" (get subject "statement")))
    (is (= office-jwt (get subject "evidence")))))

(deftest verify-enforces-the-validity-window
  (let [jwt (issue-office-grant)]
    (testing "before validFrom"
      (is (= "not yet valid"
             (:error (vc/verify (:public issuer-kp) jwt issuer-did subject-did
                                (Instant/parse "2025-06-01T00:00:00Z"))))))
    (testing "after validUntil"
      (is (= "expired"
             (:error (vc/verify (:public issuer-kp) jwt issuer-did subject-did
                                (Instant/parse "2027-06-01T00:00:00Z"))))))))
