(ns com.trustblocks.app.trigger
  "POST /contracts/:id/trigger -- the write path.

  com.trustblocks.app.contracts is the warehouse's front window; this is its
  loading dock. One endpoint, which runs a contract's clause against a
  request and records what happened (com.trustblocks.xtdb.execute/trigger!).

  The body is a JSON envelope around the Concerto request:

    {\"requestId\": \"board-approval-2025-12-02\",
     \"at\":        \"2025-12-02T00:00:00.000Z\",
     \"request\":   {\"$class\": \"...BoardApprovalRequest\",
                   \"requestedAt\": \"2025-12-02T00:00:00.000Z\"}}

  An envelope rather than a bare Concerto instance because neither of the
  other two fields could live inside one. Concerto is closed-world: an
  instance carrying a requestId that its model does not declare fails
  validation. And `at` is not a property of the request at all -- it is when
  the execution takes effect, which is the contract's own clock, not ours.

  Three things the caller, not the server, decides:

  - `at` is required. Reading a wall clock here would make an execution
    unreplayable and would quietly record a back-dated approval as having
    happened today. See execute/trigger!, which takes `now` for the same
    reason.

  - `requestId` is required, and a repeat is refused with 409. A trigger is
    not safe to redo: a second snow-removal visit report pays the contractor
    twice, and a caller whose POST timed out cannot otherwise tell whether
    the first one landed.

  - The response and any new state come back in the reply, so a caller does
    not have to re-read the contract to find out what its request did.

  Authentication is the session cookie, the same one the pages use, so this
  endpoint is a :biff.ring/routes site route rather than a
  :biff.ring/api-routes one: the API stack has no session. A script therefore
  signs in the way a person does, and must send an Origin header, because
  biff.ring's CSRF protection applies to every cookie-authenticated POST --
  which is exactly right while the credential is a cookie. A machine caller
  wants a bearer credential instead; that is a separate piece of work, not a
  reason to leave the write path open."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [com.trustblocks.app.auth :as auth]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.xtdb.execute :as execute]))

;; ---------------------------------------------------------------- envelope

(defn- concerto-keys
  "Undo the keywordization muuntaja did on the way in.

  biff.ring's site middleware stack runs muuntaja/wrap-format, which parses
  a JSON body into :body-params with keyword keys. Concerto instances are
  string-keyed everywhere else in this codebase -- store/validate and
  xtdb.keys both expect that -- so the instance is converted back here, at
  the edge, rather than teaching those to accept either."
  [m]
  (walk/stringify-keys m))

(defn- date-time?
  "Whether a value is a Concerto DateTime we can actually use. Asked here so
  a bad `at` is a 400 naming the field, rather than a string that flows into
  the clause as `now` and fails somewhere less obvious."
  [v]
  (boolean (and (string? v)
                (try (execute/->instant v) true
                     (catch Exception _ false)))))

(defn- envelope-problem
  "Why this body cannot be a trigger, or nil."
  [{:keys [requestId at request]}]
  (cond
    (not (and (string? requestId) (seq requestId)))
    "requestId is required, and must be a non-empty string naming this request"

    (not (date-time? at))
    "at is required, and must be an ISO-8601 date-time -- when this request takes effect"

    (not (map? request))
    "request is required, and must be a Concerto instance"

    (not (string? (:$class request)))
    "request must carry a $class naming its Concerto type"))

;; ------------------------------------------------------------------ replies

(def ^:private error-status
  "How each :trustblocks/error from execute/trigger! reaches the caller.

  The split is by who can do something about it. 4xx means the caller can
  fix it or should stop; 422 means the contract itself declined, which is a
  real answer rather than a failure; 5xx means the platform could not run a
  request that was nothing wrong with."
  {:invalid-envelope      400
   :invalid-request       400
   :no-such-contract      404
   :duplicate-request     409
   :out-of-order          409
   :no-clause             422
   :clause-refused        422
   :runtime-unavailable   501
   :invalid-clause-output 500})

(defn- reply
  "A JSON response, encoded here rather than left to muuntaja's content
  negotiation so the content type does not depend on what the caller put in
  its Accept header."
  [status body]
  {:status  status
   :headers {"content-type" "application/json"}
   :body    (json/generate-string body)})

(defn- error-reply
  "Every failure looks the same from outside: a code to branch on, a message
  to read, and whatever the code makes available. Keys are camelCase like the
  request's, so a caller is not parsing two conventions in one exchange."
  [code message extra]
  (reply (error-status code 500)
         (into {:error code :message message}
               (remove (comp nil? val))
               extra)))

;; ------------------------------------------------------------------ handler

(defn trigger
  "Run the contract's clause against the posted request and record it."
  [{:keys [biff.xtdb/node body-params session] :as ctx}]
  (let [id (routes/contract-id ctx)]
    (if-let [problem (envelope-problem body-params)]
      (error-reply :invalid-envelope problem {:contract id})
      ;; From here on the envelope is well formed, so anything that goes wrong
      ;; is trigger!'s to name -- see error-status.
      (let [{:keys [requestId at request]} body-params]
        (try
          (let [{:keys [response state]}
                (execute/trigger! node id (concerto-keys request) at
                                  :request-id requestId
                                  :by         (auth/user-email ctx (:uid session)))]
            (reply 200 (cond-> {:contract  id
                                :requestId requestId
                                :at        at
                                :response  response}
                         state (assoc :state state))))
          (catch clojure.lang.ExceptionInfo e
            (let [{:trustblocks/keys [error] :as data} (ex-data e)]
              (when-not error
                ;; Not one of trigger!'s own refusals, so it is a fault rather
                ;; than an answer. The caller gets a status; the log gets the
                ;; stack trace.
                (log/error e "Unexpected failure triggering" id))
              (error-reply (or error :invalid-clause-output)
                           (ex-message e)
                           {:contract   id
                            :requestId  requestId
                            :$class     (:$class data)
                            :errors     (:errors data)
                            :executedAt (:executed-at data)}))))))))

(def module
  {:biff.ring/routes
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/contract-trigger) {:post trigger}]]]})
