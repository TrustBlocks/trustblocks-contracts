(ns com.trustblocks.xtdb.identity
  "The identity ledger: who holds a DID, and the keypair behind it.
  Deliberately its own table, separate from com.trustblocks.xtdb.credential
  -- that one is the log of *claims* (who was granted what, who attested
  to what); this one is the registry of *who exists to make or receive a
  claim* at all, town or officeholder. The two link through the one
  thing they share: the DID string itself, which is this table's :xt/id
  and credential's own :issuer/:subject.

  Keys are held here, custodially, in plain XTDB documents -- a real
  security tradeoff, made deliberately: officeholders here are town
  staff signing routine municipal attestations, not a system meant to
  resist a hostile XTDB operator. If this ever protects something where
  that stops being true, encrypting :identity/private-key at rest with a
  server-held secret is the next step, not a rewrite -- everything above
  this table only ever sees a keypair map, never the storage format.

  An officeholder's identity is found by the email they sign in with
  (see com.trustblocks.app.auth) -- there is no separate officeholder
  login. The Town has no email; it is provisioned once and never signs
  in anywhere."
  (:require [com.trustblocks.did.interop :as interop]
            [com.trustblocks.did.key :as key]
            [xtdb.api :as xt]))

(def identity-table :identity)

(defn- put! [node doc]
  (xt/execute-tx node [[:put-docs identity-table doc]])
  doc)

(defn provision-town!
  "A new Town identity: a fresh Ed25519 keypair and a path-based did:web
  under our own domain -- did:web:trustblocks.com:towns:<slug> -- since
  no real municipal domain is ours to serve /.well-known from yet (see
  docs/model-repository.md's own note on this project's domains). A
  did:web identity is just files at a URL, so this can move to a town's
  own domain later without anyone's DID changing meaning, only where it
  resolves."
  [node {:keys [slug name]}]
  (let [{:keys [public private]} (interop/generate-keypair)
        did (str "did:web:trustblocks.com:towns:" slug)]
    (put! node {:xt/id                  did
                :identity/kind          :town
                :identity/name          name
                :identity/public-key   public
                :identity/private-key  private})))

(defn provision-officeholder!
  "A new officeholder identity: a fresh Ed25519 keypair and a did:key --
  no document to host, since the identifier is the key (see
  com.trustblocks.did.key). Provisioning an identity is not the same as
  granting one authority to act; that is a separate OfficeGrant credential
  (see com.trustblocks.xtdb.certification/grant-office!), issued by a
  Town to the did:key this returns.

  `email` is how this identity is found again once someone signs in --
  the same address com.biffweb/authenticate put in the session, not a
  second credential of its own."
  [node {:keys [email name office]}]
  (let [{:keys [public private]} (interop/generate-keypair)
        did (key/from-public-key public)]
    (put! node {:xt/id                  did
                :identity/kind          :officeholder
                :identity/email         email
                :identity/name          name
                :identity/office        office
                :identity/public-key   public
                :identity/private-key  private})))

(defn by-did
  "The identity record for `did`, or nil."
  [node did]
  (first (xt/q node (list 'from identity-table [{:xt/id did} (symbol "*")]))))

(defn by-email
  "The officeholder identity record for `email`, or nil -- how a signed-in
  session (which only carries an email) resolves to a DID and a keypair."
  [node email]
  (first (xt/q node (list 'from identity-table
                          [{:identity/email email} (symbol "*")]))))

(defn keypair
  "An identity record's keypair, in the {:public :private} shape
  com.trustblocks.did.interop and com.trustblocks.xtdb.credential expect."
  [identity]
  {:public  (:identity/public-key identity)
   :private (:identity/private-key identity)})
