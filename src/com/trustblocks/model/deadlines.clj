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
  recorded when a person makes it and certifies it."
  (:require [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.store :as store]))

(defn- instant [x]
  (try (execute/->instant x) (catch Exception _ nil)))

(defn- terms-of
  "The terms of `document`'s matter, or the document itself when it is a
  matter's terms, or nil."
  [node reg document]
  (let [matter-id (or (matter/matter-of node document)
                      (:xt/id (first (filter #(= document (:matter/terms %)) (matter/all node)))))]
    (some->> matter-id (matter/get-matter node) :matter/terms
             (#(store/get-instance node % :reg reg)))))

(defn for-document
  "`document`'s deadlines, as of `now`: each {:name :description :document
  :due :status :met-at}, :status one of :upcoming, :passed, :met, or
  :not-started when what it counts from has not happened yet (then no :due)."
  [node reg document now]
  (let [instance (store/get-instance node document :reg reg)
        declared (some->> (get instance "$class") (execute/get-deadlines node))
        terms    (delay (terms-of node reg document))
        history  (delay (execute/status-history node document))]
    (vec
     (for [{:strs [name description fromField fromState days daysField source metBy]}
           (get declared "deadlines")

           :let [data    (if (= "TERMS" source) @terms instance)
                 start   (if fromField
                           (instant (get data fromField))
                           (some #(when (= fromState (:status %)) (:from %)) @history))
                 n       (or days (get data daysField))
                 due     (when (and start (number? n))
                           (.plus ^java.time.Instant start (java.time.Duration/ofDays (long n))))
                 met-at  (when start
                           (some #(when (and (some #{(:status %)} metBy)
                                             (not (.isBefore ^java.time.Instant (:from %) start)))
                                    (:from %))
                                 @history))]]
       (cond-> {:name name :description description :document document
                :status (cond (nil? due) :not-started
                              met-at     :met
                              (.isAfter ^java.time.Instant now due) :passed
                              :else      :upcoming)}
         due    (assoc :due due)
         met-at (assoc :met-at met-at))))))

(defn for-contract
  "The deadlines on contract `id`'s dashboard: its own, and when it is a
  matter's terms, its documents' too."
  [node reg id now]
  (let [matter-id (:xt/id (first (filter #(= id (:matter/terms %)) (matter/all node))))
        documents (when matter-id (map :xt/id (matter/documents node matter-id)))]
    (vec (mapcat #(for-document node reg % now) (cons id documents)))))
