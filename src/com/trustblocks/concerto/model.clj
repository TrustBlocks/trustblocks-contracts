(ns com.trustblocks.concerto.model
  "CTO models as data.

  XTDB has no schema of its own, so the Concerto model is stored *as data* and
  enforcement happens here, in Clojure, via Malli. A CTO file is parsed to
  Metamodel JSON by the Concerto CLI (the grammar is JavaScript; we do not
  reimplement it), then split into one record per declaration keyed by fully
  qualified name."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.trustblocks.concerto :as concerto]
            [malli.core :as m]
            [malli.util :as mu]))

(def default-concerto-cli
  (str (System/getProperty "user.home")
       "/Code/Accord/apap/node_modules/.bin/concerto"))

;; ---------------------------------------------------------------- parsing

(defn parse-cto
  "Parse a .cto file to Metamodel JSON. Returns a string-keyed map."
  [cto-path & {:keys [concerto-cli]}]
  (let [f   (io/file cto-path)
        ;; `or`, not an :or default -- callers thread an explicit nil through.
        cli (or concerto-cli default-concerto-cli)]
    (when-not (.exists f)
      (throw (ex-info "CTO file not found" {:path (str f)})))
    (when-not (.canExecute (io/file cli))
      (throw (ex-info "concerto CLI not found or not executable" {:cli cli})))
    (let [{:keys [exit out err]} (p/sh cli "parse" "--model" (.getPath f))]
      (when-not (zero? exit)
        (throw (ex-info "concerto parse failed" {:path (.getPath f) :exit exit :err err})))
      (json/parse-string out))))

(defn strip-locations
  "Remove source Range/Position nodes. They are byte offsets into the .cto text,
  not schema semantics, and they dominate the payload."
  [x]
  (cond
    (map? x)    (into {} (comp (remove (fn [[k _]] (= "location" k)))
                               (map (fn [[k v]] [k (strip-locations v)])))
                      x)
    (vector? x) (mapv strip-locations x)
    :else       x))

(defn- short-name [fqn] (last (str/split fqn #"\.")))

(defn declarations
  "Model metamodel -> seq of {:fqn :namespace :name :declaration}."
  [model]
  (let [ns- (get model "namespace")]
    (for [d (get model "declarations")]
      {:fqn         (str ns- "." (get d "name"))
       :namespace   ns-
       :name        (get d "name")
       :declaration d})))

(def ^:private type-identifier-class "concerto.metamodel@1.0.0.TypeIdentifier")

(defn namespace-index
  "Short type name -> namespace, from a model's own declarations plus its
  imports. A local declaration shadows an import of the same name."
  [model]
  (let [ns- (get model "namespace")]
    (merge
     (into {}
           (for [i (get model "imports")
                 :let [i-ns (get i "namespace")]
                 nm (case (get i "$class")
                      "concerto.metamodel@1.0.0.ImportType"  [(get i "name")]
                      "concerto.metamodel@1.0.0.ImportTypes" (get i "types")
                      [])]
             [nm i-ns]))
     (into {} (for [d (get model "declarations")] [(get d "name") ns-])))))

(defn qualify-types
  "Fill in the `namespace` of every TypeIdentifier in `x`, resolving short names
  through `ns-for`.

  The Concerto grammar writes type references unqualified -- `superType` and
  ObjectProperty carry a bare `name` -- so the namespace has to be recovered
  from the enclosing model's imports. Resolving that eagerly, once, is what
  keeps the registry pure data. The alternative is to keep the model's import
  resolver as a closure beside each declaration, which makes the registry
  unserializable and so unstorable, undiffable and unexportable.

  TypeIdentifier already carries an optional `namespace` in the metamodel, so a
  qualified declaration is still a legal metamodel value: nothing is mangled and
  nothing is invented. Walking on `$class` rather than on known parent fields
  means decorator type references and Concerto 3's map key/value types are
  covered without enumerating every site. A name that resolves to nothing is
  left bare, exactly as it arrived."
  [ns-for x]
  (cond
    (map? x)
    (let [m (update-vals x #(qualify-types ns-for %))]
      (if (and (= type-identifier-class (get m "$class"))
               (not (contains? m "namespace")))
        (if-let [ns- (ns-for (get m "name"))]
          (assoc m "namespace" ns-)
          m)
        m))

    (vector? x) (mapv #(qualify-types ns-for %) x)
    :else       x))

(defn type-fqn
  "TypeIdentifier -> fully qualified name. Stays unqualified when the name never
  resolved, in which case no declaration matches it and the caller stops there."
  [node]
  (when-let [nm (get node "name")]
    (if-let [ns- (get node "namespace")]
      (str ns- "." nm)
      nm)))

;; ---------------------------------------------------------------- registry

(defn registry
  "Build a lookup of every declaration across several parsed models, so that
  inheritance chains crossing namespaces (TemplateModel -> Clause) resolve.

  Every declaration is fully qualified on the way in, so the registry is pure
  data: it can be pr-str'd, stored, diffed and read back without losing the
  import context needed to interpret it."
  [models]
  (reduce (fn [acc model]
            (let [ns-for (namespace-index model)]
              (reduce (fn [acc {:keys [fqn declaration]}]
                        (assoc acc fqn {:declaration (qualify-types ns-for declaration)
                                        :namespace   (get model "namespace")}))
                      acc
                      (declarations model))))
          {}
          models))

(defn super-chain
  "FQN -> [fqn parent grandparent ...]. Concerto flattens a subclass into one
  tagged object, so validation needs every ancestor's properties."
  [reg fqn]
  (loop [fqn fqn, seen [], guard 0]
    (let [{:keys [declaration]} (get reg fqn)]
      (cond
        (nil? declaration) seen
        (< 20 guard)       (throw (ex-info "Cyclic supertype chain" {:fqn fqn :seen seen}))
        :else
        (let [seen' (conj seen fqn)]
          (if-let [super (type-fqn (get declaration "superType"))]
            (recur super seen' (inc guard))
            seen'))))))

;; ---------------------------------------------------------------- malli

(def ^:private scalar
  {"concerto.metamodel@1.0.0.StringProperty"   :string
   "concerto.metamodel@1.0.0.BooleanProperty"  :boolean
   ;; JSON has no int/double distinction for whole numbers, so a Double field
   ;; commonly arrives as `85` rather than `85.0`. Concerto's own validator
   ;; accepts that (verified against demandforecast and volumediscount), so
   ;; :double would be stricter than the spec.
   "concerto.metamodel@1.0.0.DoubleProperty"   number?
   "concerto.metamodel@1.0.0.IntegerProperty"  :int
   ;; Long is 64-bit in the spec but only 2^53-safe in Concerto's JS runtime.
   ;; On the JVM it is a true java.lang.Long and XTDB stores it as BIGINT, so
   ;; precision is only at risk when a value crosses the JS boundary (APAP).
   "concerto.metamodel@1.0.0.LongProperty"     :int
   "concerto.metamodel@1.0.0.DateTimeProperty" [:fn {:error/message "not a date-time"}
                                                #(or (string? %)
                                                     (instance? java.time.OffsetDateTime %)
                                                     (instance? java.time.Instant %))]})

(defn- enum? [reg fqn]
  (= "concerto.metamodel@1.0.0.EnumDeclaration"
     (get-in reg [fqn :declaration "$class"])))

(defn- enum-values [reg fqn]
  (into [:enum] (map #(get % "name")) (get-in reg [fqn :declaration "properties"])))

(defn- property->malli [reg prop]
  (let [base (or (scalar (get prop "$class"))
                 (when (= "concerto.metamodel@1.0.0.ObjectProperty" (get prop "$class"))
                   (let [fqn (type-fqn (get prop "type"))]
                     (if (enum? reg fqn) (enum-values reg fqn) :map)))
                 ;; relationships store the referenced id
                 (when (= "concerto.metamodel@1.0.0.RelationshipProperty" (get prop "$class"))
                   :string)
                 :any)]
    (cond-> base
      (get prop "isArray") (->> (conj [:sequential])))))

(defn ->malli
  "Compile a Malli schema for a $class, flattening its whole inheritance chain.

  Concerto and Malli both flatten inheritance, so the two line up field for
  field -- but only because $class is retained to name the leaf type. Never
  infer the subtype structurally; always drive it from $class."
  [reg fqn]
  (let [chain (super-chain reg fqn)]
    (when (empty? chain)
      (throw (ex-info "Unknown $class -- is its model loaded?" {:fqn fqn})))
    (let [entries
          (for [f     (reverse chain)          ; parents first, child overrides
                :let  [{:keys [declaration]} (get reg f)]
                prop  (get declaration "properties")
                :let  [k (concerto/field->xt (get prop "name"))]]
            (if (get prop "isOptional")
              [k {:optional true} (property->malli reg prop)]
              [k (property->malli reg prop)]))]
      (m/schema
       (into [:map
              ;; $class is the linchpin: it is what makes the flattening lossless.
              [:concerto/class [:= fqn]]
              [:concerto/identifier {:optional true} :string]]
             ;; later entries win, so a child redeclaring a field overrides
             (vals (into {} (map (fn [e] [(first e) e])) entries)))
       ;; :merge/:select-keys live in malli.util, not the default registry --
       ;; omitting this is the usual cause of "unknown type" errors.
       {:registry (merge (m/default-schemas) (mu/schemas))}))))

(defn identity-field
  "The `identified by` field for a declaration, inherited if not declared."
  [reg fqn]
  (some (fn [f] (get-in reg [f :declaration "identified" "name"]))
        (super-chain reg fqn)))

(defn property-names
  "XTDB key -> exact declared property name, across the whole inheritance chain.

  Required for a faithful read. Deriving the name back from the key with
  ->camelCase is lossy for anything that is not plain camelCase: `vechileID`
  would come back as `vechileId`, and `INITIAL_DUE` as `initialDue`. The model
  is the only authority on the original spelling."
  [reg fqn]
  (into {}
        (for [f    (super-chain reg fqn)
              prop (get-in reg [f :declaration "properties"])
              :let [nm (get prop "name")]]
          [(concerto/field->xt nm) nm])))
