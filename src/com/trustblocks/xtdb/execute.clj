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
  (:require [com.trustblocks.clause :as clause]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.xtdb.keys :as k]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]))

(defn- ->instant
  "A Concerto DateTime is a string on the wire; XTDB's :valid-from needs an
  instant. This is the one place the conversion happens, and it happens in the
  adapter -- the clause layer never holds a platform date, which is what keeps
  a clause's answer the same wherever it runs."
  [dt]
  (cond
    (inst? dt) dt
    (string? dt) (-> (try (java.time.OffsetDateTime/parse dt)
                          (catch Exception _
                            (java.time.OffsetDateTime/parse (str dt "Z"))))
                     .toInstant)
    :else (throw (ex-info "Cannot read as a date-time" {:value dt}))))

(def clause-table    :clause)
(def state-table     :contract-state)
(def execution-table :execution)

;; ---------------------------------------------------------------- clauses

(defn put-clause!
  "Store the clause logic for a contract type, checked before it lands.

  Keyed by the `$class` it applies to, so a contract finds its own logic. The
  pre-flight check refuses a clause reaching for anything outside the
  vocabulary at ingestion, rather than discovering it mid-execution."
  [node fqn src & {:keys [valid-from ctx]}]
  (when-let [why (clause/check (or ctx (clause/context)) src)]
    (throw (ex-info (str "Clause refused by the vocabulary: " why)
                    {:$class fqn :reason why})))
  (xt/execute-tx node [[:put-docs (cond-> {:into clause-table}
                                    valid-from (assoc :valid-from valid-from))
                        {:xt/id fqn :clause/source src}]])
  {:$class fqn})

(defn get-clause [node fqn]
  (-> (xt/q node (list 'from clause-table [{:xt/id fqn} (symbol "*")]))
      first
      :clause/source))

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

;; ---------------------------------------------------------------- trigger

(defn trigger!
  "Run a contract's clause against `request` and record what happened.

  `now` is supplied rather than read, so the execution is a pure function of its
  inputs and can be replayed. Accord's own clause logic calls `new Date()`,
  which is why its executions cannot be.

  Everything crossing the boundary is validated: the request on the way in, the
  response and any new state on the way out. The clause body is opaque to us --
  we check the boundary, not the behaviour -- but the boundary is entirely
  Concerto, so it is checkable."
  [node contract-id request now & {:keys [reg ctx]}]
  (let [reg (or reg (store/registry-from-xtdb node))
        ctx (or ctx (clause/context))
        contract (or (store/get-instance node contract-id :reg reg)
                     (throw (ex-info "No such contract" {:contract contract-id})))
        fqn      (get contract "$class")
        src      (or (get-clause node fqn)
                     (throw (ex-info "No clause stored for this contract type"
                                     {:contract contract-id :$class fqn})))]

    (when-let [errs (store/validate reg request)]
      (throw (ex-info "Request failed validation"
                      {:$class (get request "$class") :errors errs})))

    (let [state  (current-state node contract-id :reg reg)
          result (clause/evaluate ctx src {:data contract :request request
                                           :state state :now now})
          response (:response result)]

      (when-not response
        (throw (ex-info "Clause returned no :response" {:contract contract-id
                                                        :returned (keys result)})))
      (doseq [[what instance] [[:response response] [:state (:state result)]]
              :when instance]
        (when-let [errs (store/validate reg instance)]
          (throw (ex-info (str "Clause produced an invalid " (name what))
                          {:$class (get instance "$class") :errors errs}))))

      (xt/execute-tx
       node
       (cond-> [[:put-docs execution-table
                 {:xt/id     (random-uuid)
                  :contract  contract-id
                  :at        now
                  :request   (k/->xtdb request)
                  :response  (k/->xtdb response)
                  :clause    src}]]

         ;; The new state is valid from the moment the request took effect, not
         ;; from when the database learned of it. That separation is the whole
         ;; point: a back-dated correction lands in valid time while system time
         ;; still records when we believed it.
         (:state result)
         (conj [:put-docs {:into state-table :valid-from (->instant now)}
                (k/instance->doc (:state result)
                                 :identity-field (mm/identity-field
                                                  reg (get (:state result) "$class")))])))

      {:response response
       :state    (:state result)
       :at       now})))
