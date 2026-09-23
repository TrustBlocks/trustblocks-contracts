(ns com.trustblocks.xtdb.execute
  "Contract execution, recorded bitemporally.

  This is the `trigger` operation: a request arrives, the clause runs, and the
  contract advances. What makes it worth doing here rather than in APAP is what
  gets *recorded*.

  APAP stores the resulting state. That answers \"what is the state?\" and, with
  effort, \"what was the state on June 2?\" -- but not \"why?\". Here an execution
  writes three things:

    :contract-state   the new State, valid from the moment it took effect
    :execution        the request, the response and the clause that ran
    (the contract itself is untouched -- it is the agreement, not the state)

  So `FOR VALID_TIME AS OF DATE '2025-06-02'` gives the state, and the execution
  log gives the reason: this request arrived at this time and produced this
  response. For a dispute that second half is the part that matters.

  Clauses are data. They are stored as text in XTDB like everything else, which
  means they get valid time too -- \"what was the logic on June 2?\" is the same
  query as \"what was the state on June 2?\". A compiled TypeScript blob in an
  archive cannot answer that."
  (:require [clojure.walk :as walk]
            [com.trustblocks.clause :as clause]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.xtdb.keys :as k]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]))

(defn ->instant
  "A Concerto DateTime is a string on the wire; XTDB's :valid-from needs an
  instant. This is the one place the conversion happens, and it happens in the
  adapter -- the clause layer never holds a platform date, which is what keeps
  a clause's answer the same wherever it runs.

  Public so a caller taking a date-time from outside can find out whether it
  will parse before an execution depends on it -- see
  com.trustblocks.app.trigger. Nothing should convert a Concerto DateTime any
  other way."
  [dt]
  (cond
    (inst? dt) dt
    (string? dt) (-> (try (java.time.OffsetDateTime/parse dt)
                          (catch Exception _
                            (java.time.OffsetDateTime/parse (str dt "Z"))))
                     .toInstant)
    :else (throw (ex-info "Cannot read as a date-time" {:value dt}))))

(defn- restate-errors
  "Put a validation failure back into Concerto's spelling.

  store/validate explains the instance as it would be stored, so its keys are
  kebab-case -- that is what XTDB forces on us, see com.trustblocks.xtdb.keys.
  Someone who sent inchesFallen should be told about inchesFallen, not about
  inches-fallen, which is a name they have never seen and cannot look up in
  the model.

  The declared names of the instance's own chain are authoritative and used
  wherever they apply. A key belonging to a nested concept falls back to
  ->camelCase, which is right for every name Concerto's grammar allows except
  deliberately odd capitalization; resolving those exactly would mean walking
  property types down to each nested class, which is a lot of machinery for a
  better error message."
  [reg fqn errs]
  (let [known (k/property-names reg fqn)]
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (update-keys x #(cond-> % (keyword? %) (k/xt->field known)))
         x))
     errs)))

(def clause-table    :clause)
(def state-table     :contract-state)
(def execution-table :execution)

;; ---------------------------------------------------------------- clauses

(def runtimes
  "Clause execution backends. Only :clojure runs anything today -- see
  trigger! -- but the runtime is decided per stored clause (not per
  process), because a contract type ingested from a .cta arrives with
  TypeScript logic we may never rewrite, and a contract type authored here
  never needs to be. Neither runtime is the platform; both are backends the
  platform can pick between per clause."
  #{:clojure :typescript})

(defn put-clause!
  "Store the clause logic for a contract type, checked before it lands.

  Keyed by the `$class` it applies to, so a contract finds its own logic.
  `:runtime` (default :clojure) records which backend trigger! should use to
  run it -- see `runtimes`. Only a :clojure clause is checked against the
  sandbox vocabulary before it lands; a stored :typescript clause is an
  inert, citable artifact until a TypeScript backend exists (see trigger!),
  so there is nothing to check it against yet."
  [node fqn src & {:keys [valid-from ctx runtime] :or {runtime :clojure}}]
  (when-not (runtimes runtime)
    (throw (ex-info "Unknown clause runtime" {:runtime runtime :known runtimes})))
  (when (= runtime :clojure)
    (when-let [why (clause/check (or ctx (clause/context)) src)]
      (throw (ex-info (str "Clause refused by the vocabulary: " why)
                      {:$class fqn :reason why}))))
  (xt/execute-tx node [[:put-docs (cond-> {:into clause-table}
                                    valid-from (assoc :valid-from valid-from))
                        {:xt/id fqn :clause/source src :clause/runtime (name runtime)}]])
  {:$class fqn :runtime runtime})

(defn get-clause
  "{:source :runtime}, or nil if no clause is stored for this $class.
  :runtime defaults to :clojure for a record written before the column
  existed, since every clause stored here originally was one."
  [node fqn]
  (when-let [record (first (xt/q node (list 'from clause-table [{:xt/id fqn} (symbol "*")])))]
    {:source  (:clause/source record)
     :runtime (keyword (or (:clause/runtime record) "clojure"))}))

(defn list-clauses
  "Every stored clause, ordered by the contract $class it applies to.

  This is deliberately an execution-layer query rather than an app page
  reaching into :clause directly: the default from a pre-runtime record to
  :clojure is the same rule get-clause uses, and callers should not grow a
  second interpretation of that storage shape."
  [node]
  (->> (xt/q node (list 'from clause-table [(symbol "*")]))
       (map (fn [record]
              {:class   (:xt/id record)
               :source  (:clause/source record)
               :runtime (keyword (or (:clause/runtime record) "clojure"))}))
       (sort-by :class)
       vec))

;; ---------------------------------------------------------------- state

(defn current-state
  "The contract's State instance as Concerto JSON, or nil before any execution."
  [node contract-id & {:keys [reg]}]
  (store/get-instance node contract-id :table state-table :reg reg))

(defn state-as-of
  "The State as it stood at `instant` in valid time -- the question a lawyer
  actually asks. No history table, no audit triggers, no status_changed_at
  column anyone has to remember to write."
  [node contract-id instant]
  (first
   (xt/q node (list 'from state-table
                    {:for-valid-time (list 'at instant)
                     :bind           [{:xt/id contract-id} (symbol "*")]}))))

(defn history
  "Every execution against a contract, oldest first.

  Sorted here rather than in the query: XTQL's order-by takes a bound column
  and the result set for one contract is small."
  [node contract-id]
  (->> (xt/q node (list 'from execution-table [{:contract contract-id} (symbol "*")]))
       (sort-by :at)
       vec))

;; ------------------------------------------------------------- request ids

(defn execution-for-request
  "The execution already recorded under `request-id` for this contract, or nil.

  Request ids are scoped to the contract, not global. The thing being
  prevented is the same action arriving twice -- a retried POST, a
  double-clicked button -- and that is always about one contract. Global
  uniqueness would additionally reject a caller who numbers its requests
  per contract, which is how a caller naturally numbers them."
  [node contract-id request-id]
  (when request-id
    (first (xt/q node (list 'from execution-table
                            [{:contract contract-id :request-id request-id}
                             (symbol "*")])))))

(defn- duplicate! [contract-id request-id prior]
  (throw (ex-info "This request has already been executed against this contract"
                  {:trustblocks/error :duplicate-request
                   :contract          contract-id
                   :request-id        request-id
                   :executed-at       (:at prior)})))

(defn- assert-unused
  "A transaction op that aborts the whole transaction if `request-id` has
  already been used on this contract.

  The query in execution-for-request is not enough on its own: two copies of
  the same request arriving together both read an empty table and both go on
  to write. This closes that, because XTDB evaluates the assertion inside the
  transaction it guards -- if it fails, neither the execution nor the new
  state lands.

  It is SQL rather than XTQL because XTDB removed XTQL DML in 2.0.0-beta7 and
  told callers to use ASSERT and SQL DML instead; :assert-not-exists now
  throws saying exactly that. Column names are the SQL spellings of the
  document's keys."
  [contract-id request-id]
  [:sql (str "ASSERT NOT EXISTS (SELECT 1 FROM execution "
             "WHERE contract = ? AND request_id = ?)")
   [contract-id request-id]])

(defn- assert-failed? [e]
  (= :xtdb/assert-failed (:xtdb.error/code (ex-data e))))

;; ---------------------------------------------------------------- trigger

(defn- run-clause
  "Runs one clause invocation, dispatching on its stored runtime. Only
  :clojure is implemented -- a :typescript clause is refused with a clear
  'not yet' error rather than attempting to eval TypeScript as Clojure or
  silently doing nothing. See `runtimes`."
  [runtime ctx src args]
  (case runtime
    :clojure (try
               (clause/evaluate ctx src args)
               (catch Exception e
                 ;; A clause throws to refuse -- "Already approved by the
                 ;; Board" is the contract saying no, not a failure. A clause
                 ;; with a bug throws the same way and is tagged the same way,
                 ;; because from outside the sandbox the two are
                 ;; indistinguishable: either way the clause ran and produced
                 ;; no response. The message is the clause's own.
                 (throw (ex-info (ex-message e)
                                 {:trustblocks/error :clause-refused}
                                 e))))
    :typescript (throw (ex-info
                        (str "TypeScript clause execution is not implemented "
                             "yet. The clause is stored -- see put-clause! and "
                             "com.trustblocks.template.archive -- but nothing "
                             "runs it.")
                        {:trustblocks/error :runtime-unavailable
                         :runtime           runtime}))
    (throw (ex-info "Unknown clause runtime"
                    {:trustblocks/error :runtime-unavailable
                     :runtime           runtime}))))

(defn trigger!
  "Run a contract's clause against `request` and record what happened.

  `now` is supplied rather than read, so the execution is a pure function of its
  inputs and can be replayed. Accord's own clause logic calls `new Date()`,
  which is why its executions cannot be.

  Everything crossing the boundary is validated: the request on the way in, the
  response and any new state on the way out. The clause body is opaque to us --
  we check the boundary, not the behaviour -- but the boundary is entirely
  Concerto, so it is checkable.

  `:request-id` names the request, and naming it makes the execution
  at-most-once: a second trigger carrying an id already used on this contract
  is refused rather than run again. Optional here, because at a REPL you see
  the result and there is nothing to retry; required by the HTTP endpoint,
  where a timeout leaves the caller unable to tell whether the first attempt
  landed. Re-running is not harmless -- a second snow-removal visit report
  would quietly pay the contractor twice.

  `:by` records who submitted it. Everything else here answers what happened
  and when; for a record that may be read in a dispute, who is the third
  question, and the one nothing else in the row answers.

  Errors carry a :trustblocks/error tag in their ex-data so a caller can tell
  a bad request from a refusal from a broken clause without reading messages.
  See com.trustblocks.app.trigger, which turns those tags into status codes."
  [node contract-id request now & {:keys [reg ctx request-id by]}]
  (let [reg (or reg (store/registry-from-xtdb node))
        ctx (or ctx (clause/context))
        contract (or (store/get-instance node contract-id :reg reg)
                     (throw (ex-info "No such contract"
                                     {:trustblocks/error :no-such-contract
                                      :contract          contract-id})))
        fqn      (get contract "$class")

        {src :source runtime :runtime}
        (or (get-clause node fqn)
            (throw (ex-info "No clause stored for this contract type"
                            {:trustblocks/error :no-clause
                             :contract          contract-id
                             :$class            fqn})))]

    ;; Checked before the clause runs as well as inside the write, so an
    ;; obvious retry costs nothing and is answered with the execution it
    ;; duplicates. assert-unused is what actually makes it safe.
    (when-let [prior (execution-for-request node contract-id request-id)]
      (duplicate! contract-id request-id prior))

    (when-let [errs (store/validate reg request)]
      (throw (ex-info "Request failed validation"
                      {:trustblocks/error :invalid-request
                       :$class            (get request "$class")
                       :errors            (restate-errors
                                           reg (get request "$class") errs)})))

    (let [state  (current-state node contract-id :reg reg)
          result (run-clause runtime ctx src {:data contract :request request
                                              :state state :now now})
          response (:response result)]

      (when-not response
        (throw (ex-info "Clause returned no :response"
                        {:trustblocks/error :invalid-clause-output
                         :contract          contract-id
                         :returned          (keys result)})))
      (doseq [[what instance] [[:response response] [:state (:state result)]]
              :when instance]
        (when-let [errs (store/validate reg instance)]
          (throw (ex-info (str "Clause produced an invalid " (name what))
                          {:trustblocks/error :invalid-clause-output
                           :$class            (get instance "$class")
                           :errors            (restate-errors
                                               reg (get instance "$class") errs)}))))

      (try
        (xt/execute-tx
         node
         (cond-> []
           request-id (conj (assert-unused contract-id request-id))

           :always
           (conj [:put-docs execution-table
                  (cond-> {:xt/id     (random-uuid)
                           :contract  contract-id
                           :at        now
                           :request   (k/->xtdb request)
                           :response  (k/->xtdb response)
                           :clause    src}
                    request-id (assoc :request-id request-id)
                    by         (assoc :by by))])

           ;; The new state is valid from the moment the request took effect,
           ;; not from when the database learned of it. That separation is the
           ;; whole point: a back-dated correction lands in valid time while
           ;; system time still records when we believed it.
           (:state result)
           (conj [:put-docs {:into state-table :valid-from (->instant now)}
                  (k/instance->doc (:state result)
                                   :identity-field (mm/identity-field
                                                    reg (get (:state result) "$class")))])))
        (catch Exception e
          (if (assert-failed? e)
            (duplicate! contract-id request-id
                        (execution-for-request node contract-id request-id))
            (throw e))))

      {:response   response
       :state      (:state result)
       :at         now
       :request-id request-id})))
