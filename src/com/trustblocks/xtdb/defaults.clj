(ns com.trustblocks.xtdb.defaults
  "Default data: values a model's fields start with, kept apart from the
  model that says what the fields are.

  The Town is always the Town of China Grove, in Rowan County, North Carolina,
  and acts through its Town Council. Those are facts about the municipality,
  not about any one contract, and each contract's model (MunicipalAgreement
  and what extends it) already has fields for them. A DEFAULTS record supplies
  their values so a draft begins filled in, and the author overrides only what
  differs.

  A record is a set of values for one type of one pinned model:

    {:defaults/id      \"town\"
     :defaults/applies {:model \"com.trustblocks.municipal.agreement@1.0.0\"
                        :type  \"MunicipalAgreement\"}
     :defaults/data    {\"municipalityName\" \"China Grove\" ...}}

  It is versioned like a clause: every change is a new immutable version
  (\"town@3\") with the hash of the one before it, and nothing already stored is
  altered. Data and schema are versioned separately -- a change to the Town's
  address is a new defaults version, not a new model version. A version's
  valid time is when it takes effect (the day a name changes), so `in-force`
  answers \"what were the Town's defaults on March 3\".

  The values must fit the type they are for: every name is a property of it,
  every value of the right kind. They are flat -- scalars, enums and lists of
  them -- for now.

  `prefill` layers defaults under a contract's own data and says which fields
  came from where. `snapshot` is what a release takes: the exact versions and
  their values, so changing the Town later never alters a released contract."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [com.trustblocks.templatemark.clauses :as clauses]
            [com.trustblocks.templatemark.model :as model]
            [com.trustblocks.xtdb.model-repository :as repo]
            [xtdb.api :as xt]))

(def table :defaults-version)

(def ^:private star (symbol "*"))

(defn- refuse [error msg data]
  (throw (ex-info msg (assoc data :trustblocks/error error))))

(defn- unindexed? [e] (= :xtdb/unindexed-tx (:xtdb.error/code (ex-data e))))

(defn- assert-failed? [e] (= :xtdb/assert-failed (:xtdb.error/code (ex-data e))))

(defn- with-reads [f]
  (loop [n 0]
    (let [r (try {:ok (f)} (catch Exception e (if (and (unindexed? e) (< n 20)) {:retry e} (throw e))))]
      (if (contains? r :ok) (:ok r) (do (Thread/sleep (inc n)) (recur (inc n)))))))

(defn- ->instant [x] (cond (nil? x) nil (instance? java.time.Instant x) x :else (java.time.Instant/parse x)))

(defn- from-q [binding {:keys [at as-of]}]
  (list 'from table (cond-> {:bind binding}
                      at    (assoc :for-valid-time (list 'at at))
                      as-of (assoc :for-system-time (list 'at as-of)))))

(defn version-id [id version] (str id "@" version))

(defn defaults-hash
  "The SHA-256 of a defaults record's content: what it applies to and its data."
  [{:defaults/keys [applies data]}]
  (clauses/sha-256 (pr-str (clauses/canonical {:applies applies :data data}))))

;; -------------------------------------------------------------------- rows

(defn- ->row [{:defaults/keys [id version applies data hash parent-hash note author]}]
  (cond-> {:xt/id (version-id id version) :defaults-id id :version version
           :applies (pr-str applies) :data (pr-str data) :hash hash}
    parent-hash (assoc :parent-hash parent-hash)
    note        (assoc :note note)
    author      (assoc :author author)))

(defn- row-> [row]
  (cond-> #:defaults{:id      (:defaults-id row)
                     :version (:version row)
                     :applies (edn/read-string (:applies row))
                     :data    (edn/read-string (:data row))
                     :hash    (:hash row)}
    (:parent-hash row) (assoc :defaults/parent-hash (:parent-hash row))
    (:note row)        (assoc :defaults/note (:note row))
    (:author row)      (assoc :defaults/author (:author row))))

(defn- verified [row]
  (let [d (row-> row)]
    (when-not (= (:hash row) (defaults-hash d))
      (refuse :defaults-corrupt
              (str "Defaults " (:xt/id row) " no longer match their hash; they were altered after they were stored")
              {:defaults (:defaults-id row) :version (:version row)}))
    d))

;; ------------------------------------------------------------------- reads

(defn- version-rows [node id & {:as opts}]
  (->> (with-reads #(xt/q node (from-q [{:defaults-id id} star] opts)))
       (sort-by :version)))

(defn get-version
  "Version `version` of defaults `id`, or nil. Throws :defaults-corrupt if
  what is stored no longer matches its hash."
  [node id version & {:as opts}]
  (some-> (first (with-reads #(xt/q node (from-q [{:xt/id (version-id id version)} star] opts))))
          verified))

(defn latest [node id & {:as opts}]
  (some-> (last (version-rows node id opts)) verified))

(defn versions
  "A summary of every version of `id`, lowest first: {:version :hash :parent-hash :note :author}."
  [node id]
  (mapv #(select-keys % [:version :hash :parent-hash :note :author]) (version-rows node id)))

(defn defaults-ids [node]
  (->> (with-reads #(xt/q node '(from :defaults-version [defaults-id])))
       (map :defaults-id) distinct sort vec))

(defn in-force
  "The version of defaults `id` that applied at `:at` (default now): the
  highest version that had taken effect by then. With `:as-of`, as we
  understood it at that system time. Nil if none."
  [node id & {:keys [at as-of]}]
  (some-> (last (version-rows node id {:at (or at (java.time.Instant/now)) :as-of as-of})) verified))

(defn lookup
  "A `(fn [id pin])`: the exact version for an integer pin, otherwise (:latest
  or nil) the one in force at `:at`."
  [node & {:keys [at]}]
  (fn [id pin]
    (if (integer? pin) (get-version node id pin) (in-force node id :at at))))

;; ------------------------------------------------------------- validation

(defn- enum-values [reg type-fqn]
  (set (map :name (get-in reg [type-fqn :declaration :properties]))))

(defn- value-problem
  "Why `v` does not fit the property description `d`, or nil."
  [reg d v]
  (let [one (fn [x]
              (case (:kind d)
                :string   (when-not (string? x) "a string")
                :double   (when-not (number? x) "a number")
                (:integer :long) (when-not (integer? x) "a whole number")
                :boolean  (when-not (boolean? x) "true or false")
                :datetime (when-not (and (string? x) (try (java.time.OffsetDateTime/parse x) true (catch Exception _ false)))
                            "a date-time like 2025-07-01T00:00:00.000Z")
                :enum     (when-not ((enum-values reg (:type d)) x)
                            (str "one of " (str/join ", " (sort (enum-values reg (:type d))))))
                (str "a " (name (:kind d)) ", which defaults do not hold yet")))]
    (if (:array? d)
      (if (sequential? v) (some one v) "a list")
      (one v))))

(defn check-data
  "Problems with `data` as defaults for `type` of `model-ns`: [{:property
  :rule :message}], empty when it fits. Rules: :unknown-property,
  :bad-value."
  [node model-ns type data]
  (let [reg      (repo/registry node model-ns)
        type-fqn (str model-ns "." type)]
    (when-not (get reg type-fqn)
      (refuse :invalid-defaults (str "Model " model-ns " has no type " type) {:model model-ns :type type}))
    (let [props (into {} (map (fn [p] [(:name p) (model/describe reg p)])) (model/properties reg type-fqn))]
      (vec (for [[k v] (sort-by key data)
                 :let [d (props k)
                       why (when d (value-problem reg d v))]
                 :when (or (nil? d) why)]
             (if d
               {:property k :rule :bad-value :message (str k " should be " why)}
               {:property k :rule :unknown-property
                :message (str k " is not a property of " type)}))))))

;; ------------------------------------------------------------------ writes

(defn put-version!
  "Save `record` -- {:defaults/id :defaults/applies {:model :type} :defaults/data}
  -- as the next version of its id and return it with :defaults/version,
  :defaults/hash and :defaults/parent-hash.

  Refused (:invalid-defaults, with :problems) if the data does not fit the
  type it is for. Identical to the latest version's content: nothing is
  written and that version is returned. The version is written once; a
  concurrent writer that took the number first makes this try again.

  Options: `:valid-from` (when it takes effect; default now), `:by`, `:note`."
  [node {:defaults/keys [id applies data] :as record} & {:keys [valid-from by note]}]
  (when-not (and (string? id) (re-matches #"[A-Za-z][A-Za-z0-9_.-]*" id))
    (refuse :invalid-defaults (str "A defaults id starts with a letter and holds letters, digits, . _ -; not " (pr-str id)) {:defaults id}))
  (when-not (and (map? data) (every? string? (keys data)))
    (refuse :invalid-defaults "Defaults data is a map from property name (a string) to value" {:defaults id}))
  (when-not (and (string? (:model applies)) (string? (:type applies)))
    (refuse :invalid-defaults "Defaults say what they are for: :applies {:model \"ns@1.0.0\" :type \"Type\"}" {:defaults id}))
  (when-let [problems (seq (check-data node (:model applies) (:type applies) data))]
    (refuse :invalid-defaults
            (str "Defaults " id " do not fit " (:type applies) ": " (str/join "; " (map :message problems)))
            {:defaults id :problems (vec problems)}))
  (let [base (select-keys record [:defaults/id :defaults/applies :defaults/data])
        h    (defaults-hash base)
        vf   (or (->instant valid-from) (java.time.Instant/now))]
    (loop [attempt 0]
      (let [prior (latest node id)]
        (if (and prior (= h (:defaults/hash prior)))
          prior
          (let [d   (cond-> (assoc base :defaults/version (inc (or (:defaults/version prior) 0)) :defaults/hash h)
                      prior (assoc :defaults/parent-hash (:defaults/hash prior))
                      note  (assoc :defaults/note note)
                      by    (assoc :defaults/author by))
                vid (version-id id (:defaults/version d))
                r   (try
                      (xt/execute-tx node [[:sql "ASSERT NOT EXISTS (SELECT 1 FROM defaults_version WHERE _id = ?)" [vid]]
                                           [:put-docs {:into table :valid-from vf} (->row d)]])
                      d
                      (catch Exception e (if (and (assert-failed? e) (< attempt 5)) ::taken (throw e))))]
            (if (= ::taken r) (recur (inc attempt)) r)))))))

;; ---------------------------------------------------------- using defaults

(defn- resolve-ref
  "[id pin] -> the defaults version it names, or throw :no-such-defaults."
  [node [id pin] at]
  (or ((lookup node :at at) id pin)
      (refuse :no-such-defaults (str "No defaults " id (when (integer? pin) (str " version " pin))) {:defaults id :pin pin})))

(defn prefill
  "A contract's `data` (property name -> value) with defaults beneath it. `refs`
  is a vector of [id pin] (pin an integer, or :latest for the version in force
  at `:at`, default now); later refs override earlier ones, and the contract's
  own data overrides them all.

  Returns {:data merged :applied [{:defaults :version :hash :fields #{...}}]},
  where :fields are the properties that defaults actually supplied -- not
  those the contract overrode -- so a screen can show which values are the
  Town's and which are the author's."
  [node refs data & {:keys [at]}]
  (let [ds      (mapv #(resolve-ref node % at) refs)
        layered (reduce (fn [m d] (merge m (:defaults/data d))) {} ds)
        merged  (merge layered data)
        supplied (fn [d i]
                   (->> (:defaults/data d) keys
                        (remove #(contains? data %))
                        (remove (fn [k] (some #(contains? (:defaults/data %) k) (drop (inc i) ds))))
                        set))]
    {:data    merged
     :applied (vec (map-indexed (fn [i d] {:defaults (:defaults/id d) :version (:defaults/version d)
                                           :hash (:defaults/hash d) :fields (supplied d i)})
                                ds))}))

(defn snapshot
  "What a release takes of the defaults it was drafted from: for each of
  `refs`, the exact version and its values: [{:defaults :version :hash :applies
  :data}]. Resolved once, so :latest becomes a number and the release keeps the
  values themselves."
  [node refs & {:keys [at]}]
  (mapv (fn [ref]
          (let [d (resolve-ref node ref at)]
            {:defaults (:defaults/id d) :version (:defaults/version d) :hash (:defaults/hash d)
             :applies (:defaults/applies d) :data (:defaults/data d)}))
        refs))

(defn snapshot-hash
  "A fingerprint of a snapshot: which versions, with which content."
  [snap]
  (when (seq snap)
    (clauses/sha-256 (pr-str (clauses/canonical (mapv #(select-keys % [:defaults :version :hash]) snap))))))

(defn verify-snapshot
  "Hold a snapshot up against itself and the library: what differs. [] when it
  holds. {:rule ... :defaults id :version n}:
    :defaults-altered   the snapshot's values no longer give the hash it recorded
    :defaults-missing   that version is no longer in the library
    :defaults-changed   the library's version no longer has that hash
    :defaults-corrupt   the library's version no longer matches its own hash"
  [node snap]
  (vec (mapcat (fn [{:keys [defaults version hash applies data]}]
                 (concat
                  (when (not= hash (defaults-hash {:defaults/applies applies :defaults/data data}))
                    [{:rule :defaults-altered :defaults defaults :version version
                      :message (str "The values kept for defaults " defaults " version " version " no longer give their hash")}])
                  (try
                    (let [d (get-version node defaults version)]
                      (cond
                        (nil? d) [{:rule :defaults-missing :defaults defaults :version version
                                   :message (str "Defaults " defaults " version " version " is no longer in the library")}]
                        (not= hash (:defaults/hash d))
                        [{:rule :defaults-changed :defaults defaults :version version
                          :message (str "Defaults " defaults " version " version " has changed since this was released")}]))
                    (catch clojure.lang.ExceptionInfo e
                      [{:rule :defaults-corrupt :defaults defaults :version version :message (ex-message e)}]))))
               snap)))
