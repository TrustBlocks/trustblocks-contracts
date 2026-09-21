(ns com.trustblocks.did.vc
  "W3C Verifiable Credentials, in the VC-JWT proof format rather than the
  Linked-Data-Proof/Data-Integrity one.

  The other Java library for this, com.danubetech/verifiable-credentials-
  java, supports both proof formats, but LD-Proofs need RDF
  canonicalization, and that pulls in com.danubetech/key-formats-java,
  which requires (not optionally) org.bitcoinj:bitcoinj-core, org.web3j:
  crypto, and a native libsodium binding -- a full Bitcoin and Ethereum
  crypto stack, and a JNI dependency this project otherwise has none of,
  none of it reachable from Ed25519/did:key/did:web, and unavoidable via
  :exclusions since a second, independent dependency
  (com.github.multiformats:java-multibase, needing JitPack on top of
  Danube Tech's own repo) fails the same way regardless. Checked by hand
  against the real dependency tree while choosing this design, not by
  reading the library's README.

  VC-JWT is a complete, spec-recognized profile of the same data model
  without any of that: a JWT whose claims include a `vc` claim shaped
  like the W3C credential, signed and verified with nothing more than
  com.trustblocks.did.interop already provides. The only thing kept from
  Danube Tech is decentralized-identity/did-common-java, for DID document
  objects -- clean on its own, no blockchain, no JitPack (see deps.edn).
  com.trustblocks.did.base58 exists for the same reason: the obvious place
  to get Base58 from a dependency is bitcoinj, which is exactly what this
  whole design avoids pulling in.

  A credential here is always: who issued it (`issuer`, a DID), who it's
  about (`credentialSubject.id`, a DID), what's being claimed
  (`credentialSubject`'s own fields -- Concerto-validated by the caller
  before it reaches this namespace, see com.trustblocks.xtdb.credential),
  and when it holds (`validFrom`/`validUntil`). Nothing here decides what
  a credentialSubject may contain; a VC's payload is exactly as open as
  the caller wants it to be, and this namespace only wraps and unwraps it."
  (:require [com.trustblocks.did.interop :as interop])
  (:import [java.time Instant]))

(def context
  "The base W3C Verifiable Credentials v2 context. A caller adding its own
  vocabulary appends further context entries; this is the one every
  credential this project issues starts with."
  "https://www.w3.org/ns/credentials/v2")

(defn- rfc3339 [^Instant instant]
  (.toString instant))

(defn issue
  "Sign a Verifiable Credential as a JWT. Returns the JWT string -- the
  credential *is* this string; nothing else needs to be kept to present or
  re-verify it later, though com.trustblocks.xtdb.credential also stores
  it for exactly that reason.

  `issuer` and `subject` are DIDs (strings). `credential-subject` is
  whatever claim the caller has already validated -- an OfficeGrant, an
  Attestation, anything with its own Concerto model -- and must itself
  carry `\"id\"` set to `subject`, since credentialSubject.id is where the
  W3C data model actually records who a credential is about; `issuer` and
  `subject`/`sub` here are the JWT-envelope mirror of that, used to find
  the right key before the JWT body is even parsed as a VC.

  `valid-from`/`valid-until` are `java.time.Instant`s. `types` are
  additional credential types beyond the required `VerifiableCredential`,
  e.g. `[\"OfficeGrant\"]`.

  `evidence`, when given, is another credential (its JWT string) this one
  rests on -- the office-grant a certification act names as the authority
  behind it. It is a sibling of credentialSubject rather than a field
  inside it: an OfficeGrant is evidence *for* an Attestation, not part of
  what the Attestation itself claims. See model/identity.cto."
  [issuer-keypair {:keys [issuer subject credential-subject valid-from valid-until types evidence]}]
  (when-not (= subject (get credential-subject "id"))
    (throw (ex-info "credential-subject id must match subject"
                    {:subject subject :credential-subject-id (get credential-subject "id")})))
  (interop/sign-jws
   issuer-keypair
   {:iss issuer
    :sub subject
    :vc  (cond-> {"@context"          [context]
                  "type"              (into ["VerifiableCredential"] types)
                  "issuer"            issuer
                  "validFrom"         (rfc3339 valid-from)
                  "validUntil"        (rfc3339 valid-until)
                  "credentialSubject" credential-subject}
           evidence (assoc "evidence" evidence))}))

(defn- vc-problem
  "Why `vc` cannot be trusted as issued by `issuer` and about `subject`, or
  nil. Structural checks a signature alone doesn't cover: the JWT envelope
  and the VC body have to agree about who signed it and who it's about,
  or a verifier checking only one half could be fooled by a credential
  whose envelope says one thing and whose body says another."
  [{:keys [iss sub vc]} issuer subject]
  (cond
    (not= issuer iss)      "issuer does not match the JWT's iss claim"
    (not= subject sub)     "subject does not match the JWT's sub claim"
    (not= issuer (get vc "issuer"))            "issuer does not match vc.issuer"
    (not= subject (get-in vc ["credentialSubject" "id"])) "subject does not match vc.credentialSubject.id"
    (not (some #{"VerifiableCredential"} (get vc "type"))) "vc.type does not include VerifiableCredential"))

(defn verify
  "The credentialSubject inside `jwt`, if it is validly signed by
  `issuer-public-key` (raw 32-byte Ed25519), issued by `issuer`, about
  `subject`, and currently within its validFrom/validUntil window as of
  `now` (an Instant) -- otherwise {:error \"...\"}.

  Callers resolve `issuer-public-key` themselves (from the issuer's DID --
  did:key by decoding it, did:web by fetching its document) before calling
  this; verifying a credential against a key found *inside the credential
  itself* would let anyone forge one by supplying their own key alongside
  a claim to be whoever they like."
  [issuer-public-key jwt issuer subject now]
  (let [claims (interop/jws-claims jwt)]
    (if-let [problem (vc-problem claims issuer subject)]
      {:error problem}
      (if-not (interop/verify-jws issuer-public-key jwt)
        {:error "signature does not verify"}
        (let [vc (:vc claims)
              valid-from (Instant/parse (get vc "validFrom"))
              valid-until (Instant/parse (get vc "validUntil"))]
          (cond
            (.isBefore now valid-from) {:error "not yet valid" :valid-from valid-from}
            (.isAfter now valid-until) {:error "expired" :valid-until valid-until}
            ;; Nimbus's own JSON parsing is Gson under the shade, so nested
            ;; objects (credentialSubject, evidence, ...) arrive as
            ;; LinkedTreeMap -- a real java.util.Map, so get/get-in already
            ;; work on it, but not a Clojure Associative, so assoc does not
            ;; until it is copied into a real Clojure map.
            :else (cond-> (into {} (get vc "credentialSubject"))
                    (get vc "evidence") (assoc "evidence" (get vc "evidence")))))))))
