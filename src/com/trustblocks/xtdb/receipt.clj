(ns com.trustblocks.xtdb.receipt
  "Receipts: an officeholder's signed statement that a stored document was
  received on a matter -- the way a party with no identity of its own
  enters the record. A contractor's pay application, a consulting
  engineer's inspection certificate: the contractor and the engineer sign
  nothing here; the Town's officeholder who received the document does, and
  what they sign names the exact bytes (the stored document's hash, see
  com.trustblocks.xtdb.document), who it came from, and the matter.

  The receiving officeholder must hold the authority to receive that kind
  of document at the time it was received -- through their own OfficeGrant,
  or through a Designation for this matter from someone who does (see
  com.trustblocks.xtdb.certification/authority-for). The credential that
  authorized the receipt is its evidence, so the Receipt carries its own
  proof of why it was allowed.

  A Receipt is self-signed, like an Attestation: issuer and subject are the
  receiving officeholder. Its valid-time opens when the document was
  received -- the event's own time, not when someone clicked -- and stays
  open: a receipt does not expire. certifiedAt is the wall-clock moment of
  signing.

  One document is received once per matter; a second receipt of the same
  bytes on the same matter is refused, since it would say the same thing
  twice with two dates. The same report received on another matter is a
  separate receipt."
  (:require [com.trustblocks.did.vc :as vc]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]))

(def receipt-class "com.trustblocks.identity@1.0.0.Receipt")

(def ^:private open-ended
  "A receipt stays in force; VC validUntil is required, so it is far off."
  (java.time.Instant/parse "9999-12-31T00:00:00Z"))

(defn- refuse! [message data]
  (throw (ex-info message data)))

(defn receipts-for-matter
  "Every Receipt on matter `matter-id`, oldest first by the time received --
  verified, as the credentialSubject plus \"issuer\", \"received-at\" and
  \"jwt\". A record that fails to verify is left out."
  [node matter-id]
  (keep (fn [{:keys [issuer jwt valid-from]}]
          (when-let [officeholder (identity/by-did node issuer)]
            (let [subject (vc/verify (:identity/public-key officeholder) jwt
                                     issuer issuer valid-from)]
              (when-not (:error subject)
                (assoc subject "issuer" issuer "received-at" valid-from "jwt" jwt)))))
        (filter #(= receipt-class (:concerto/class %))
                (credential/for-matter node matter-id))))

(defn receive!
  "The officeholder signed in as `email` certifies that stored document
  `document-hash` was received on matter `matter` from `received-from` at
  `received-at`, under `required-authority` (e.g. RECEIVE_PAY_APPLICATIONS).
  Returns the signed Receipt VC.

  Refusals, tagged :trustblocks/error: :no-such-officeholder,
  :no-such-matter, :no-such-stored-document, :missing-authority (neither
  their OfficeGrant nor a Designation for this matter covers it at
  `received-at`), :already-received."
  [node {:keys [email matter document-hash received-from description
                required-authority received-at]}]
  (let [officeholder (or (identity/by-email node email)
                         (refuse! "No officeholder identity for this email"
                                  {:trustblocks/error :no-such-officeholder :email email}))
        did (:xt/id officeholder)]
    (when-not (matter/get-matter node matter)
      (refuse! "No such matter" {:trustblocks/error :no-such-matter :matter matter}))
    (when-not (document/get-document node document-hash)
      (refuse! "No such stored document"
               {:trustblocks/error :no-such-stored-document :document document-hash}))
    (let [{:keys [jwt]} (or (cert/authority-for node did required-authority received-at
                                                :matter matter)
                            (refuse! "Not authorized to receive this on this matter at that time"
                                     {:trustblocks/error :missing-authority :did did
                                      :required required-authority :matter matter}))]
      (when-let [earlier (first (filter #(= document-hash (get % "documentHash"))
                                        (receipts-for-matter node matter)))]
        (refuse! "This document has already been received on this matter"
                 {:trustblocks/error :already-received :document document-hash
                  :received-at (get earlier "received-at")}))
      (credential/issue!
       node (identity/keypair officeholder)
       {:issuer did :subject did
        :credential-subject (cond-> {"$class"       receipt-class
                                     "id"           did
                                     "documentHash" document-hash
                                     "matterId"     matter
                                     "receivedFrom" received-from
                                     "certifiedAt"  (str (java.time.Instant/now))}
                              description (assoc "description" description))
        :valid-from received-at :valid-until open-ended
        :evidence jwt}))))
