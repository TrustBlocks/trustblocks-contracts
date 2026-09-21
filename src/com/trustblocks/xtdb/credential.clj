(ns com.trustblocks.xtdb.credential
  "Verifiable Credentials, recorded bitemporally -- the same principle as
  com.trustblocks.xtdb.execute applied to a different kind of fact.

  A contract's execution log answers 'what happened and why'; this answers
  'who was authorized, and for how long'. Both questions turn out to want
  the same mechanism: a credential's own validFrom/validUntil become the
  XTDB record's own valid-time window, not just inert JSON fields sitting
  inside it, so 'was this credential in force during the January 12
  hearing' is the same kind of query as 'what was this contract's state
  in March' -- FOR VALID_TIME AS OF, not a check we implement ourselves
  over an expiresAt column.

  What's stored is the signed JWT verbatim -- the credential *is* that
  string, see com.trustblocks.did.vc -- plus enough plain columns
  (issuer, subject, :concerto/class) to find it again without decoding
  every row. :concerto/class rather than a bare :$class for the same
  reason com.trustblocks.xtdb.keys reserves that exact mapping: XTDB
  reads a bare `$` as a namespace separator and silently renames the
  column to `:/class`, which is not a key anything can query by name
  again -- caught here by a test, the same trap this codebase has hit
  before storing whole Concerto instances.

  The credentialSubject itself is Concerto-validated here, against
  model/identity.cto, before it ever reaches com.trustblocks.did.vc/issue;
  the outer VC envelope stays outside Concerto entirely, per that model's
  own docstring."
  (:require [com.trustblocks.did.vc :as vc]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]))

(def credential-table :credential)

(defn issue!
  "Validate `credential-subject` against its own $class, sign it as a VC
  from `issuer` to `subject`, and record it -- valid from `valid-from`
  until `valid-until` in XTDB's own valid-time, not merely as data inside
  the credential. Returns the signed JWT.

  `issuer-keypair` is the issuer's {:public :private} (com.trustblocks.did.
  interop/generate-keypair's shape) -- the Town's, for an OfficeGrant; an
  officeholder's own, for an Attestation they sign about themselves (see
  model/identity.cto).

  `evidence`, when given, is another credential's JWT this one rests on."
  [node issuer-keypair
   {:keys [issuer subject credential-subject valid-from valid-until evidence reg]}]
  (let [reg (or reg (store/registry-from-xtdb node))
        fqn (get credential-subject "$class")]
    (when-not fqn
      (throw (ex-info "credentialSubject has no $class" {:credential-subject credential-subject})))
    (when-let [errs (store/validate reg credential-subject)]
      (throw (ex-info "credentialSubject failed validation" {:$class fqn :errors errs})))
    (let [type (:name (:declaration (get reg fqn)))
          jwt  (vc/issue issuer-keypair
                         {:issuer issuer :subject subject
                          :credential-subject (dissoc credential-subject "$class")
                          :valid-from valid-from :valid-until valid-until
                          :types [type] :evidence evidence})]
      (xt/execute-tx
       node
       [[:put-docs {:into credential-table :valid-from valid-from :valid-to valid-until}
         {:xt/id            (random-uuid)
          :issuer           issuer
          :subject          subject
          :concerto/class   fqn
          :jwt              jwt}]])
      jwt)))

(defn for-subject
  "Every credential ever issued to `subject`, as stored -- {:issuer
  :subject :concerto/class :jwt}, oldest first by the credential's own
  valid-from (not :xt/id, which is a random UUID and so is no order at
  all -- sorting by it produced a flaky test before this docstring's
  claim of 'oldest first' was actually true). Includes credentials
  outside their own valid-time window (expired, or not yet in force);
  see current-for-subject and as-of-for-subject for the questions that
  answer 'is this one usable right now'."
  [node subject]
  (->> (xt/q node (list 'from credential-table
                        {:for-valid-time :all-time
                         :bind           [{:subject subject} (symbol "*") 'xt/valid-from]}))
       (sort-by :xt/valid-from)
       (mapv #(dissoc % :xt/valid-from))))

(defn as-of-for-subject
  "Every credential of `subject`'s that was in force at `instant`."
  [node subject instant]
  (vec (xt/q node (list 'from credential-table
                        {:for-valid-time (list 'at instant)
                         :bind           [{:subject subject} (symbol "*")]}))))

(defn current-for-subject
  "Every credential of `subject`'s in force right now."
  [node subject]
  (as-of-for-subject node subject (java.time.Instant/now)))

(defn revoke!
  "Close a credential's valid-time window at `at`, before its own
  validUntil -- the Town deciding someone's authority ends now, not when
  the credential said it would.

  A bitemporal correction, via :delete-docs with its own :valid-from
  rather than by re-asserting the document with an earlier :valid-to:
  XTDB records the deletion as its own fact, effective from `at`, so
  system time still shows the credential was on record as valid through
  `at` right up until this transaction ran, and valid time before `at`
  is untouched -- 'was this credential in force during the January
  hearing' asked *after* a later revocation still gets the answer that
  was true in January.

  `credential-id` is the :xt/id for-subject/current-for-subject returned."
  [node credential-id at]
  (xt/execute-tx node [[:delete-docs {:from credential-table :valid-from at} credential-id]]))
