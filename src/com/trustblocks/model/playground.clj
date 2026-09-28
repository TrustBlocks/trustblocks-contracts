(ns com.trustblocks.model.playground
  "Links out to the playgrounds -- Accord Project's
  concerto-playground.accordproject.org for the model, and Trustblocks' own
  Template Playground for the template, its logic and its lifecycle -- so a
  contract's model and template can be opened, read and experimented with
  in tools built for exactly that, instead of us building a second Concerto
  editor or a second TemplateMark renderer.

  The Template Playground is TrustBlocks' fork of Accord's
  (github.com/TrustBlocks/template-playground, hosted at
  trustblocks.github.io/template-playground): it runs a template's Clojure
  logic with our own clause runtime (trustblocks-templates' runtime/), so a
  request there gets the answer it gets here, and it bundles our shared
  models beside Accord's.

  Both playgrounds put their state in the URL: a JSON payload, LZ-String
  compressed (com.github.tommyettinger/blazingchain -- confirmed
  byte-compatible with the lz-string 1.5.0 both playgrounds actually ship,
  checked in both directions), then dropped straight into the URL fragment
  with no further escaping, since LZ-String's URI-safe mode only emits
  characters already legal there. No server, no account, no expiry -- the
  link is the state. Template Playground's current contract prefixes its
  payload with `#data=`; Concerto Playground still consumes the compressed
  array directly after `#`, so these are deliberately two link builders.

  The two playgrounds do not accept the same shape of state, and the
  difference is load-bearing:

  - Concerto Playground takes a JSON array of whole .cto sources, one per
    namespace, and resolves the imports between them itself. Any
    contract's full model -- however many namespaces its inheritance and
    property types touch -- fits in one link. concerto-url below always
    returns one.

  - Template Playground takes exactly one .cto string besides the models
    it bundles -- its share state has a singular `modelCto`, and a model
    text can declare only one namespace. Our fork bundles, beside Accord's
    standard models, the shared ones our templates import
    (com.trustblocks.municipal.agreement, com.trustblocks.attestation and
    org.accordproject.usa.state -- see template-playground-bundled-
    namespaces), so every template of ours fits: each has one namespace of
    its own. A model that still needs more than one namespace beyond the
    bundle cannot be opened there at all -- feeding it only the leaf
    namespace throws 'Namespace is not defined for type ...' -- and
    template-status says so rather than giving a link certain to fail.

  The link also carries the contract's clause (logicTs) and lifecycle
  (lifecycleJson), which the fork reads from its share state, so the
  playground opens with the contract ready to simulate.

  `TRUSTBLOCKS_TEMPLATE_PLAYGROUND_ORIGIN` points links at a local
  checkout (bin/playground, port 5173) instead; see
  docs/template-playground-webmcp.md."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.xtdb.execute :as execute]
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
  bare referenced id rather than the referenced document. A class's own fields is the same boundary
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

(defn- compressed-payload [payload]
  (LZSEncoding/compressToEncodedURIComponent (json/generate-string payload)))

(defn- share-link [origin payload]
  (str origin "/#" (compressed-payload payload)))

(defn template-playground-origin
  "Trustblocks' hosted Template Playground by default, or an explicitly
  configured compatible origin (principally a local checkout, bin/playground). A function
  rather than a def so a REPL can change the system property without
  reloading this namespace. A blank setting counts as unset: an exported but
  empty variable would otherwise yield relative links back into this app."
  []
  (-> (or (not-empty (str/trim (or (System/getProperty "trustblocks.template-playground-origin") "")))
          (not-empty (str/trim (or (System/getenv "TRUSTBLOCKS_TEMPLATE_PLAYGROUND_ORIGIN") "")))
          "https://trustblocks.github.io/template-playground")
      (str/replace #"/+$" "")))

(defn- template-share-link [payload]
  (str (template-playground-origin) "/#data=" (compressed-payload payload)))

(defn concerto-url
  "A concerto-playground.accordproject.org link that opens with `fqn`'s
  whole model loaded -- every namespace `namespaces` above finds, each as
  its own array element, so the playground resolves the imports between
  them exactly as it would loading them from disk."
  [node reg fqn]
  (share-link "https://concerto-playground.accordproject.org"
              (into [] (keep #(store/model-source node %)) (namespaces reg fqn))))

(def template-playground-bundled-namespaces
  "Namespaces the Template Playground preloads into every session (see its
  src/utils/modelCache.ts) so a template importing them resolves without a
  network round trip: Accord's standard models, and -- in our fork, from
  trustblocks-templates (its scripts/sync-trustblocks.mjs) -- the shared
  models our templates import. A model needing only these plus one
  namespace of its own fits in a template-status link; anything more does
  not, because the playground's share format holds exactly one model string
  besides these."
  #{"org.accordproject.contract@0.2.0" "org.accordproject.runtime@0.2.0"
    "org.accordproject.time@0.3.0" "org.accordproject.money@0.3.0"
    "org.accordproject.obligation@0.2.0" "org.accordproject.binary@0.2.0"
    "org.accordproject.signature@0.3.0" "com.docusign.connect@0.4.0"
    ;; Trustblocks' fork
    "com.trustblocks.municipal.agreement@1.0.0" "com.trustblocks.attestation@1.0.0"
    "org.accordproject.usa.state@0.3.0"})

(defn template-status
  "Whether instance's model and template can be opened in the Template
  Playground, and either the link or why not. The link carries the
  contract's clause and lifecycle too, when its template has them.

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
      {:url (template-share-link
             (cond-> {:templateMarkdown grammar
                      :modelCto         (store/model-source node (first custom))
                      :data             (json/generate-string instance)
                      :agreementHtml    ""}
               (= :clojure (:runtime (execute/get-clause node fqn)))
               (assoc :logicTs (:source (execute/get-clause node fqn)))

               (execute/get-lifecycle node fqn)
               (assoc :lifecycleJson (json/generate-string (execute/get-lifecycle node fqn)
                                                           {:pretty true}))))})))
