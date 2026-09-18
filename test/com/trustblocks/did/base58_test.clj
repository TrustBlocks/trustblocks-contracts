(ns com.trustblocks.did.base58-test
  "The real check here is against Ed25519 multikey vectors from the Data
  Integrity EdDSA Cryptosuites spec's own Annex A -- not just
  encode/decode agreeing with each other, which would pass even if both
  were wrong the same way."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.base58 :as base58]))

;; Annex A.1 and A.2 multikeys: 'z' (multibase base58btc) + 0xed01
;; (Ed25519 public key multicodec) + the raw 32-byte key. Decoding one,
;; stripping the known 2-byte prefix, and re-encoding must reproduce the
;; exact original string -- an external oracle these vectors are correct
;; Ed25519 multikeys, checked with no dependency on our own encoder being
;; right first.
(def ^:private annex-vectors
  ["z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doY"
   "z6Mkff3F4VMDGbMbMtgRyXMrgr7qyxaKsPo7QEPQ2AkNrx2X"])

(deftest decode-then-encode-reproduces-real-spec-vectors
  (doseq [multikey annex-vectors]
    (testing multikey
      (let [decoded (base58/decode (subs multikey 1))]
        (is (= 34 (count decoded)) "2-byte multicodec prefix + 32-byte Ed25519 key")
        (is (= [0xed 0x01] (map #(bit-and % 0xff) (take 2 decoded)))
            "the Ed25519 public-key multicodec prefix")
        (is (= multikey (str "z" (base58/encode decoded))))))))

(deftest leading-zero-bytes-round-trip
  ;; The one edge case a naive BigInteger-only encoding gets wrong: a
  ;; leading 0x00 byte carries no numeric weight, so without special
  ;; handling it would silently vanish instead of becoming a leading '1'.
  (doseq [bs [(byte-array [0 0 1 2 3])
              (byte-array [0])
              (byte-array (repeat 5 (byte 0)))
              (byte-array (range 0 40))]]
    (is (= (seq bs) (seq (base58/decode (base58/encode bs))))
        (str "round-trips " (vec bs)))))

(deftest non-base58-character-is-refused
  (is (thrown? clojure.lang.ExceptionInfo (base58/decode "0OIl"))))
