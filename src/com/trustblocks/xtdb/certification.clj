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
  See attestation.cto (shared by the templates in trustblocks-templates,
  and carried by each template that supports attestation) for the standard
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
  (:require [com.trustblocks.did.interop :as interop]
            [com.trustblocks.did.vc :as vc]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]))

(def office-grant-class "com.trustblocks.identity@1.0.0.OfficeGrant")
(def attestation-class "com.trustblocks.identity@1.0.0.Attestation")
(def attestation-request-class "com.trustblocks.attestation@1.0.0.AttestationRequest")
(def designation-class "com.trustblocks.identity@1.0.0.Designation")

(def advisory-authorities
  "The only authority an AI agent can be designated: to advise."
  #{"ADVISE"})

(defn grant-office!
  "The Town grants `officeholder-did` an office and a set of authorities,
  valid from `valid-from` until `valid-until`. Returns the signed VC."
  [node town-did {:keys [officeholder-did name office authority valid-from valid-until]}]
  (let [town (or (identity/by-did node town-did)
                 (throw (ex-info "No such town"
                                 {:trustblocks/error :no-such-town :did town-did})))]
    (when (some-> (identity/by-did node officeholder-did) identity/agent?)
      (throw (ex-info "An AI agent holds no office; it acts only by designation, and only to advise"
                      {:trustblocks/error :agent-cannot-hold-office :did officeholder-did})))
    (credential/issue!
     node (identity/keypair town)
     {:issuer town-did :subject officeholder-did
      :credential-subject {"$class"    office-grant-class
                           "id"        officeholder-did
                           "name"      name
                           "office"    office
                           "authority" authority}
      :valid-from valid-from :valid-until valid-until})))

(defn- verified-credentials
  "Every credential of class `cls` held by `subject-did` that was genuinely
  in force at `at` -- checked against XTDB's own valid-time *and* the
  issuer's signature -- as {:subject <decoded credentialSubject> :jwt
  :issuer}."
  [node subject-did cls at]
  (keep (fn [record]
          (when (= cls (:concerto/class record))
            (when-let [issuer (identity/by-did node (:issuer record))]
              (let [subject (vc/verify (:identity/public-key issuer) (:jwt record)
                                       (:issuer record) subject-did at)]
                (when-not (:error subject)
                  {:subject subject :jwt (:jwt record) :issuer (:issuer record)})))))
        (credential/as-of-for-subject node subject-did at)))

(defn- verified-office-grant
  "An OfficeGrant credential of `officeholder-did`'s that was genuinely
  in force at `at` -- checked against XTDB's own valid-time *and* the
  signature, since this is the one place authority to act is decided
  rather than merely displayed. nil if none."
  [node officeholder-did at]
  (:subject (first (verified-credentials node officeholder-did office-grant-class at))))

(defn- grant-with
  "The verified OfficeGrant of `did`'s in force at `at` that names
  `authority`, as verified-credentials returns it, or nil."
  [node did authority at]
  (some #(when (some #{authority} (get-in % [:subject "authority"])) %)
        (verified-credentials node did office-grant-class at)))

(defn authority-for
  "How `did` holds `authority` at `at`, or nil: {:via :office-grant :jwt}
  from their own OfficeGrant, or {:via :designation :jwt :designator} from
  a Designation for `matter` -- the credential that authorized the act, for
  the signed record of it to name as evidence.

  A designation counts only while its designator still holds the
  authority, through their own OfficeGrant: authority delegated is
  authority the principal still has. It is checked at `at`, the act's own
  time, so a designee's acts before the designator left office stand and
  those after do not. A designee cannot pass the authority on."
  [node did authority at & {:keys [matter]}]
  (or (when-let [{:keys [jwt]} (grant-with node did authority at)]
        {:via :office-grant :jwt jwt})
      (when matter
        (some (fn [{:keys [subject jwt issuer]}]
                (when (and (= matter (get subject "matterId"))
                           (some #{authority} (get subject "authority"))
                           (grant-with node issuer authority at))
                  {:via :designation :jwt jwt :designator issuer}))
              (verified-credentials node did designation-class at)))))

(defn designate!
  "The officeholder signed in as `designator-email` designates
  `designee-did` to act for them on `matter` -- with `authority`, a subset
  of their own, in `capacity` -- from `valid-from` until `valid-until`.
  Returns the signed Designation VC, whose evidence is the designator's own
  OfficeGrant.

  Refused unless the matter is open (:no-such-matter), the designee has an
  identity, and the designator holds every authority delegated, at
  `valid-from`, through their own OfficeGrant (:missing-authority). An AI
  agent can be designated ADVISE and nothing else
  (:agent-authority-not-advisory)."
  [node {:keys [designator-email designee-did name capacity matter authority
                valid-from valid-until]}]
  (let [designator (or (identity/by-email node designator-email)
                       (throw (ex-info "No officeholder identity for this email"
                                       {:trustblocks/error :no-such-officeholder
                                        :email designator-email})))
        did    (:xt/id designator)
        grants (map #(grant-with node did % valid-from) authority)]
    (when-not (matter/get-matter node matter)
      (throw (ex-info "No such matter" {:trustblocks/error :no-such-matter :matter matter})))
    (when-not (identity/by-did node designee-did)
      (throw (ex-info "No identity for the designee"
                      {:trustblocks/error :no-such-officeholder :did designee-did})))
    (when (and (identity/agent? (identity/by-did node designee-did))
               (not-every? advisory-authorities authority))
      (throw (ex-info "An AI agent can be designated only to advise"
                      {:trustblocks/error :agent-authority-not-advisory
                       :did designee-did :delegating authority})))
    (when (or (empty? authority) (some nil? grants))
      (throw (ex-info "A designator can delegate only authority they hold"
                      {:trustblocks/error :missing-authority :did did :delegating authority})))
    (credential/issue!
     node (identity/keypair designator)
     {:issuer did :subject designee-did
      :credential-subject {"$class"    designation-class
                           "id"        designee-did
                           "name"      name
                           "capacity"  capacity
                           "matterId"  matter
                           "authority" (vec authority)}
      :valid-from valid-from :valid-until valid-until
      :evidence (:jwt (first grants))})))

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
  agrees this is true right now (see the templates' attestation.cto).

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
                            :by email
                            ;; Verified above: the contract's lifecycle, when
                            ;; it has one, checks it is the certification the
                            ;; attestation requires.
                            :certification {:credential "Attestation"
                                            :authority required-authority
                                            :did did})]
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

(defn- micros
  "`instant` at XTDB's own valid-time precision, so a stored bound and the
  nanosecond-precise one signed into a credential compare equal."
  [^java.time.Instant instant]
  (.truncatedTo instant java.time.temporal.ChronoUnit/MICROS))

(defn- office-grant-authorization
  "One stored OfficeGrant row, verified and decoded for display. Verified at
  the credential's own signed validFrom rather than the stored row's: XTDB
  keeps valid time to the microsecond, so a grant issued at a
  nanosecond-precise (Instant/now) would otherwise be checked a hair before
  its own window opens and report 'not yet valid'.

  :revoked rather than :expired when XTDB's valid time closed before the
  credential's own signed validUntil -- the mark credential/revoke! leaves."
  [node did now {:keys [issuer jwt valid-from valid-until]}]
  (let [issuer-record (identity/by-did node issuer)

        [claim signed-until]
        (try
          (let [signed      (:vc (interop/jws-claims jwt))
                signed-from (java.time.Instant/parse (get signed "validFrom"))]
            [(when issuer-record
               (vc/verify (:identity/public-key issuer-record) jwt issuer did signed-from))
             (java.time.Instant/parse (get signed "validUntil"))])
          (catch Exception e
            [{:error (str "Credential could not be read: " (ex-message e))} nil]))

        verified (boolean (and claim (not (:error claim))))
        status (cond
                 (.isBefore now valid-from) :not-yet-valid

                 (and valid-until (.isAfter now valid-until))
                 (if (and signed-until (.isBefore (micros valid-until) (micros signed-until)))
                   :revoked
                   :expired)

                 :else :active)]
    {:issuer      issuer
     :authority   (vec (or (get claim "authority") []))
     :valid-from  valid-from
     :valid-until valid-until
     :status      status
     :verified    verified
     :error       (cond
                    (:error claim) (:error claim)
                    (nil? issuer-record) "Issuer identity is not on file")}))

(defn signature-authorizations
  "Every provisioned officeholder with every signed OfficeGrant ever issued
  to their DID, suitable for the operator-facing authorization ledger.

  Grants are signature-verified before their claims are exposed (see
  office-grant-authorization). A bad/missing issuer, or a stored credential
  too malformed to parse, remains visible with :verified false and an
  :error rather than disappearing from an administrative list -- silence
  would make corrupted authorization data look like no authorization data."
  [node]
  (let [now (java.time.Instant/now)]
    (mapv
     (fn [officeholder]
       (let [did (:xt/id officeholder)]
         {:did    did
          :name   (:identity/name officeholder)
          :email  (:identity/email officeholder)
          :office (:identity/office officeholder)
          :grants (->> (credential/for-subject node did)
                       (filter #(= office-grant-class (:concerto/class %)))
                       (mapv #(office-grant-authorization node did now %)))}))
     (identity/officeholders node))))

(defn matter-record
  "Every credential naming `matter-id` -- designations, receipts,
  attestations -- verified and decoded for display, oldest first by the time
  of the act: the credentialSubject plus \"$class\", \"issuer\",
  \"subject\", \"valid-from\", \"valid-until\" and \"evidence\" (the
  authorizing credential's own subject and issuer, decoded, when there is
  one). A record that fails to verify is left out, as
  attestations-for-contract leaves it."
  [node matter-id]
  (let [decode (fn [jwt]
                 (try (let [{:keys [vc iss]} (interop/jws-claims jwt)]
                        {"issuer" iss "credentialSubject" (into {} (get vc "credentialSubject"))
                         "type" (last (get vc "type"))})
                      (catch Exception _ nil)))]
    (keep (fn [{:keys [issuer subject jwt valid-from valid-until] cls :concerto/class}]
            (when-let [signer (identity/by-did node issuer)]
              ;; At its own signed validFrom, for the reason
              ;; office-grant-authorization gives.
              (let [signed (try (java.time.Instant/parse
                                 (get (:vc (interop/jws-claims jwt)) "validFrom"))
                                (catch Exception _ valid-from))
                    claims (vc/verify (:identity/public-key signer) jwt issuer subject signed)]
                (when-not (:error claims)
                  (assoc claims
                         "$class" cls "issuer" issuer "subject" subject
                         "valid-from" valid-from "valid-until" valid-until
                         "evidence" (some-> (get claims "evidence") decode))))))
          (credential/for-matter node matter-id))))

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
