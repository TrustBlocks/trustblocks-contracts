(ns com.trustblocks.model.deadlines
  "The deadlines a contract sets, worked out for one document: when each
  falls due, and whether it has been met, has passed, or is still ahead.

  A template declares them (trustblocks-templates' shared/model/deadline.cto):
  counted from a DateTime property or from when the document entered a
  lifecycle state, for a number of days or the days in an Integer property
  -- read from the document, or from its matter's terms -- and met in named
  states.

  Trustblocks is reactive. A deadline is shown -- on the timeline, on the
  dashboard's watch list -- and never acted on: nothing changes state and no
  notice goes out because a date passes. What follows is the Town's decision,
  recorded when a person makes it and certifies it.

  Pure: what a document's deadlines need is read for it by
  com.trustblocks.model.reads (:contract/deadlines), which calls `compute`."
  (:require [com.trustblocks.xtdb.execute :as execute]))

(defn- instant [x]
  (try (execute/->instant x) (catch Exception _ nil)))

(defn compute
  "`document`'s deadlines, as of `now`: each {:name :description :document
  :due :status :met-at}, :status one of :upcoming, :passed, :met, or
  :not-started when what it counts from has not happened yet (then no :due).

    instance        the document, as Concerto JSON
    declared        its template's declared Deadlines (Concerto JSON), or nil
    terms           its matter's terms instance, for a TERMS-sourced deadline
    status-history  execute/status-history of the document"
  [{:keys [document instance declared terms status-history now]}]
  (vec
   (for [{:strs [name description fromField fromState days daysField source metBy]}
         (get declared "deadlines")

         :let [data    (if (= "TERMS" source) terms instance)
               start   (if fromField
                         (instant (get data fromField))
                         (some #(when (= fromState (:status %)) (:from %)) status-history))
               n       (or days (get data daysField))
               due     (when (and start (number? n))
                         (.plus ^java.time.Instant start (java.time.Duration/ofDays (long n))))
               met-at  (when start
                         (some #(when (and (some #{(:status %)} metBy)
                                           (not (.isBefore ^java.time.Instant (:from %) start)))
                                  (:from %))
                               status-history))]]
     (cond-> {:name name :description description :document document
              :status (cond (nil? due) :not-started
                            met-at     :met
                            (.isAfter ^java.time.Instant now due) :passed
                            :else      :upcoming)}
       due    (assoc :due due)
       met-at (assoc :met-at met-at)))))
