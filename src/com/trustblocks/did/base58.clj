(ns com.trustblocks.did.base58
  "Base58 (Bitcoin/IPFS alphabet), the encoding multibase's 'z' prefix means.

  did:key is `did:key:z` + base58(multicodec-prefix + raw public key) -- see
  com.trustblocks.did.key. Written by hand rather than pulled in from a
  library: it is a genuinely small algorithm, and the obvious place to get
  it from a dependency, bitcoinj, is exactly the multi-hundred-file Bitcoin
  protocol implementation com.trustblocks.did.vc's docstring explains this
  project turned down elsewhere for a feature that explicitly must not
  touch a blockchain. One function-length algorithm is a better trade
  than that.

  Tested against real Ed25519 multikey vectors from the Data Integrity
  EdDSA Cryptosuites spec's own Annex, not just self-consistency -- see
  base58_test.clj."
  (:import [java.math BigInteger]))

(def ^:private alphabet
  "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(def ^:private base (BigInteger/valueOf 58))

(defn encode
  "Bytes -> base58 string. A leading 0x00 byte becomes a leading '1', same
  as every other Base58 implementation -- otherwise it would vanish (0 has
  no effect on the numeric value), silently shortening the encoding of any
  key whose first byte happens to be zero."
  [^bytes bs]
  (let [zeros (count (take-while zero? bs))]
    (if (= zeros (alength bs))
      (apply str (repeat zeros \1))
      (loop [n    (BigInteger. 1 bs)
             digits ()]
        (if (zero? (.signum n))
          (str (apply str (repeat zeros \1)) (apply str digits))
          (let [[q r] (.divideAndRemainder n base)]
            (recur q (cons (.charAt alphabet (.intValue r)) digits))))))))

(defn decode
  "base58 string -> bytes. The inverse of encode, including leading-1 ->
  leading-zero-byte."
  [^String s]
  (let [zeros (count (take-while #(= \1 %) s))]
    (if (= zeros (count s))
      (byte-array zeros)
      (let [n (reduce (fn [acc ch]
                        (let [i (.indexOf alphabet (int ch))]
                          (when (neg? i)
                            (throw (ex-info "Not a base58 character" {:char ch :string s})))
                          (.add (.multiply acc base) (BigInteger/valueOf i))))
                      BigInteger/ZERO
                      (drop zeros s))
            magnitude (.toByteArray n)
            ;; BigInteger.toByteArray is two's-complement and prepends a
            ;; sign byte (0x00) whenever the high bit of the true magnitude
            ;; is set, to keep the value from reading as negative. That
            ;; byte isn't part of the decoded data.
            magnitude (if (and (< 1 (alength magnitude)) (zero? (aget magnitude 0)))
                       (java.util.Arrays/copyOfRange magnitude 1 (alength magnitude))
                       magnitude)]
        (byte-array (concat (repeat zeros (byte 0)) magnitude))))))
