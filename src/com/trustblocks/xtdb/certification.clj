(ns com.trustblocks.xtdb.certification
  "Where identity (com.trustblocks.xtdb.identity), credential
  (com.trustblocks.xtdb.credential), and authorization meet: does this
  officeholder currently hold the authority to do this, and if so, sign
  it on their behalf.

  Two acts, both just credential issuance underneath, distinguished by
  who is checked before it happens:

  - grant-office! is the Town acting -- no authority check, because the
    Town is the root of trust here; nothing above it grants the Town
    its own authority to grant.
  - certify! is an officeholder acting, and is the one place this
    project checks 'is this person allowed to do this' before signing
    anything -- resolving who they are from the email
    com.biffweb/authenticate already put in their session, finding
    their current OfficeGrant, and refusing outright if it does not
    name the specific authority the action requires.

  Neither act goes through com.trustblocks.xtdb.execute/trigger!. A
  council vote and the minutes approving it are not a contract
  execution -- no parties, no mutual assent -- so forcing them through
  the Concerto Request/Response ceremony trigger! uses for contracts
  would model a fact as something it structurally is not. An
  Attestation VC already is the 'signed response, anchored at the
  moment it actually happened' that trigger! provides for contracts;
  it does not need a second envelope on top.

  certify!'s refusals carry a :trustblocks/error tag in their ex-data,
  the same convention com.trustblocks.xtdb.execute/trigger! uses, so
  com.trustblocks.app.certify can turn them into status codes without
  matching on a message string."
  (:require [com.trustblocks.did.vc :as vc]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.identity :as identity]))

(def office-grant-class "com.trustblocks.identity@1.0.0.OfficeGrant")
(def attestation-class "com.trustblocks.identity@1.0.0.Attestation")

(defn grant-office!
  "The Town grants `officeholder-did` an office and a set of authorities,
  valid from `valid-from` until `valid-until`. Returns the signed VC."
  [node town-did {:keys [officeholder-did name office authority valid-from valid-until]}]
  (let [town (or (identity/by-did node town-did)
                 (throw (ex-info "No such town"
                                 {:trustblocks/error :no-such-town :did town-did})))]
    (credential/issue!
     node (identity/keypair town)
     {:issuer town-did :subject officeholder-did
      :credential-subject {"$class"    office-grant-class
                           "id"        officeholder-did
                           "name"      name
                           "office"    office
                           "authority" authority}
      :valid-from valid-from :valid-until valid-until})))

(defn- verified-office-grant
  "An OfficeGrant credential of `officeholder-did`'s that was genuinely
  in force at `at` -- checked against XTDB's own valid-time *and* the
  signature, since this is the one place authority to act is decided
  rather than merely displayed. nil if none."
  [node officeholder-did at]
  (some (fn [record]
          (when (= office-grant-class (:concerto/class record))
            (when-let [issuer (identity/by-did node (:issuer record))]
              (let [subject (vc/verify (:identity/public-key issuer) (:jwt record)
                                       (:issuer record) officeholder-did at)]
                (when-not (:error subject) subject)))))
        (credential/as-of-for-subject node officeholder-did at)))

(defn certify!
  "An officeholder, identified by the email their session signed in with,
  certifies something -- refusing unless their OfficeGrant, as of
  `valid-from` (the event's own time, not now), names
  `required-authority`.

  `valid-from` is deliberately when the check is made against, not
  wall-clock time: it answers 'was this person authorized to do this
  when it happened', which for a Clerk certifying a months-old vote is
  not the same question as 'are they authorized today'.

  Returns the signed Attestation VC. `evidence`, when given, is another
  credential's JWT this one rests on -- the vote certification a minutes
  approval names as what it is approving."
  [node {:keys [email statement document-hash contract-id required-authority
                valid-from valid-until evidence]}]
  (let [officeholder (or (identity/by-email node email)
                         (throw (ex-info "No officeholder identity for this email"
                                         {:trustblocks/error :no-such-officeholder :email email})))
        did (:xt/id officeholder)
        grant (or (verified-office-grant node did valid-from)
                  (throw (ex-info "No office grant in force at that time"
                                  {:trustblocks/error :no-office-grant :did did :at valid-from})))
        authorities (set (get grant "authority"))]
    (when-not (contains? authorities required-authority)
      (throw (ex-info "This office grant does not include the required authority"
                      {:trustblocks/error :missing-authority
                       :did did :required required-authority :has authorities})))
    (credential/issue!
     node (identity/keypair officeholder)
     {:issuer did :subject did
      :credential-subject (cond-> {"$class"       attestation-class
                                   "id"           did
                                   "documentHash" document-hash
                                   "statement"    statement}
                            contract-id (assoc "contractId" contract-id))
      :valid-from valid-from :valid-until valid-until
      :evidence evidence})))

(defn attestations-for-contract
  "Every Attestation ever certified about `contract-id`, oldest first,
  each verified and decoded rather than handed back as a raw stored row
  -- this is for display (a contract page's own certification history),
  so a caller gets credentialSubject content (statement, documentHash,
  evidence, ...) plus 'issuer' (the officeholder's did), 'valid-from'
  (when it was certified), and the attestation's own 'jwt' -- kept
  because a caller building a form to certify something *else* about
  the same contract needs it to offer this one back as `evidence`,
  not just to display it.

  Verified as of each credential's own valid-from, not now: an
  Attestation correctly certified in 2025 should still display in 2027
  even though nothing here re-checks whether that officeholder is
  still in office today -- this shows what was certified, not who
  could still certify it.

  Skips (does not throw on) any record that fails to verify -- an
  issuer with no identity on file, or a tampered row -- rather than
  breaking the whole page over one bad entry."
  [node contract-id]
  (keep (fn [{:keys [issuer jwt valid-from]}]
          (when-let [officeholder (identity/by-did node issuer)]
            (let [subject (vc/verify (:identity/public-key officeholder) jwt
                                     issuer issuer valid-from)]
              (when-not (:error subject)
                (assoc subject "issuer" issuer "valid-from" valid-from "jwt" jwt)))))
        (filter #(= attestation-class (:concerto/class %))
                (credential/for-contract node contract-id))))
