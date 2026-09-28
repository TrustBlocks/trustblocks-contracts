(ns com.trustblocks.lifecycle
  "A document's lifecycle, as its template declares it -- and the one
  question a runtime asks of it: may this event happen now?

  The lifecycle is the contract's, not ours. A template carries it as data
  (package.json's trustblocks.lifecycle, typed by
  com.trustblocks.lifecycle@1.0.0.Lifecycle -- see trustblocks-templates'
  docs/model-conventions.md): the states, and for each event the states it
  may occur in, the state it leads to, and the Receipt or Attestation, under
  which authority, that certifies it. trustblocks-templates' bb check has
  already held it to being a sound state machine.

  What the contract cannot do for itself is the environment's part, and the
  caller's: verify that a certification is genuine and was held under the
  authority it names at the time of the event (com.trustblocks.xtdb.
  certification), and supply the time. This namespace takes both as given
  and applies the declaration. It is pure -- no database, no clock -- so the
  same check runs wherever the template does.

  Refusals carry :trustblocks/error, as execute's do."
  (:require [clojure.string :as str]))

(def lifecycle-ns "com.trustblocks.lifecycle@1.0.0")

(defn- refuse! [message data]
  (throw (ex-info message data)))

(defn event-name
  "A request's type, by its name in its namespace -- how a lifecycle names
  events: com.x@1.0.0.PaymentApproved -> PaymentApproved."
  [request]
  (let [fqn (str (get request "$class"))]
    (subs fqn (inc (str/last-index-of fqn ".")))))

(defn state-type
  "The fully-qualified state type of the lifecycle of template root `root`
  (its stateType, in the root's namespace)."
  [lifecycle root]
  (str (subs root 0 (str/last-index-of root ".")) "." (get lifecycle "stateType")))

(defn- meets? [certification {:strs [credential authority]}]
  (and (= credential (:credential certification))
       (= authority (:authority certification))))

(defn- find-transition [lifecycle status event]
  (first (filter #(and (= event (get % "event"))
                       (if status
                         (some #{status} (get % "from"))
                         (empty? (get % "from"))))
                 (get lifecycle "transitions"))))

(defn- no-transition! [event status]
  (refuse! (str event " cannot happen "
                (if status (str "in state " status) "before the lifecycle has begun"))
           {:trustblocks/error :no-transition :event event :status status}))

(defn requirements
  "The certifications that can certify `request` from `status` -- what a
  host asks before it knows who will sign: the {\"credential\"
  \"authority\"} maps the transition accepts, empty for a timed event.
  Refuses :no-transition as transition does."
  [lifecycle status request]
  (let [event (event-name request)]
    (get (or (find-transition lifecycle status event) (no-transition! event status))
         "requires")))

(defn transition
  "The transition `request` takes from `status` (nil before the lifecycle
  has begun), given the verified `certification` -- {:credential
  \"Receipt\"|\"Attestation\" :authority :did}. Returns the transition;
  refuses otherwise:

  - :no-transition -- the event cannot happen in this state, or is not one
    of this lifecycle's events at all;
  - :certification-required -- no certification the transition accepts.

  Every transition is certified: nothing moves a document on its own. A
  date passing is a deadline (com.trustblocks.model.deadlines), shown and
  never acted on."
  [lifecycle status request & {:keys [certification]}]
  (let [event (event-name request)
        t     (or (find-transition lifecycle status event) (no-transition! event status))]
    (when-not (some #(meets? certification %) (get t "requires"))
      (refuse! (str event " must be certified by "
                    (str/join " or " (map #(str (get % "credential") " under " (get % "authority"))
                                          (get t "requires"))))
               {:trustblocks/error :certification-required
                :event             event
                :requires          (get t "requires")
                :given             (dissoc certification :jwt)}))
    t))

(def attestation-request "com.trustblocks.attestation@1.0.0.AttestationRequest")

(defn attestation-authority
  "The authority `lifecycle` requires of an Attestation to the contract --
  the shared AttestationRequest -- or nil when it accepts none."
  [lifecycle]
  (some (fn [t]
          (when (= (subs attestation-request (inc (str/last-index-of attestation-request ".")))
                   (get t "event"))
            (some #(when (= "Attestation" (get % "credential")) (get % "authority"))
                  (get t "requires"))))
        (get lifecycle "transitions")))

(defn state
  "The state a transition leaves document `id` in, for a template with no
  clause of its own: its state type, with its status. `identity-field` is
  the state type's identifying property."
  [lifecycle root id identity-field t]
  {"$class"       (state-type lifecycle root)
   identity-field id
   "status"       (get t "to")})

(defn response
  "What a template with no clause answers an event with: the transition
  taken."
  [status t now]
  (cond-> {"$class"     (str lifecycle-ns ".TransitionResponse")
           "$timestamp" now
           "event"      (get t "event")
           "to"         (get t "to")}
    status (assoc "from" status)))
