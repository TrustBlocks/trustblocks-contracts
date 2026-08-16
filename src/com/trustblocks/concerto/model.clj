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

(defn type-resolver
  "Resolve an unqualified type name (as it appears in `superType` and
  ObjectProperty) to a fully qualified name, using the model's own namespace
  plus its imports."
  [model]
  (let [ns-   (get model "namespace")
        local (into {} (for [d (get model "declarations")]
                         [(get d "name") (str ns- "." (get d "name"))]))
        imported
        (into {}
              (for [i (get model "imports")
                    :let [i-ns (get i "namespace")]
                    nm (case (get i "$class")
                         "concerto.metamodel@1.0.0.ImportType"  [(get i "name")]
                         "concerto.metamodel@1.0.0.ImportTypes" (get i "types")
                         [])]
                [nm (str i-ns "." nm)]))]
    (fn [nm] (or (local nm) (imported nm) nm))))

;; ---------------------------------------------------------------- registry

(defn registry
  "Build a lookup of every declaration across several parsed models, so that
  inheritance chains crossing namespaces (TemplateModel -> Clause) resolve."
  [models]
  (reduce (fn [acc model]
            (let [resolve-type (type-resolver model)]
              (reduce (fn [acc {:keys [fqn declaration]}]
                        (assoc acc fqn {:declaration  declaration
                                        :namespace    (get model "namespace")
                                        :resolve-type resolve-type}))
                      acc
                      (declarations model))))
          {}
          models))

(defn super-chain
  "FQN -> [fqn parent grandparent ...]. Concerto flattens a subclass into one
  tagged object, so validation needs every ancestor's properties."
  [reg fqn]
  (loop [fqn fqn, seen [], guard 0]
    (let [{:keys [declaration resolve-type]} (get reg fqn)]
      (cond
        (nil? declaration) seen
        (< 20 guard)       (throw (ex-info "Cyclic supertype chain" {:fqn fqn :seen seen}))
        :else
        (let [seen' (conj seen fqn)]
          (if-let [super (get-in declaration ["superType" "name"])]
            (recur (resolve-type super) seen' (inc guard))
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

(defn- property->malli [reg resolve-type prop]
  (let [base (or (scalar (get prop "$class"))
                 (when (= "concerto.metamodel@1.0.0.ObjectProperty" (get prop "$class"))
                   (let [fqn (resolve-type (get-in prop ["type" "name"]))]
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
                :let  [{:keys [declaration resolve-type]} (get reg f)]
                prop  (get declaration "properties")
                :let  [k (concerto/field->xt (get prop "name"))]]
            (if (get prop "isOptional")
              [k {:optional true} (property->malli reg resolve-type prop)]
              [k (property->malli reg resolve-type prop)]))]
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
