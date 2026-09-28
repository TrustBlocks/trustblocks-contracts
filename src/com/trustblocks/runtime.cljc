(ns com.trustblocks.runtime
  "One step of a document's life: the part of trigger! that is the
  contract's own, with no database and no clock -- so the app
  (com.trustblocks.xtdb.execute) and the Template Playground's logic worker
  (com.trustblocks.logic-bundle) take exactly the same step.

  The template's lifecycle decides whether the event may happen at all, and
  under which certification (com.trustblocks.lifecycle); its clause decides
  what follows (com.trustblocks.clause). With a lifecycle, the lifecycle
  owns the status: the clause's state is merged over the state it was
  given, under the new status, and the clause may leave the answer to the
  transition. What the host adds around this -- verifying the
  certification, validating the result against the model, recording it --
  stays the host's.

  Refusals carry :trustblocks/error, as execute's do."
  (:require [com.trustblocks.lifecycle :as lifecycle]))

(defn step
  "The result of `request` on document `id` (an instance of template root
  `root`) in `state`, at `now`: {:response :state :transition}.

    :lifecycle       the template's lifecycle, or nil
    :identity-field  the lifecycle state type's identifying property
    :certification   {:credential :authority ...}, verified by the caller
    :run-clause      a no-argument fn running the clause, or nil when the
                     template has none; it is given `state` by the caller

  Refuses a transition the lifecycle does not allow, a clause that leaves
  the wrong status, and a step with no response."
  [{:keys [lifecycle root id identity-field state request certification now run-clause]}]
  (let [status (get state "status")
        t      (when lifecycle
                 (lifecycle/transition lifecycle status request :certification certification))
        clause-result (when run-clause (run-clause))
        result (if-not lifecycle
                 clause-result
                 {:response (or (:response clause-result) (lifecycle/response status t now))
                  :state    (merge state
                                   (lifecycle/state lifecycle root id identity-field t)
                                   (:state clause-result))})]
    (when (and t (not= (get t "to") (get (:state result) "status")))
      (throw (ex-info (str "The clause did not leave the document in "
                           (get t "to") ", where its lifecycle says "
                           (get t "event") " leads")
                      {:trustblocks/error :invalid-clause-output
                       :contract          id
                       :expected-status   (get t "to")
                       :status            (get (:state result) "status")})))
    (when-not (:response result)
      (throw (ex-info "Clause returned no :response"
                      {:trustblocks/error :invalid-clause-output
                       :contract          id
                       :returned          (keys result)})))
    (assoc result :transition t)))

(defn next-steps
  "What can happen from `status` (nil before the lifecycle has begun): each
  transition's event, the state it leads to, and the certifications it
  accepts."
  [lifecycle status]
  (for [t (get lifecycle "transitions")
        :when (if status (some #{status} (get t "from")) (empty? (get t "from")))]
    {:event    (get t "event")
     :to       (get t "to")
     :requires (get t "requires")}))
