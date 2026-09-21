(ns com.trustblocks.model.playground
  "Links out to Accord Project's own playgrounds -- concerto-playground.
  accordproject.org for the model, playground.accordproject.org for the
  template -- so a contract's own model and template text can be opened,
  read and experimented with in the tools Accord already built for exactly
  that, instead of us building a second Concerto editor or a second
  TemplateMark renderer. Both are hosted by Accord Project today; see the
  module docstring below for why that may not stay true.

  Both playgrounds put their state in the URL: a JSON payload, LZ-String
  compressed (com.github.tommyettinger/blazingchain -- confirmed
  byte-compatible with the lz-string 1.5.0 both playgrounds actually ship,
  checked in both directions), then dropped straight into the URL fragment
  with no further escaping, since LZ-String's URI-safe mode only emits
  characters already legal there. No server, no account, no expiry -- the
  link is the state.

  The two playgrounds do not accept the same shape of state, and the
  difference is load-bearing:

  - Concerto Playground takes a JSON array of whole .cto sources, one per
    namespace, and resolves the imports between them itself. Any
    contract's full model -- however many namespaces its inheritance and
    property types touch -- fits in one link. concerto-url below always
    returns one.

  - Template Playground takes exactly one .cto string, no more. Checked
    directly against its own rebuild(), on its newest commit (pulled
    fresh, not a stale clone, while writing this): it calls
    modelManager.addCTOModel(model, undefined, true) exactly once, and a
    model text can declare only one namespace -- confirmed by an actual
    Concerto parse error when every one of our namespaces was concatenated
    into one string. So a contract whose model needs more than one
    namespace beyond what the playground bundles (see
    template-playground-bundled-namespaces) cannot be opened there at all
    -- not degraded, just wrong: feeding it only the leaf namespace throws
    'Namespace is not defined for type ...' before anything renders.
    template-status below returns an :unavailable reason naming exactly
    how many namespaces are over the limit, rather than a link that is
    certain to fail.

    com.trustblocks.municipal.employment@1.0.0.ManagerEmployment is a real
    case of this, not a hypothetical: it needs org.accordproject.usa.state
    (an Accord Project namespace, just not one of the ones the playground
    happens to bundle) and com.trustblocks.municipal.agreement (our own
    shared jurisdiction base, reused by other municipal contracts on
    purpose -- see agreement.cto's own docstring) on top of its own
    namespace, three past the limit of one. Deliberately not worked
    around by reshaping either model to fit -- flattening the shared base
    back into every contract that uses it, or swapping the imported enum
    for a bare string, would be a real, permanent loss of the reuse and
    validation those choices exist for, made solely to satisfy an
    external tool's link-size limit. Left as documented, known behaviour
    instead: Concerto Playground already gives a complete, correct view
    of such a model with no such limit, and Accord Project is actively
    developing Template Playground -- worth checking again periodically
    for either broader bundling or genuine multi-file support before
    concluding the only fix is self-hosting one, which remains the real
    fix if neither materializes."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.xtdb.store :as store])
  (:import [blazing.chain LZSEncoding]))

;; ---------------------------------------------------------------- closure

(defn- object-property-target
  "The FQN an ObjectProperty points at, or nil for any other property kind.

  A RelationshipProperty's target is deliberately excluded. Concerto's own
  model validation does not resolve it eagerly -- our vendored
  org.accordproject.runtime@0.2.0.cto declares relationships to
  Participant, a type nothing in this codebase defines, and the model
  still validates -- and concerto-clj treats a relationship's value as the
  bare referenced id rather than the referenced document (see
  com.trustblocks.model.graph). A class's own fields is the same boundary
  this library already draws elsewhere for what a relationship's target
  owes the class holding it."
  [prop]
  (when (= "ObjectProperty" (mm/metamodel-type (:$class prop)))
    (mm/type-fqn (:type prop))))

(defn namespaces
  "Every namespace whose source a standalone tool -- one with no network
  access and no knowledge of any registry but what it's handed -- needs in
  order to fully load and validate `fqn` on its own.

  That's `fqn`'s supertype chain (Concerto flattens a subclass into one
  tagged object, so every ancestor's properties are part of the instance),
  plus every type any of those classes references through an
  ObjectProperty -- a nested concept like Money, or an enum like USState --
  followed transitively, since a referenced concept can itself reference
  further types. This is what makes a self-contained multi-file playground
  link possible at all: it's the complete answer to 'what would I have to
  hand someone for this to type-check with nothing else'."
  [reg fqn]
  (loop [queue [fqn], seen #{}]
    (if-let [next-fqn (first queue)]
      (if (seen next-fqn)
        (recur (rest queue) seen)
        (let [chain   (mm/super-chain reg next-fqn)
              props   (mapcat #(:properties (:declaration (get reg %))) chain)
              targets (into #{} (keep object-property-target) props)
              seen'   (into seen chain)]
          (recur (into (rest queue) (remove seen' targets)) seen')))
      (into (sorted-set) (keep #(:namespace (get reg %))) seen))))

;; ---------------------------------------------------------------- linking

(defn- share-link [origin payload]
  (str origin "/#" (LZSEncoding/compressToEncodedURIComponent (json/generate-string payload))))

(defn concerto-url
  "A concerto-playground.accordproject.org link that opens with `fqn`'s
  whole model loaded -- every namespace `namespaces` above finds, each as
  its own array element, so the playground resolves the imports between
  them exactly as it would loading them from disk."
  [node reg fqn]
  (share-link "https://concerto-playground.accordproject.org"
              (into [] (keep #(store/model-source node %)) (namespaces reg fqn))))

(def template-playground-bundled-namespaces
  "Namespaces template-playground preloads into every session (see its
  src/utils/modelCache.ts) so a template importing them resolves without a
  network round trip. A model needing only these plus one namespace of its
  own fits in a template-status link; anything more does not, because the
  playground's own share format holds exactly one model string besides
  these. Checked against its current main -- not a stale clone -- while
  writing this."
  #{"org.accordproject.contract@0.2.0" "org.accordproject.runtime@0.2.0"
    "org.accordproject.time@0.3.0" "org.accordproject.money@0.3.0"
    "org.accordproject.obligation@0.2.0" "org.accordproject.binary@0.2.0"
    "org.accordproject.signature@0.3.0" "com.docusign.connect@0.4.0"})

(defn template-status
  "Whether instance's model and template can be opened in
  playground.accordproject.org, and either the link or why not.

  Returns {:url \"...\"} or {:unavailable \"a reason to show someone\"}, never
  both and never neither -- one caller (document-page) branches on this once
  rather than every call site re-deriving grammar/custom for itself.

  Two things have to hold. There has to be a template on file for this
  $class at all (com.trustblocks.template.archive/by-namespace, the same
  lookup document-page uses) -- nothing to show otherwise. And the model's
  namespace closure, once template-playground's own bundled namespaces are
  set aside, has to be exactly one namespace -- see the module docstring
  for why more than one cannot be sent at all, not degraded."
  [node reg instance]
  (let [fqn     (get instance "$class")
        ns-     (:namespace (get reg fqn))
        grammar (:archive/grammar (archive/by-namespace node ns-))
        custom  (remove template-playground-bundled-namespaces (namespaces reg fqn))]
    (cond
      (not grammar)
      {:unavailable "No contract template text on file for this model."}

      (not= 1 (count custom))
      {:unavailable
       (str "This model needs " (count custom) " namespaces beyond what "
            "Template Playground bundles (" (str/join ", " (sort custom)) "), "
            "and its share link holds only one.")}

      :else
      {:url (share-link "https://playground.accordproject.org"
                        {:templateMarkdown grammar
                         :modelCto         (store/model-source node (first custom))
                         :data             (json/generate-string instance)
                         :agreementHtml    ""})})))
