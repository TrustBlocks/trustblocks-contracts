(ns com.trustblocks.xtdb.model-use
  "Which model properties are read by which clauses and templates.

  The type checker resolves every placeholder to a property, so it knows what a
  clause or a template reads. This records it, one row per owner and property,
  in the same transaction as the thing that reads it -- the way :clause-use
  records which template uses which clause -- so the question \"who reads
  municipalityName\" is a query and cannot drift from the text.

  A property is named by the type that DECLARES it: a field read through a
  subtype is the base type's field. Each row carries `:fqn` with the model's
  version in it (\"com.trustblocks.municipal.agreement@1.0.0.MunicipalAgreement.
  municipalityName\") and `:field` without it, so a question can ask for one
  version's property or for the property across every version.

  Rows for a clause version are written once, with the version, and never
  change. Rows for a template are replaced as its head moves (vanished ones
  deleted), so as of any date they say what the template then read."
  (:require [xtdb.api :as xt]))

(def table :model-use)

(def ^:private star (symbol "*"))

(defn- unindexed? [e] (= :xtdb/unindexed-tx (:xtdb.error/code (ex-data e))))

(defn- with-reads [f]
  (loop [n 0]
    (let [r (try {:ok (f)} (catch Exception e (if (and (unindexed? e) (< n 20)) {:retry e} (throw e))))]
      (if (contains? r :ok) (:ok r) (do (Thread/sleep (inc n)) (recur (inc n)))))))

(defn- from-q [binding {:keys [at as-of]}]
  (list 'from table (cond-> {:bind binding}
                      at    (assoc :for-valid-time (list 'at at))
                      as-of (assoc :for-system-time (list 'at as-of)))))

(defn row-id [kind owner fqn] (str (name kind) ":" owner "#" fqn))

(defn rows
  "The :model-use rows for `reads` (from templatemark model/reads) by `kind`
  (:clause or :template) and `owner` (a clause version id, or a template id),
  one per property. `version` is the clause version or template revision."
  [kind owner version reads]
  (->> (group-by :fqn reads)
       (sort-by key)
       (mapv (fn [[fqn rs]]
               {:xt/id      (row-id kind owner fqn)
                :owner-kind (name kind)
                :owner      owner
                :version    version
                :fqn        fqn
                :field      (:field (first rs))
                :direct     (boolean (some (comp nil? :clause) rs))
                :nodes      (pr-str (vec (sort (distinct (keep :node-id rs)))))
                :via        (pr-str (vec (sort-by str (distinct (keep :clause rs)))))}))))

(defn- row->read [r]
  {:kind (keyword (:owner-kind r)) :owner (:owner r) :version (:version r)
   :fqn (:fqn r) :field (:field r) :direct (boolean (:direct r))})

(defn reads-of
  "What `owner` (a template id, or a clause version id like \"indemnity@3\") of
  `kind` reads: [{:fqn :field :direct :nodes :via}] as of `:at` / `:as-of`.
  :direct is true when the owner's own text reads it, :via lists the clauses
  [id version] it reads it through."
  [node kind owner & {:as opts}]
  (->> (with-reads #(xt/q node (from-q [{:owner-kind (name kind) :owner owner} star] opts)))
       (map (fn [r] {:fqn (:fqn r) :field (:field r) :direct (boolean (:direct r))
                     :nodes (read-string (:nodes r)) :via (read-string (:via r))}))
       (sort-by :fqn) vec))

(defn readers
  "Everything that reads property `p`, a versioned FQN or an unversioned
  field: [{:kind :owner :version :fqn :field :direct}] as of `:at` / `:as-of`. A
  template appears at its head; a clause appears once per version."
  [node p & {:as opts}]
  (->> (concat (with-reads #(xt/q node (from-q [{:fqn p} star] opts)))
               (with-reads #(xt/q node (from-q [{:field p} star] opts))))
       (map row->read)
       distinct
       (sort-by (juxt :kind :owner :version))
       vec))

(defn fields-read
  "Every property read by anything, as the unversioned :field, with the number
  of owners that read it: {field n}."
  [node & {:as opts}]
  (->> (with-reads #(xt/q node (from-q [{:field 'f :owner 'o} star] opts)))
       (map (juxt :field :owner)) distinct
       (reduce (fn [m [f _]] (update m f (fnil inc 0))) (sorted-map))))
