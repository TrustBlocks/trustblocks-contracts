(ns com.trustblocks.did.key-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.key :as key]))

;; The same real Data Integrity EdDSA Cryptosuites Annex vectors
;; base58-test checks the encoding with, now checked end to end as actual
;; did:key identifiers.
(def ^:private annex-vectors
  ["did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doY"
   "did:key:z6Mkff3F4VMDGbMbMtgRyXMrgr7qyxaKsPo7QEPQ2AkNrx2X"])

(deftest round-trips-real-spec-vectors
  (doseq [did annex-vectors]
    (testing did
      (is (= did (key/from-public-key (key/public-key did)))))))

(deftest from-public-key-rejects-the-wrong-length
  (is (thrown? clojure.lang.ExceptionInfo (key/from-public-key (byte-array 31))))
  (is (thrown? clojure.lang.ExceptionInfo (key/from-public-key (byte-array 33)))))

(deftest public-key-rejects-non-did-key-strings
  (doseq [not-a-key ["did:web:trustblocks.com"
                     "did:key:zNotBase58!!!"
                     "not a did at all"]]
    (is (thrown? clojure.lang.ExceptionInfo (key/public-key not-a-key)))))

(deftest different-keys-produce-different-dids
  (is (not= (key/from-public-key (byte-array (range 32)))
            (key/from-public-key (byte-array (reverse (range 32)))))))
