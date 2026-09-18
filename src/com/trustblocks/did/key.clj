(ns com.trustblocks.did.key
  "did:key -- an identifier that IS its own public key, needing no document
  fetched from anywhere. `did:key:z` + base58(multicodec-prefix + raw key).
  Resolution is the inverse: read the prefix and key straight back out of
  the identifier. No network call, no registry, no did:web-style hosting
  question -- which is exactly why officeholders get did:key rather than
  did:web: nobody has to serve a document on their behalf.

  This is pure data transformation, not interop; see com.trustblocks.did.
  interop for what actually produces an Ed25519 keypair."
  (:require [com.trustblocks.did.base58 :as base58]))

(def ^:private ed25519-multicodec
  "The `ed25519-pub` multicodec code (0xed) as a varint -- two bytes,
  because 0xed exceeds the 7 bits a single varint byte holds. Not
  arbitrary: this is what makes a did:key for an Ed25519 key start with
  the well-known 'z6Mk' four characters every did:key implementation
  recognizes on sight."
  [(unchecked-byte 0xed) (unchecked-byte 0x01)])

(defn from-public-key
  "The did:key for a raw 32-byte Ed25519 public key."
  [^bytes public-key]
  (when-not (= 32 (alength public-key))
    (throw (ex-info "Ed25519 public key must be 32 bytes" {:length (alength public-key)})))
  (str "did:key:z" (base58/encode (byte-array (concat ed25519-multicodec public-key)))))

(defn public-key
  "The raw 32-byte Ed25519 public key inside a did:key, or throws if `did`
  isn't a did:key for an Ed25519 key -- the only kind this project issues."
  [did]
  (when-not (and (string? did) (.startsWith ^String did "did:key:z"))
    (throw (ex-info "Not an Ed25519 did:key" {:did did})))
  (let [decoded (base58/decode (subs did (count "did:key:z")))]
    (when-not (and (= 34 (count decoded))
                   (= (seq ed25519-multicodec) (take 2 decoded)))
      (throw (ex-info "Not an Ed25519 did:key" {:did did})))
    (byte-array (drop 2 decoded))))
