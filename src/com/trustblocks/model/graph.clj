(ns com.trustblocks.model.graph
  "Field-level biff.graph attributes generated directly from the Concerto
  model, rather than hand-written per contract type.

  com.trustblocks.model.contract exposes contracts coarse-grained: one
  :contract/data blob per contract, opaque past that point, because
  biff.graph itself has no idea what properties a $class carries. This
  namespace closes that gap by walking the *model* -- concerto-clj's
  registry, the same one com.trustblocks.xtdb.store already resolves schemas
  from -- and generating one keyword per declared property, e.g.
  :snow-removal/contractor, so a page can query individual fields the way
  biff.xtdb/make-resolvers would for a fixed SQL schema. Except the field
  list here is discovered from concerto-clj, not hand-declared: a new
  template needs no new resolver code, only a model to ingest.

  Nested concepts (Money, etc.) need no join resolver of their own: they
  have no identity to look up by, so their properties are inlined --
  recursively, using the same generated keywords a top-level resolver for
  that class would use -- into the parent's single document read.
  biff.graph's own join descent matches an already-present key on the value
  before trying any resolver for it, which is what lets a query like
  {:snow-removal/rate-per-visit [:money/amount]} work with zero resolvers for
  Money: :money/amount is already sitting in the value this namespace
  produced.

  Enum-typed properties (Outcome) and --> relationships are scalars: an
  enum's wire value is a bare string tag, not a nested object, and a
  relationship is the referenced identifier, not the referenced document.
  Turning a relationship into an actual cross-entity join (a second read, by
  that id, through whichever resolver covers its target class) is future
  work -- none of our current templates has one to test against.

  Known limitation: the generated keyword namespace is a $class's own
  `name`, not its fully qualified name, so two Concerto namespaces that
  happen to declare identically-named concepts would collide. Fine for one
  template library; worth widening if that ever actually happens."
  (:require [camel-snake-kebab.core :as csk]
            [com.biffweb.graph :as biff.graph]
            [com.trustblocks.concerto.metamodel :as mm]))

;; ---------------------------------------------------------------- naming

(defn- declaration-kind [reg fqn]
  (some-> reg (get fqn) :declaration :$class mm/metamodel-type))

(defn- enum? [reg fqn]
  (= "EnumDeclaration" (declaration-kind reg fqn)))

(defn attr-ns
  "Keyword namespace for a $class: its own declared name, kebab-cased --
  \"SnowRemoval\" -> \"snow-removal\". Namespacing by class rather than by
  property means two classes can both declare `amount` with no collision:
  each gets its own keyword, :money/amount vs :invoice/amount."
  [reg fqn]
  (some-> reg (get fqn) :declaration :name csk/->kebab-case-string))

(defn- attr-kw [reg fqn prop-name]
  (keyword (attr-ns reg fqn) (csk/->kebab-case-string prop-name)))

;; ---------------------------------------------------------------- properties

(declare instance->attrs)

(defn- own-properties
  "This class's properties, flattened over its supertype chain, parent first,
  a child's redeclaration overriding its parent's at the parent's position.
  Mirrors concerto-clj's own flattening rule for compiling Malli schemas --
  tracked explicitly rather than with an array-map, which silently becomes an
  unordered hash-map once it passes eight entries."
  [reg fqn]
  (let [props (mapcat #(:properties (:declaration (get reg %)))
                      (reverse (mm/super-chain reg fqn)))]
    (:order
     (reduce (fn [{:keys [idx order] :as acc} prop]
               (let [k (:name prop)]
                 (if-let [i (idx k)]
                   (assoc-in acc [:order i] prop)
                   (-> acc
                       (assoc-in [:idx k] (count order))
                       (update :order conj prop)))))
             {:idx {} :order []}
             props))))

(defn- object-join?
  "Whether a property's value is a nested map (or array of them) rather than
  a scalar -- true for a concept/asset/etc.-typed ObjectProperty, false for
  an enum-typed one (a bare string tag)."
  [reg prop]
  (and (= "ObjectProperty" (mm/metamodel-type (:$class prop)))
       (not (enum? reg (mm/type-fqn (:type prop))))))

(defn- property-value
  "A property's raw Concerto value -> its graph-attribute value. Nested
  concepts recurse via the *value's own* $class, never the property's
  declared type -- a property typed as an abstract concept can legitimately
  hold any concrete subtype, and only the instance says which."
  [reg prop v]
  (case (mm/metamodel-type (:$class prop))
    "ObjectProperty"
    (if (enum? reg (mm/type-fqn (:type prop)))
      v
      (if (:isArray prop)
        (mapv #(instance->attrs reg %) v)
        (instance->attrs reg v)))

    ("StringProperty" "BooleanProperty" "IntegerProperty" "LongProperty"
     "DoubleProperty" "DateTimeProperty" "RelationshipProperty")
    v

    (throw (ex-info (str "Cannot generate a graph attribute for property kind "
                        (pr-str (:$class prop)) ". No permissive default -- "
                        "add it explicitly rather than silently dropping the "
                        "field.")
                    {:property prop}))))

(defn instance->attrs
  "Concerto JSON instance (string keys, as returned by
  com.trustblocks.xtdb.store/get-instance) -> a map keyed by the generated
  graph keyword for every property declared on its own or an ancestor's
  $class. A property absent from the instance (nil, or legitimately
  optional) is simply omitted, same as biff.graph treats any other missing
  attribute."
  [reg instance]
  (let [fqn (get instance "$class")]
    (when-not fqn
      (throw (ex-info "Concerto instance has no $class" {:instance instance})))
    (into {}
          (keep (fn [prop]
                  (when-some [v (get instance (:name prop))]
                    [(attr-kw reg fqn (:name prop)) (property-value reg prop v)])))
          (own-properties reg fqn))))

;; ---------------------------------------------------------------- resolvers

(defn- class-output
  "This class's properties as a biff.graph :output query: scalars as bare
  keywords, nested concepts as joins whose sub-query is *that* class's own
  properties, recursively -- so a caller can select individual nested fields
  ({:snow-removal/rate-per-visit [:money/amount]}) rather than only the whole
  wildcard blob.

  `seen` guards against a cyclic model (a concept nested inside itself,
  directly or through another concept) recursing forever; biff.graph queries
  are literal trees with no :ref indirection to break a cycle with the way
  Malli schemas can, so a cycle falls back to an opaque [:*] wildcard instead."
  ([reg fqn] (class-output reg fqn #{}))
  ([reg fqn seen]
   (if (contains? seen fqn)
     [:*]
     (mapv (fn [prop]
             (let [k (attr-kw reg fqn (:name prop))]
               (if (object-join? reg prop)
                 {k (class-output reg (mm/type-fqn (:type prop)) (conj seen fqn))}
                 k)))
           (own-properties reg fqn)))))

(defn resolver-for-class
  "A biff.graph resolver exposing every property `fqn` declares (including
  inherited ones) as its own generated keyword, given `fetch`, a function of
  (fn [node id] -> Concerto JSON instance or nil) such as
  com.trustblocks.xtdb.store/get-instance.

  Every generated resolver shares the same :input, [:contract/id] -- the
  resolver itself decides whether the fetched document is actually its
  class (comparing $class) before contributing anything, so asking for
  another class's fields on this id just resolves to nothing rather than to
  wrong data."
  [reg fqn fetch]
  (biff.graph/resolver
   {:id     (keyword "com.trustblocks.model.graph" (str (attr-ns reg fqn) "-fields"))
    :input  [:contract/id]
    :output (class-output reg fqn)
    :resolve-fn
    (fn [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
      (when-let [instance (fetch node id)]
        (when (= fqn (get instance "$class"))
          (instance->attrs reg instance))))}))

(defn resolvers-for-registry
  "One resolver per identity-bearing declaration in `reg` -- a declaration
  with no `identified by` (Money, and any other embedded-only concept) is
  never independently fetchable by id, so it is never given a resolver of
  its own. See instance->attrs for why it does not need one: its properties
  are inlined wherever it's nested."
  [reg fetch]
  (into []
        (keep (fn [fqn]
                (when (mm/identity-field reg fqn)
                  (resolver-for-class reg fqn fetch))))
        (keys reg)))
