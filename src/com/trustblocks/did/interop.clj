(ns com.trustblocks.did.interop
  "The only namespace that imports java.security, com.nimbusds.*, or
  foundation.identity.did directly. Everything above this reads and writes
  raw bytes, Clojure maps, and did:key/did:web strings; this is where those
  meet the JVM crypto and JSON-LD object APIs that actually produce and
  check a signature or a DID document.

  Ed25519 keypair generation is plain java.security -- JDK 15+ has a native
  Ed25519 provider, so generating one touches neither BouncyCastle nor
  Tink. Signing does need Tink: nimbus-jose-jwt's Ed25519Signer/
  Ed25519Verifier are Tink-backed in every version checked (9.37.3 and
  10.0.2), with or without BouncyCastle registered as a provider -- not a
  choice this namespace is making, just what actually runs underneath
  :biff.auth (see deps.edn)."
  (:require [clojure.walk :as walk])
  (:import [com.nimbusds.jose JWSAlgorithm JWSHeader$Builder]
           [com.nimbusds.jose.crypto Ed25519Signer Ed25519Verifier]
           [com.nimbusds.jose.jwk Curve OctetKeyPair$Builder]
           [com.nimbusds.jose.util Base64URL]
           [com.nimbusds.jwt JWTClaimsSet$Builder SignedJWT]
           [foundation.identity.did DIDDocument VerificationMethod]
           [java.net URI]
           [java.security KeyPairGenerator]
           [java.util Arrays]))

;; ---------------------------------------------------------------- keys

(defn- raw-key-bytes
  "The last 32 bytes of a java.security key's own encoding -- X.509 for a
  public key, PKCS8 for a private one. Both wrap the same 32-byte Ed25519
  point/seed in a short, fixed ASN.1 prefix that differs between the two
  encodings but is always shorter than the key material itself, so taking
  the tail is safe and stable across both."
  [^bytes encoded]
  (Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded)))

(defn generate-keypair
  "A fresh Ed25519 keypair, as raw 32-byte {:public :private}. Raw rather
  than any Java key object, because everything above this namespace --
  did:key encoding, XTDB storage -- wants bytes, not a type that only
  means something to this one library stack."
  []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))]
    {:public  (raw-key-bytes (.getEncoded (.getPublic kp)))
     :private (raw-key-bytes (.getEncoded (.getPrivate kp)))}))

(defn- octet-key-pair
  "Nimbus's own JWK shape for an OKP (Ed25519/X25519) key, built directly
  from raw bytes -- not via OctetKeyPairGenerator, which pulls in Tink for
  generation too and gives us no way to import a key we already made with
  plain java.security. `private` is optional: omit it for a
  verification-only (public) key."
  ([public] (octet-key-pair public nil))
  ([^bytes public ^bytes private]
   (cond-> (OctetKeyPair$Builder. Curve/Ed25519 (Base64URL/encode public))
     private (.d (Base64URL/encode private))
     true    (.build))))

;; ------------------------------------------------------------ signing

(defn- ->java
  "Clojure data -> the java.util.Map/List/String/Number shape Nimbus's JSON
  serializer expects for a claim value. Clojure's own map and vector types
  already implement those interfaces, which is why the naive version of
  this (passing a Clojure map straight to .claim) mostly works -- but a
  keyword anywhere in the tree (a stray :key instead of \"key\") serializes
  as `:key` in the JSON, silently, which is the kind of bug that only
  shows up when something else fails to parse the credential later."
  [x]
  (walk/postwalk (fn [v] (cond-> v (keyword? v) name)) x))

(defn sign-jws
  "An Ed25519-signed (EdDSA) JWT over `claims`, a flat map of JWT claim
  names to values -- string keys, and values that may themselves be nested
  maps/vectors (a VC's own JSON structure lives under one claim, see
  com.trustblocks.did.vc). `keypair` is the signer's own {:public :private},
  the same shape generate-keypair returns.

  Takes the whole keypair rather than just the private key: EdDSA signing
  needs the private seed, but nothing here confirms that Nimbus's signer
  never reads the JWK's public component too, and building the JWK with a
  fabricated or mismatched public half to find out empirically is not a
  chance worth taking with a signature. The real pair costs nothing extra
  to pass, since a caller always has both."
  [{:keys [public private]} claims]
  (let [okp    (octet-key-pair public private)
        header (-> (JWSHeader$Builder. JWSAlgorithm/EdDSA) (.build))
        builder (JWTClaimsSet$Builder.)
        _      (doseq [[k v] claims] (.claim builder (name k) (->java v)))
        jwt    (SignedJWT. header (.build builder))]
    (.sign jwt (Ed25519Signer. okp))
    (.serialize jwt)))

(defn jws-claims
  "The claims inside a signed JWT, without checking the signature -- the
  first step of verification is always 'read who this claims to be from',
  which has to happen before there is a key to check the signature
  against. Never trust the result of this alone; see verify-jws."
  [^String jwt]
  (into {}
        (map (fn [[k v]] [(keyword k) v]))
        (.getClaims (.getJWTClaimsSet (SignedJWT/parse jwt)))))

(defn verify-jws
  "Whether `jwt` is validly signed by the holder of `public` (raw 32-byte
  Ed25519 public key). Checks the signature only -- not expiry, not who
  `public` actually belongs to; those are com.trustblocks.did.vc's job,
  once it has resolved whose key this is supposed to be."
  [^bytes public ^String jwt]
  (.verify (SignedJWT/parse jwt) (Ed25519Verifier. (octet-key-pair public))))

;; ------------------------------------------------------------ DID documents

(defn did-document
  "A minimal single-key DID document, as a JSON string: one Ed25519
  verification method, referenced for both authentication and
  assertionMethod -- the second is what matters here, since it is the
  role a resolver checks before trusting a credential `did` issued.

  `public-key-multibase` is com.trustblocks.did.key/multibase's output for
  the same key -- the same 'z...' form did:key itself uses, so a did:web
  document's key and a did:key identifier for the same key are visibly
  the same bytes."
  [{:keys [did public-key-multibase]}]
  (let [id (URI/create did)
        vm (-> (VerificationMethod/builder)
               (.id (URI/create (str id "#key-1")))
               (.type "Ed25519VerificationKey2020")
               (.controller id)
               (.publicKeyMultibase public-key-multibase)
               (.build))
        doc (-> (DIDDocument/builder)
                (.id id)
                (.verificationMethod vm)
                (.authenticationVerificationMethod vm)
                (.assertionMethodVerificationMethod vm)
                (.build))]
    (.toJson doc true)))
