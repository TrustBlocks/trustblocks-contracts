(ns com.trustblocks.concerto
  "Bijective transform between Concerto JSON instances and XTDB records.

  XTDB 2 normalizes column names on write, and the normalization is lossy in
  ways that matter here (all verified empirically against XTDB 2.1.0):

    :$class        -> :/class          ($ is read as a namespace separator)
    :businessDays  -> :businessdays    (camelCase is lowercased -- NOT reversible)
    :business_days -> :business-days   (underscore becomes hyphen)
    :business-days -> :business-days   (kebab-case is stable)
    :concerto/class-> :concerto/class  (namespaced keywords are stable)

  Storing a Concerto instance verbatim therefore destroys `$class` -- the tag
  the whole mapping depends on -- and flattens field-name casing. Worse,
  `business_days` and `business-days` collide silently, last write winning.

  So we canonicalize on the way in and restore on the way out:
    $class      -> :concerto/class
    $identifier -> :concerto/identifier
    camelCase   -> kebab-case"
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]))

(def ^:private reserved
  {"$class"      :concerto/class
   "$identifier" :concerto/identifier
   "$timestamp"  :concerto/timestamp})

(def ^:private reserved-inverse
  (into {} (map (fn [[k v]] [v k])) reserved))

(defn field->xt
  "Concerto property name -> XTDB-safe keyword."
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
  gets its own declared names rather than its parent's. Without it we fall back
  to ->camelCase, which is lossy for names like `vechileID` or `INITIAL_DUE`."
  ([record] (<-xtdb record nil))
  ([record names-for]
   (let [known (when names-for
                 (some-> (:concerto/class record) names-for))]
     (reduce-kv
      (fn [acc k v]
        (if (= :xt/id k)
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
