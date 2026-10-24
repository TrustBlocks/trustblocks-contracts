(ns com.trustblocks.xtdb.lifecycle-check
  "Does the text of a contract say what its lifecycle requires?

  A template's lifecycle (trustblocks-templates' lifecycle.json) lists the
  events that move a document and, for each, the certification it needs: a
  Receipt or an Attestation by someone holding a named authority. Nothing in
  the lifecycle makes the contract's TEXT say so, and municipal contracts are
  not always explicit about who attests or approves. This finds the gaps.

  A requirement is STATED when some clause in the release says it does. A
  library clause says so with `:clause/states`, a list of
  {:event \"InspectionCertified\" :authority \"INSPECT_PAY_APPLICATIONS\"}; the
  authority may be left out to state the event for any authority. Text a
  template writes itself says so through `release!`'s `:states`. Whether the
  words actually say it is for the author and a reader; what this checks is that
  someone has said they do, for every event, so a required certification
  cannot go unmentioned by accident.")

(defn requirements
  "Every certification `lifecycle` (a lifecycle.json map, string keys)
  requires: [{:event :credential :authority}], in order, one per transition and
  certification."
  [lifecycle]
  (vec (for [t (get lifecycle "transitions")
             r (get t "requires")]
         {:event (get t "event") :credential (get r "credential") :authority (get r "authority")})))

(defn stated?
  "Whether `states` ({:event :authority}) state requirement `req`."
  [states {:keys [event authority]}]
  (boolean (some (fn [s] (and (= event (:event s))
                              (or (nil? (:authority s)) (= authority (:authority s)))))
                 states)))

(defn unstated
  "The requirements of `lifecycle` that `states` do not state: findings
  {:rule :event :credential :authority :message}. :rule is :attestation-unstated
  or :receipt-unstated."
  [lifecycle states]
  (vec (for [{:keys [event credential authority] :as r} (requirements lifecycle)
             :when (not (stated? states r))]
         {:rule       (if (= "Receipt" credential) :receipt-unstated :attestation-unstated)
          :event      event
          :credential credential
          :authority  authority
          :message    (str "The text does not state that " event " needs "
                           (if (= "Receipt" credential) "a Receipt" "an Attestation")
                           " under the authority " authority)})))
