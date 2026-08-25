(ns com.trustblocks.xtdb.keys
  "Concerto instances <-> XTDB records.

  This is the storage adapter. `concerto-clj` knows nothing about XTDB and takes
  a `key-fn`; everything XTDB-shaped lives here.

  XTDB 2 normalizes column names on write, and the normalization is lossy in
  ways that matter (all verified empirically against XTDB 2.1.0):

    :$class        -> :/class          ($ is read as a namespace separator)
    :businessDays  -> :businessdays    (camelCase is lowercased -- NOT reversible)
    :business_days -> :business-days   (underscore becomes hyphen)
    :business-days -> :business-days   (kebab-case is stable)
    :concerto/class-> :concerto/class  (namespaced keywords are stable)

  Storing a Concerto instance verbatim therefore destroys `$class` -- the tag
  the whole mapping depends on -- and flattens field-name casing. Worse,
  `business_days` and `business-days` collide silently, last write winning.

  So we canonicalize on the way in and restore on the way out. Note that this
  transform is the *reason* the library keeps a neutral EDN layer: at that
  layer `:$class` and `:businessDays` are legal keywords that need no reverse
  mapping at all. Everything below is the price of XTDB specifically, and no
  other adapter should have to pay it."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [com.trustblocks.concerto.metamodel :as mm]))

(def ^:private reserved
  {"$class"      :concerto/class
   "$identifier" :concerto/identifier
   "$timestamp"  :concerto/timestamp})

(def ^:private reserved-inverse
  (into {} (map (fn [[k v]] [v k])) reserved))

(defn field->xt
  "Concerto property name -> XTDB-safe keyword. This is the `key-fn` handed to
  `concerto-clj` so that compiled schemas are keyed the same way as the records
  they validate."
  [field-name]
  (or (reserved field-name)
      (csk/->kebab-case-keyword field-name)))

(defn xt->field
  "XTDB keyword -> Concerto property name. `known` optionally maps the kebab
  keyword back to the exact declared name, which is authoritative; without it we
  fall back to ->camelCase, correct for every name Concerto's grammar allows."
  ([k] (xt->field k nil))
  ([k known]
   (or (reserved-inverse k)
       (get known k)
       (csk/->camelCaseString (name k)))))

(defn property-names
  "XTDB key -> exact declared property name, across the whole inheritance chain.

  Required for a faithful read. Deriving the name back from the key with
  ->camelCase is lossy for anything that is not plain camelCase: `vechileID`
  would come back as `vechileId`, and `INITIAL_DUE` as `initialDue`. The model
  is the only authority on the original spelling.

  This function exists only because XTDB forces kebab-case on us -- an adapter
  that can store `:businessDays` needs nothing of the sort."
  [reg fqn]
  (into {}
        (for [f    (mm/super-chain reg fqn)
              prop (get-in reg [f :declaration :properties])
              :let [nm (:name prop)]]
          [(field->xt nm) nm])))

(defn- collisions
  "Concerto field names in `m` that would normalize onto the same XTDB key.
  XTDB would silently drop all but one, so we refuse the write instead."
  [m]
  (->> (keys m)
       (remove #(str/starts-with? % "$"))
       (group-by field->xt)
       (filter (fn [[_ names]] (< 1 (count names))))
       (into {})))

(defn ->xtdb
  "Concerto JSON instance (string keys) -> XTDB record. Recurses into nested
  concepts and arrays, which carry their own $class."
  [instance]
  (when-let [c (not-empty (collisions instance))]
    (throw (ex-info "Concerto field names collide under XTDB key normalization"
                    {:collisions c :$class (get instance "$class")})))
  (reduce-kv
   (fn [acc k v]
     (assoc acc (field->xt k)
            (cond
              (map? v)                     (->xtdb v)
              (and (vector? v)
                   (some map? v))          (mapv #(if (map? %) (->xtdb %) %) v)
              :else                        v)))
   {}
   instance))

(defn <-xtdb
  "XTDB record -> Concerto JSON instance. Drops XTDB's own :xt/id, which is a
  copy of the identity field and would otherwise appear as a bogus property.

  `names-for` is an optional (fn [$class] -> {xt-key declared-name}). It is
  resolved per level from that level's own :concerto/class, so a nested concept
  gets its own declared names rather than its parent's.

  Dropping the derived keys here is what lets the compiled schemas stay closed:
  the library emits schemas over pure Concerto properties, and the adapter is
  responsible for not handing it anything else."
  ([record] (<-xtdb record nil))
  ([record names-for]
   (let [known (when names-for
                 (some-> (:concerto/class record) names-for))]
     (reduce-kv
      (fn [acc k v]
        ;; Namespaced keys other than the reserved concerto/* ones are our own
        ;; derived metadata (party indexes, effective dates). They must not
        ;; reappear as Concerto properties, or the instance stops validating.
        (if (or (= :xt/id k)
                (and (namespace k) (not (reserved-inverse k))))
          acc
          (assoc acc (xt->field k known)
                 (cond
                   (map? v)            (<-xtdb v names-for)
                   (and (vector? v)
                        (some map? v)) (mapv #(if (map? %) (<-xtdb % names-for) %) v)
                   :else               v))))
      {}
      record))))

(defn instance->doc
  "Concerto instance -> XTDB document, ready for :put-docs.

  The identity field becomes :xt/id. Concerto's $identifier already holds that
  value (it mirrors the `identified by` field), so we prefer it and fall back to
  the declared identity field for models that predate it."
  [instance & {:keys [identity-field]}]
  (let [id (or (get instance "$identifier")
               (when identity-field (get instance identity-field)))]
    (when-not id
      (throw (ex-info "Concerto instance has no identifier"
                      {:$class (get instance "$class")
                       :identity-field identity-field})))
    (assoc (->xtdb instance) :xt/id id)))
