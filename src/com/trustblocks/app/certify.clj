(ns com.trustblocks.app.certify
  "POST /contracts/:id/certify -- the officeholder-certification write
  path, an HTML form's front door onto
  com.trustblocks.xtdb.certification/certify!.

  Unlike com.trustblocks.app.trigger (a JSON envelope for machine
  callers, reached by curl in docs/walkthrough.md), this is a plain
  form post from a contract's own page (com.trustblocks.app.contracts):
  a signed-in officeholder types a statement, a document reference, and
  the meeting date, and submits. A page reload with the new
  certification listed is success; a redirect back with ?certify-error=
  is a refusal, read off the query string by the same page.

  The authority an officeholder needs is the contract's to say, not this
  handler's: its lifecycle declares who certifies an AttestationRequest
  (CERTIFY_MINUTES for the manager employment contract), and a contract
  whose lifecycle names none accepts no attestations here. Whether one can
  be made *yet* -- not before the Board has approved -- is the lifecycle's
  to refuse when it runs.

  certify! itself now runs as an execute/trigger! call against this
  contract (see com.trustblocks.xtdb.certification's own docstring), so
  a retried form post dedupes through trigger!'s own request-id guard
  exactly like any other trigger, and a refusal from the contract's own
  clause (e.g. attesting before the Board has approved anything) reaches
  here the same way any other :trustblocks/error does."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.trustblocks.app.auth :as auth]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.lifecycle :as lifecycle]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]))

(defn required-authority
  "The authority `contract-id`'s lifecycle requires of an officeholder
  attesting to it, or nil when it accepts no attestations."
  [node contract-id]
  (some->> (store/get-instance node contract-id)
           (#(get % "$class"))
           (execute/get-lifecycle node)
           (lifecycle/attestation-authority)))

(def ^:private no-natural-expiry
  "An Attestation records that something happened; unlike an
  OfficeGrant's term, there is no business rule for when that stops
  being true. A century out is a practical horizon, not a policy --
  chosen so re-verifying a decades-old attestation never spuriously
  reports 'expired', which a short or 'now-ish' validUntil would risk.

  java.time.Duration rather than Period: valid-from is an Instant, and
  Instant only supports exact (seconds-based) arithmetic -- adding a
  calendar Period of years throws UnsupportedTemporalTypeException,
  which a manual /certify call caught immediately and a unit test on a
  fixed Instant never would have."
  (java.time.Duration/ofDays (* 100 365)))

(defn- form-value
  "biff.ring's default middleware may or may not keywordize form params
  depending on how the request was made, so check both spellings --
  the same defensive pattern com.trustblocks.app.contracts/query-param
  uses for the same reason."
  [ctx k]
  (let [v (or (get-in ctx [:params k]) (get-in ctx [:params (keyword k)]))]
    (when-not (str/blank? v) v)))

(defn- envelope-problem [{:keys [statement document-hash valid-from]}]
  (cond
    (not statement) "A statement is required."
    (not document-hash) "A document reference is required."
    (not valid-from) "The meeting date is required."

    (try (execute/->instant valid-from) false (catch Exception _ true))
    "The meeting date could not be read as a date."))

(defn- back-to-contract
  "A redirect back to the contract page -- always 303, whether this is
  a success or a refusal: the browser is the caller here, not a script
  reading a status code, so the outcome is a message on the page it
  lands on (?certify-error=...), not the response's own status."
  ([id] {:status 303 :headers {"location" (routes/contract id)}})
  ([id message] {:status  303
                 :headers {"location" (str (routes/contract id) "?certify-error="
                                           (java.net.URLEncoder/encode ^String message "UTF-8"))}}))

(defn certify
  "Run com.trustblocks.xtdb.certification/certify! against the posted
  form and send the officeholder back to the contract page, either way."
  [{:keys [biff.xtdb/node session] :as ctx}]
  (let [id (routes/contract-id ctx)
        statement (form-value ctx "statement")
        document-hash (form-value ctx "documentHash")
        valid-from-raw (form-value ctx "validFrom")
        evidence (form-value ctx "evidence")]
    (if-let [problem (or (envelope-problem {:statement statement :document-hash document-hash
                                            :valid-from valid-from-raw})
                         (when-not (required-authority node id)
                           "This contract's template accepts no attestations."))]
      (back-to-contract id problem)
      (let [valid-from (execute/->instant valid-from-raw)
            email      (auth/user-email ctx (:uid session))]
        (try
          (cert/certify! node {:email email :statement statement :document-hash document-hash
                               :contract-id id :required-authority (required-authority node id)
                               :valid-from valid-from :valid-until (.plus ^java.time.Instant valid-from no-natural-expiry)
                               :evidence evidence})
          (back-to-contract id)
          (catch clojure.lang.ExceptionInfo e
            (let [{:trustblocks/keys [error]} (ex-data e)]
              (when-not error
                (log/error e "Unexpected failure certifying" id))
              (back-to-contract id (ex-message e)))))))))

(def module
  {:biff.ring/routes
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/contract-certify) {:post certify}]]]})
