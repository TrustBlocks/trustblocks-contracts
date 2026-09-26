(ns com.trustblocks.xtdb.matter
  "Matters: sets of related documents with one shared state.

  A template is one document; municipal work is several. The Street
  Resurfacing contract, STR-27, is a contract *and* a pay application each
  month, the notices that start and end it, an inspection before anything is
  paid. A street closing is a petition, resolutions, notices and a hearing.
  A matter is the live set: the documents, the terms they share, and the one
  state they advance. See docs/design/matters.md.

  A matter is anchored to its **terms** -- the stored instance whose data
  every document of the matter shares: for STR-27 the executed contract, for
  a proceeding its own record. The matter's state *is* the state
  com.trustblocks.xtdb.execute keeps for that instance, so everything
  trigger! guarantees holds unchanged at the level of the matter: a document
  dated before a state change already recorded is refused as out of order,
  concurrent documents cannot lose an update, and the state as of any date
  is a query.

  A document joins a matter with a role (\"pay-application\") from a date,
  and may point at a stored file (com.trustblocks.xtdb.document) -- the
  signed PDF the structured instance was taken from. Membership is
  bitemporal like everything else: which documents belonged to the matter
  on May 31 is a query. A document belongs to at most one matter.

  Each matter has terms of its own: two matters on one instance would share
  one state, and are refused.

  Refusals carry :trustblocks/error, as execute's do."
  (:require [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]))

(def matter-table :matter)
(def membership-table :matter-document)

(defn- refuse! [message data]
  (throw (ex-info message data)))

(defn- row
  "Row `id` of `table`, as of valid time `at` (default: now)."
  [node table id at]
  (first (xt/q node (list 'from table
                          (cond-> {:bind [{:xt/id id} (symbol "*")]}
                            at (assoc :for-valid-time (list 'at at)))))))

(defn get-matter
  "Matter `id`, or nil -- as of `at` when given."
  [node id & {:keys [at]}]
  (row node matter-table id at))

(defn all
  "Every matter open now, by id."
  [node]
  (->> (xt/q node (list 'from matter-table [(symbol "*")]))
       (sort-by :xt/id)
       vec))

(defn open!
  "Open matter `id`, anchored to the stored instance `terms` (see the ns
  docstring), valid from `opened-at`. Returns the matter record.

  `package` names the template package the matter's documents are drawn
  from, e.g. \"street-resurfacing\"."
  [node {:keys [id name package terms opened-at]}]
  (when-not (store/get-instance node terms)
    (refuse! "No instance to anchor the matter to"
             {:trustblocks/error :no-such-instance :instance terms}))
  (when (get-matter node id)
    (refuse! "A matter with this id is already open"
             {:trustblocks/error :matter-exists :matter id}))
  ;; A matter's state is its terms' state, so two matters on one instance
  ;; would silently share one state.
  (when-let [other (:xt/id (first (xt/q node (list 'from matter-table
                                                   [{:matter/terms terms} 'xt/id]))))]
    (refuse! "Another matter is already anchored to these terms"
             {:trustblocks/error :terms-in-other-matter :matter other :instance terms}))
  (let [record (cond-> {:xt/id id :matter/terms terms}
                 name    (assoc :matter/name name)
                 package (assoc :matter/package package))]
    (xt/execute-tx node [[:put-docs {:into matter-table :valid-from (execute/->instant opened-at)}
                          record]])
    record))

(defn matter-of
  "The id of the matter instance `document-id` belongs to, as of `at`
  (default: now), or nil."
  [node document-id & {:keys [at]}]
  (:matter (row node membership-table document-id at)))

(defn add-document!
  "Make stored instance `document-id` a document of matter `matter-id` with
  `role`, from `at`. `stored-document` optionally names the file it came
  from (a document id from com.trustblocks.xtdb.document). Returns the
  membership record.

  Refused when the matter or the instance does not exist, when the stored
  file is not on record, or when the document already belongs to another
  matter."
  [node matter-id document-id & {:keys [role stored-document at]}]
  (when-not (get-matter node matter-id)
    (refuse! "No such matter" {:trustblocks/error :no-such-matter :matter matter-id}))
  (when-not (store/get-instance node document-id)
    (refuse! "No such instance" {:trustblocks/error :no-such-instance :instance document-id}))
  (when (and stored-document (not (document/get-document node stored-document)))
    (refuse! "No such stored document"
             {:trustblocks/error :no-such-stored-document :stored-document stored-document}))
  (when-let [other (matter-of node document-id)]
    (when (not= other matter-id)
      (refuse! "This document already belongs to another matter"
               {:trustblocks/error :document-in-other-matter
                :document document-id :matter other})))
  (let [record (cond-> {:xt/id document-id :matter matter-id}
                 role            (assoc :role role)
                 stored-document (assoc :stored-document stored-document))]
    (xt/execute-tx node [[:put-docs {:into membership-table :valid-from (execute/->instant at)}
                          record]])
    record))

(defn documents
  "Matter `matter-id`'s documents as of `at` (default: now) -- membership
  records {:xt/id :matter :role :stored-document :joined}, in the order
  they joined, optionally only those with `role`."
  [node matter-id & {:keys [at role]}]
  (->> (xt/q node (list 'from membership-table
                        (cond-> {:bind [{:matter matter-id} (symbol "*") 'xt/valid-from]}
                          at (assoc :for-valid-time (list 'at at)))))
       (filter #(or (nil? role) (= role (:role %))))
       (map #(-> % (assoc :joined (.toInstant ^java.time.ZonedDateTime (:xt/valid-from %)))
                 (dissoc :xt/valid-from)))
       (sort-by :joined)
       vec))

(defn- terms-of [node matter-id]
  (or (:matter/terms (get-matter node matter-id))
      (refuse! "No such matter" {:trustblocks/error :no-such-matter :matter matter-id})))

(defn trigger!
  "Advance matter `matter-id`'s state with `request`, taking effect at
  `now` -- execute/trigger! against the matter's terms, with the same
  options (:request-id, :by, :reg, :ctx).

  `:document`, when given, is the document of this matter the request came
  from; it is checked to belong to the matter at `now` and recorded on the
  execution, so the matter's history says which document moved it."
  [node matter-id request now & {:keys [document] :as opts}]
  (let [terms (terms-of node matter-id)]
    (when document
      (let [of (matter-of node document :at (execute/->instant now))]
        (when (not= of matter-id)
          (refuse! "That document is not part of this matter"
                   {:trustblocks/error :document-not-in-matter
                    :document document :matter matter-id :belongs-to of}))))
    (apply execute/trigger! node terms request now (mapcat identity opts))))

(defn state
  "Matter `matter-id`'s state -- its terms' state -- as Concerto JSON, as of
  `at` (default: now), or nil before anything has advanced it."
  [node matter-id & {:keys [at reg]}]
  (execute/current-state node (terms-of node matter-id)
                         :reg reg :at (some-> at execute/->instant)))

(defn history
  "Every execution that advanced matter `matter-id`, oldest first -- each
  naming, under :document, the document it came from when there was one."
  [node matter-id]
  (execute/history node (terms-of node matter-id)))
