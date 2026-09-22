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

  certify! runs as an execute/trigger! call against the contract it
  concerns, not a bare credential issuance: an Attestation is a
  statement about a specific contract, and the contract's own stored
  clause is where 'is this actually true right now, given this
  contract's own state' gets decided -- 'this contract was approved by
  Board action on ...' is only sayable once the Board really has
  approved it, and the clause is what knows that, not this namespace.
  See model/attestation.cto's own docstring for the standard
  AttestationRequest/Response pair every contract wanting attestation
  support adds one branch of its own clause for.

  What the clause cannot do -- because the sandbox it runs in
  deliberately has neither, see com.trustblocks.clause's own docstring
  -- is know who is signing or when they actually clicked certify.
  Those two facts (the officeholder's did, already checked against a
  real OfficeGrant before the clause ever runs; and certifiedAt, the
  wall-clock moment, distinct from valid-from which is the event's own
  time) are added here, in trusted host code, once the clause has
  answered.

  certify!'s refusals -- its own, and any that bubble up from
  execute/trigger! (a refused clause, a duplicate request, an unknown
  contract) -- all carry a :trustblocks/error tag in their ex-data, the
  same convention throughout, so com.trustblocks.app.certify can turn
  them into status codes without matching on a message string."
  (:require [com.trustblocks.did.vc :as vc]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]))

(def office-grant-class "com.trustblocks.identity@1.0.0.OfficeGrant")
(def attestation-class "com.trustblocks.identity@1.0.0.Attestation")
(def attestation-request-class "com.trustblocks.attestation@1.0.0.AttestationRequest")

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

(defn- attestation-request-id
  "A deterministic id for this exact attestation attempt, so a retried
  form post dedupes through execute/trigger!'s own guard -- the same
  content submitted twice is the same request; different content about
  the same contract (the vote, then later the minutes) is not, and gets
  its own id."
  [contract-id required-authority document-hash valid-from]
  (str "attestation:" contract-id ":" required-authority ":" document-hash ":" valid-from))

(defn certify!
  "An officeholder, identified by the email their session signed in with,
  certifies something about `contract-id` -- refusing unless their
  OfficeGrant, as of `valid-from` (the event's own time, not now), names
  `required-authority`, AND unless `contract-id`'s own stored clause
  agrees this is true right now (see model/attestation.cto).

  `valid-from` is deliberately when the authority check is made
  against, not wall-clock time: it answers 'was this person authorized
  to do this when it happened', which for a Clerk certifying a
  months-old vote is not the same question as 'are they authorized
  today'. It is also the moment the resulting credential's own
  valid-time window opens -- see credential/issue!.

  Runs execute/trigger! against `contract-id` with an
  AttestationRequest before signing anything: the clause decides
  whether `statement`/`document-hash` can be certified at all (its
  own eligibility rule, e.g. 'not until the Board has approved this'),
  and its response -- not the raw arguments -- is what gets signed, so
  a caller cannot certify something the contract's own logic would
  refuse. Any refusal from the clause, a duplicate submission, or an
  unknown contract bubbles up with execute/trigger!'s own
  :trustblocks/error tag.

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
    (let [{:keys [response]}
          ;; now is the wire-format string every other trigger! caller
          ;; passes (dev/manager_employment_demo.clj, com.trustblocks.
          ;; app.trigger), not the java.time.Instant valid-from already
          ;; is elsewhere here -- Concerto's own DateTime schema would
          ;; accept either, but a clause built to treat $timestamp as a
          ;; string (the documented convention, see com.trustblocks.
          ;; clause's own docstring) shouldn't have to handle two shapes
          ;; depending on who called trigger!.
          (execute/trigger! node contract-id
                            {"$class" attestation-request-class
                             "statement" statement
                             "documentHash" document-hash
                             "requiredAuthority" required-authority}
                            (str valid-from)
                            :request-id (attestation-request-id
                                         contract-id required-authority document-hash valid-from)
                            :by email)]
      (credential/issue!
       node (identity/keypair officeholder)
       {:issuer did :subject did
        :credential-subject {"$class"       attestation-class
                             "id"           did
                             "documentHash" (get response "documentHash")
                             "statement"    (get response "statement")
                             "contractId"   contract-id
                             "certifiedAt"  (str (java.time.Instant/now))}
        :valid-from valid-from :valid-until valid-until
        :evidence evidence}))))

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
