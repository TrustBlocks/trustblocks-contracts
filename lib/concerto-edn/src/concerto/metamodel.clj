(ns concerto.metamodel
  "The Concerto metamodel as EDN.

  Concerto's metamodel is self-hosting -- it is defined in Concerto itself
  (`concerto-metamodel/lib/metamodel.cto`, 277 lines) and every parsed CTO file
  is an instance of it, serialized as JSON tagged with `$class`. That JSON is
  the stable, versioned interchange format, so it is what this namespace takes
  as input. Nothing here does I/O and nothing here shells out; see
  `concerto.cto` for getting from CTO text to this point.

  Every key in the metamodel is a legal Clojure keyword name, so JSON -> EDN is
  lossless and needs no mapping table. Values -- `$class` strings, type names,
  namespaces -- stay strings, because they are data, not structure."
  (:require [clojure.walk :as walk]))

(def ^:private type-identifier "concerto.metamodel@1.0.0.TypeIdentifier")

;; ---------------------------------------------------------------- json -> edn

(defn json->edn
  "Parsed metamodel JSON (string keys) -> EDN (keyword keys).

  `$class` becomes `:$class`, `superType` becomes `:superType`. Case is
  preserved deliberately: kebab-casing here would be lossy and would have to be
  reversed through the model on the way out, which is a storage concern, not a
  metamodel one."
  [x]
  (walk/keywordize-keys x))

(defn strip-locations
  "Remove source Range/Position nodes. They are byte offsets into the .cto text
  rather than schema semantics, and they dominate the payload."
  [x]
  (walk/postwalk (fn [node] (if (map? node) (dissoc node :location) node)) x))

;; ---------------------------------------------------------------- qualifying

(defn namespace-index
  "Short type name -> namespace, from a model's own declarations plus its
  imports. A local declaration shadows an import of the same name."
  [model]
  (let [ns- (:namespace model)]
    (merge
     (into {}
           (for [i  (:imports model)
                 nm (case (:$class i)
                      "concerto.metamodel@1.0.0.ImportType"  [(:name i)]
                      "concerto.metamodel@1.0.0.ImportTypes" (:types i)
                      [])]
             [nm (:namespace i)]))
     (into {} (for [d (:declarations model)] [(:name d) ns-])))))

(defn qualify-types
  "Fill in the `namespace` of every TypeIdentifier in `x`, resolving short names
  through `ns-for`.

  The Concerto grammar writes type references unqualified -- `superType` and
  ObjectProperty carry a bare `name` -- so the namespace has to be recovered
  from the enclosing model's imports. Doing that eagerly, once, is what keeps
  the registry pure data; the alternative is to keep the model's import resolver
  as a closure beside each declaration, which makes the registry unserializable
  and so unstorable, undiffable and unexportable.

  TypeIdentifier already carries an optional `namespace` in the metamodel, so a
  qualified declaration is still a legal metamodel value: nothing is mangled and
  nothing is invented. Walking on `$class` rather than on known parent fields
  covers decorator type references and map key/value types without enumerating
  every site. A name that resolves to nothing is left bare, exactly as it
  arrived."
  [ns-for x]
  (cond
    (map? x)
    (let [m (update-vals x #(qualify-types ns-for %))]
      (if (and (= type-identifier (:$class m))
               (not (contains? m :namespace)))
        (if-let [ns- (ns-for (:name m))]
          (assoc m :namespace ns-)
          m)
        m))

    (vector? x) (mapv #(qualify-types ns-for %) x)
    :else       x))

(defn type-fqn
  "TypeIdentifier -> fully qualified name. Stays unqualified when the name never
  resolved, in which case no declaration matches it and the caller stops there."
  [node]
  (when-let [nm (:name node)]
    (if-let [ns- (:namespace node)]
      (str ns- "." nm)
      nm)))

;; ---------------------------------------------------------------- registry

(defn declarations
  "Model -> seq of {:fqn :namespace :name :declaration}."
  [model]
  (let [ns- (:namespace model)]
    (for [d (:declarations model)]
      {:fqn         (str ns- "." (:name d))
       :namespace   ns-
       :name        (:name d)
       :declaration d})))

(defn registry
  "Build a lookup of every declaration across several models, so that
  inheritance chains crossing namespaces resolve.

  Every declaration is fully qualified on the way in, so the result is pure
  data: it can be pr-str'd, stored, diffed and read back without carrying the
  import context needed to interpret it."
  [models]
  (reduce (fn [acc model]
            (let [ns-for (namespace-index model)]
              (reduce (fn [acc {:keys [fqn declaration]}]
                        (assoc acc fqn {:declaration (qualify-types ns-for declaration)
                                        :namespace   (:namespace model)}))
                      acc
                      (declarations model))))
          {}
          models))

(defn super-chain
  "FQN -> [fqn parent grandparent ...]. Concerto flattens a subclass into one
  tagged object, so anything validating an instance needs every ancestor's
  properties."
  [reg fqn]
  (loop [fqn fqn, seen [], guard 0]
    (let [{:keys [declaration]} (get reg fqn)]
      (cond
        (nil? declaration) seen
        (< 20 guard)       (throw (ex-info "Cyclic supertype chain" {:fqn fqn :seen seen}))
        :else
        (let [seen' (conj seen fqn)]
          (if-let [super (type-fqn (:superType declaration))]
            (recur super seen' (inc guard))
            seen'))))))

(defn identity-field
  "The `identified by` field for a declaration, inherited if not declared."
  [reg fqn]
  (some (fn [f] (get-in reg [f :declaration :identified :name]))
        (super-chain reg fqn)))
