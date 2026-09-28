(ns com.trustblocks.logic-bundle
  "trustblocks-logic.js: a template's Clojure logic, run in the browser --
  what the Template Playground's logic worker loads in place of Accord's
  TypeScript runtime.

  It is the app's own runtime, compiled: the clause vocabulary
  (com.trustblocks.clause), the lifecycle (com.trustblocks.lifecycle) and
  the step trigger! takes (com.trustblocks.runtime) -- so a request gets
  the answer here that it gets in Trustblocks, to the character
  (test/com/trustblocks/clause_parity_test.cljc).

  Loaded as a classic script -- the playground's worker evaluates it from
  text, under a policy that allows no script URLs -- it defines one global,
  TrustblocksLogic:

    check(logic)               null, or why the vocabulary refuses the clause
    nextSteps(lifecycle, state)
                               what can happen from the state's status: each
                               {event, to, requires}
    identityField(model, name) the property concept `name` is identified by,
                               read from the model's CTO text
    trigger({logic, lifecycle, model, data, request, state, now, certification})
                               one step: {ok: true, response, state,
                               transition, certifiedAs, now}, or {ok: false,
                               code, error, requires?}. Never throws.

  What the app does around a step and this does not: verify who certified
  it (the playground simulates -- certifiedAs defaults to the first
  certification the transition accepts, and says so), validate the answer
  against the model (the playground has Concerto for that), and record it.
  `now` is the host's to give; without one, the step is taken at the
  current time, and the result says which."
  (:require [clojure.string :as str]
            [com.trustblocks.clause :as clause]
            [com.trustblocks.lifecycle :as lifecycle]
            [com.trustblocks.runtime :as runtime]))

(def version "0.1.0")

(def ^:private ctx (delay (clause/context)))

(defn- ->clj [x] (js->clj x))
(defn- ->js [x] (clj->js x))

(defn- short-name [fqn]
  (let [s (str fqn)] (subs s (inc (str/last-index-of s ".")))))

(defn- identity-field
  "The property concept `concept-name` is identified by, from CTO text."
  [model concept-name]
  (when (and model concept-name)
    (second (re-find (re-pattern (str "(?:concept|asset|participant|transaction|event)\\s+"
                                      concept-name "\\s+identified\\s+by\\s+(\\w+)"))
                     model))))

(defn- check [logic]
  (clause/check @ctx logic))

(defn- next-steps [lc state]
  (->js (runtime/next-steps (->clj lc) (get (->clj state) "status"))))

(defn- refusal [e]
  (let [data (ex-data e)]
    (cond-> {:ok false :error (ex-message e)
             :code (name (or (:trustblocks/error data) :error))}
      (:requires data) (assoc :requires (:requires data)))))

(defn- trigger [opts]
  (let [{:strs [logic lifecycle model data request state now certification]} (->clj opts)
        now    (or now (.toISOString (js/Date.)))
        root   (get data "$class")
        state  (not-empty state)
        status (get state "status")
        ;; Simulated: the playground has no signer. Unless told otherwise,
        ;; the step is certified the first way its transition accepts.
        certified-as
        (or (some-> certification (update-keys keyword))
            (when lifecycle
              (try (some-> (first (lifecycle/requirements lifecycle status request))
                           (#(hash-map :credential (get % "credential")
                                       :authority (get % "authority"))))
                   (catch :default _ nil))))]
    (try
      (let [result (runtime/step
                    {:lifecycle      lifecycle
                     :root           root
                     :id             (or (get data (identity-field model (short-name root)))
                                         (get data "$identifier"))
                     :identity-field (when lifecycle
                                       (identity-field model (get lifecycle "stateType")))
                     :state          state
                     :request        request
                     :certification  certified-as
                     :now            now
                     :run-clause     (when (not-empty logic)
                                       (fn []
                                         (try
                                           (clause/evaluate @ctx logic {:data data :request request
                                                                        :state state :now now})
                                           (catch :default e
                                             ;; The contract saying no -- or a bug; from
                                             ;; outside the sandbox, the same thing.
                                             (throw (ex-info (ex-message e)
                                                             {:trustblocks/error :clause-refused}))))))})]
        (->js {:ok          true
               :response    (:response result)
               :state       (:state result)
               :transition  (:transition result)
               :certifiedAs (when (:transition result) certified-as)
               :now         now}))
      (catch :default e
        (->js (refusal e))))))

(defn init []
  (set! (.-TrustblocksLogic js/globalThis)
        #js {:version       version
             :check         check
             :nextSteps     next-steps
             :identityField identity-field
             :trigger       trigger}))
