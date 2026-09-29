(ns com.trustblocks.model.reads
  "What the matter page and the contract dashboard read, as one biff.graph
  graph: each page asks for everything it shows in one query, and the
  graph's per-query cache fetches each thing once -- a person, a matter's
  verified record, a document's history -- however many sections of the
  page need it. Before this, each section fetched for itself: the STR-27
  matter page made 682 XTDB queries, 165 identity lookups and 107 signature
  verifications to render six documents.

  The graph's entities, and what can be asked of each:

    {:identity/did}    :identity/label ('Teresa Barringer, Zoning
                       Administrator', or the bare did), :identity/name
    {:identity/email}  :identity/label
    {:matter/id}       :matter/name :matter/package :matter/terms (a
                       contract) :matter/documents (contracts, with their
                       membership) :matter/record (every credential naming
                       it, verified -- once) :matter/documents-as-of (as of
                       the page's day) :matter/receipts :matter/people
                       (everyone its record names) :matter/files (the stored
                       files it names, by hash) :matter/checklist
    {:contract/id}     :contract/instance :contract/history :contract/state
                       :contract/status-history :contract/lifecycle
                       :contract/declared-deadlines :contract/terms-of and
                       :contract/in-matter (matters) :contract/deadlines
                       :contract/all-deadlines (its own, and its documents'
                       when it is a matter's terms) :contract/notes
                       :contract/advisories :contract/attestations
                       :contract/attestation-people :contract/timeline

  Resolvers only read, delegating to the xtdb namespaces; what a page shows
  is worked out by the pure functions of com.trustblocks.model.timeline,
  .deadlines and .manifest over what they resolve. `query` runs a query
  with the registry, the time now, and the page's as-of day in the ctx:
  :contract/state and :matter/documents are read as of :trustblocks/as-of,
  deadlines are placed against :trustblocks/now.

  A credential record is string-keyed Concerto JSON, and a wildcard [:*] and
  a join cannot share one selection, so records stay opaque and the people
  they name are their own attribute (:matter/people,
  :contract/attestation-people), resolved to labels beside them. Timeline
  items name people as refs under :who, :subject and :by, joined like any
  other entity."
  (:require [com.biffweb.graph :as biff.graph :refer [defresolver]]
            [com.trustblocks.model.deadlines :as deadlines]
            [com.trustblocks.model.manifest :as manifest]
            [com.trustblocks.model.timeline :as timeline]
            [com.trustblocks.xtdb.advisory :as advisory]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.note :as note]
            [com.trustblocks.xtdb.receipt :as receipt]
            [com.trustblocks.xtdb.store :as store]))

;; ---------------------------------------------------------------- people

(defresolver person-by-did
  {:input  [:identity/did]
   :output [:identity/label [:? :identity/name]]}
  [{:keys [biff.xtdb/node]} {:identity/keys [did]}]
  (if-let [o (identity/by-did node did)]
    {:identity/label (str (:identity/name o) ", " (:identity/office o))
     :identity/name  (:identity/name o)}
    {:identity/label did}))

(defresolver person-by-email
  {:input  [:identity/email]
   :output [:identity/label]}
  [{:keys [biff.xtdb/node]} {:identity/keys [email]}]
  {:identity/label (if-let [o (identity/by-email node email)]
                     (str (:identity/name o) ", " (:identity/office o))
                     email)})

;; ---------------------------------------------------------------- matters

(defresolver all-matters
  {:input  []
   :output [{:app/matters [:*]}]}
  [{:keys [biff.xtdb/node]} _]
  {:app/matters (matter/all node)})

(defresolver matter-core
  {:input  [:matter/id]
   :output [[:? :matter/name] [:? :matter/package] {[:? :matter/terms] [:contract/id]}]}
  [{:keys [biff.xtdb/node]} {:matter/keys [id]}]
  (when-let [m (matter/get-matter node id)]
    (cond-> {:matter/terms {:contract/id (:matter/terms m)}}
      (:matter/name m)    (assoc :matter/name (:matter/name m))
      (:matter/package m) (assoc :matter/package (:matter/package m)))))

(defn- memberships [node matter-id at]
  (vec (for [{:keys [xt/id role joined stored-document]} (matter/documents node matter-id :at at)]
         (cond-> {:contract/id id :membership/role role :membership/joined joined}
           stored-document (assoc :membership/file stored-document)))))

(def membership [:contract/id :membership/role :membership/joined [:? :membership/file]])

(defresolver matter-documents
  {:input  [:matter/id]
   :output [{:matter/documents membership} {:matter/documents-as-of membership}]}
  [{:keys [biff.xtdb/node trustblocks/as-of]} {:matter/keys [id]}]
  ;; Its documents now -- a timeline shows them all -- and as of the page's
  ;; day, for what was on record then.
  {:matter/documents       (memberships node id nil)
   :matter/documents-as-of (memberships node id as-of)})

(defresolver matter-record
  {:input  [:matter/id]
   :output [{:matter/record [:*]} {:matter/people [:identity/did]}]}
  [{:keys [biff.xtdb/node]} {:matter/keys [id]}]
  ;; By the time of the act; acts at the same instant, in the order they
  ;; were signed.
  (let [record (vec (sort-by (juxt #(get % "valid-from") #(str (get % "certifiedAt")))
                             (cert/matter-record node id)))]
    {:matter/record record
     :matter/people (->> record
                         (mapcat (juxt #(get % "issuer") #(get % "subject")
                                       #(get-in % ["evidence" "issuer"])))
                         (remove nil?)
                         distinct
                         (mapv #(hash-map :identity/did %)))}))

(defresolver matter-receipts
  {:input  [{:matter/record [:*]}]
   :output [{:matter/receipts [:*]}]}
  [_ {:matter/keys [record]}]
  ;; The record has already verified them: a receipt is a record entry of
  ;; its class, received when it is valid from.
  {:matter/receipts (vec (for [r record :when (= receipt/receipt-class (get r "$class"))]
                           (assoc r "received-at" (get r "valid-from"))))})

(defresolver matter-files
  {:input  [{:matter/documents-as-of [[:? :membership/file]]} {:matter/record [:*]}]
   :output [{:matter/files [:*]}]}
  [{:keys [biff.xtdb/node]} {:matter/keys [documents-as-of record]}]
  {:matter/files
   (into {} (for [hash (distinct (concat (keep :membership/file documents-as-of)
                                         (keep #(get % "documentHash") record)))
                  :let [{:document/keys [filename]} (document/get-document node hash)]
                  :when filename]
              [hash filename]))})

(defresolver matter-checklist
  {:input  [:matter/id [:? :matter/package] {[:? :matter/terms] [:contract/id]}
            {:matter/documents-as-of [:contract/id :membership/role :membership/joined
                                      [:? :membership/file] {[:? :contract/state] [:*]}]}
            {:matter/receipts [:*]}]
   :output [{[:? :matter/checklist] [:*]}]}
  [{:keys [trustblocks/as-of]} {:matter/keys [package terms documents-as-of receipts]}]
  (when-let [m (manifest/for-package package)]
    {:matter/checklist
     (manifest/checklist
      m
      {:terms     (:contract/id terms)
       :documents (for [d documents-as-of]
                    {:xt/id           (:contract/id d)
                     :role            (:membership/role d)
                     :joined          (:membership/joined d)
                     :stored-document (:membership/file d)
                     :status          (get-in d [:contract/state "status"])})
       :receipts  receipts}
      :at as-of)}))

;; ---------------------------------------------------------------- contracts

(defresolver contract-instance
  {:input  [:contract/id]
   :output [{[:? :contract/instance] [:*]}]}
  [{:keys [biff.xtdb/node trustblocks/registry]} {:contract/keys [id]}]
  (when-let [i (store/get-instance node id :reg registry)]
    {:contract/instance i}))

(defresolver contract-history
  {:input  [:contract/id]
   :output [{:contract/history [:*]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
  {:contract/history
   (mapv (fn [{:keys [by certification] :as e}]
           (cond-> e
             certification (assoc :who {:identity/did (:did certification)})
             (and by (not certification)) (assoc :who {:identity/email by})))
         (execute/history node id))})

(defresolver contract-state
  {:input  [:contract/id]
   :output [{[:? :contract/state] [:*]}]}
  [{:keys [biff.xtdb/node trustblocks/registry trustblocks/as-of]} {:contract/keys [id]}]
  (when-let [s (execute/current-state node id :reg registry :at as-of)]
    {:contract/state s}))

(defresolver contract-status-history
  {:input  [:contract/id]
   :output [{:contract/status-history [:*]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
  {:contract/status-history (vec (execute/status-history node id))})

(defresolver contract-template-declarations
  {:input  [{:contract/instance [:*]}]
   :output [{[:? :contract/lifecycle] [:*]} {[:? :contract/declared-deadlines] [:*]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [instance]}]
  (let [fqn (get instance "$class")]
    (cond-> {}
      (execute/get-lifecycle node fqn) (assoc :contract/lifecycle (execute/get-lifecycle node fqn))
      (execute/get-deadlines node fqn) (assoc :contract/declared-deadlines (execute/get-deadlines node fqn)))))

(defresolver contract-matters
  {:input  [:contract/id {:app/matters [:*]}]
   :output [{[:? :contract/terms-of] [:matter/id]} {[:? :contract/in-matter] [:matter/id]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id] :app/keys [matters]}]
  (let [terms-of  (:xt/id (first (filter #(= id (:matter/terms %)) matters)))
        in-matter (matter/matter-of node id)]
    (cond-> {}
      terms-of  (assoc :contract/terms-of {:matter/id terms-of})
      in-matter (assoc :contract/in-matter {:matter/id in-matter}))))

(defresolver contract-deadlines
  {:input  [:contract/id {:contract/instance [:*]} {:contract/status-history [:*]}
            {[:? :contract/declared-deadlines] [:*]}
            {[:? :contract/terms-of] [:matter/id]}
            {[:? :contract/in-matter] [{:matter/terms [{:contract/instance [:*]}]}]}]
   :output [{:contract/deadlines [:*]}]}
  [{:keys [trustblocks/now]} {:contract/keys [id instance status-history declared-deadlines
                                              terms-of in-matter]}]
  {:contract/deadlines
   (deadlines/compute {:document       id
                       :instance       instance
                       :declared       declared-deadlines
                       ;; Its matter's terms -- itself, when it is them.
                       :terms          (or (get-in in-matter [:matter/terms :contract/instance])
                                           (when terms-of instance))
                       :status-history status-history
                       :now            now})})

(defresolver contract-all-deadlines
  {:input  [{:contract/deadlines [:*]}
            {[:? :contract/terms-of] [{:matter/documents [{:contract/deadlines [:*]}]}]}]
   :output [{:contract/all-deadlines [:*]}]}
  [_ {:contract/keys [deadlines terms-of]}]
  {:contract/all-deadlines
   (into (vec deadlines) (mapcat :contract/deadlines (:matter/documents terms-of)))})

(defresolver contract-notes
  {:input  [:contract/id]
   :output [{:contract/notes [:*]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
  {:contract/notes (note/for-contract node id)})

(defresolver contract-advisories
  {:input  [:contract/id
            {[:? :contract/terms-of] [{:matter/record [:*]}]}
            {[:? :contract/in-matter] [{:matter/record [:*]}]}]
   :output [{:contract/advisories [:*]}]}
  [_ {:contract/keys [id terms-of in-matter]}]
  ;; A matter's terms show every document's advice; a document, its own.
  {:contract/advisories
   (vec (for [a (:matter/record (or terms-of in-matter))
              :when (= advisory/advisory-class (get a "$class"))
              :when (or terms-of (= id (get a "contractId")))]
          a))})

(defresolver contract-attestations
  {:input  [:contract/id]
   :output [{:contract/attestations [:*]} {:contract/attestation-people [:identity/did]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
  (let [as (vec (cert/attestations-for-contract node id))]
    {:contract/attestations        as
     :contract/attestation-people (mapv #(hash-map :identity/did %)
                                        (distinct (keep #(get % "issuer") as)))}))

(def person-ref [[:? :identity/did] [:? :identity/email]])

(defresolver contract-timeline
  {:input  [:contract/id {:contract/instance [:*]} {:contract/history [:*]}
            {:contract/all-deadlines [:*]} {:contract/notes [:*]} {:contract/advisories [:*]}
            {[:? :contract/terms-of] [{:matter/documents [:contract/id {:contract/history [:*]}]}
                                      {:matter/record [:*]} {:matter/receipts [:*]}]}]
   :output [{:contract/timeline [:at :kind :title [:? :document] [:? :status] [:? :detail]
                                 [:? :recorded]
                                 {[:? :who] person-ref}
                                 {[:? :subject] person-ref}
                                 {[:? :by] person-ref}]}]}
  [{:keys [trustblocks/registry]} {:contract/keys [id instance history all-deadlines notes
                                                   advisories terms-of]}]
  {:contract/timeline
   (timeline/assemble
    {:id         id
     :reg        registry
     :instance   instance
     :history    history
     :documents  (for [d (:matter/documents terms-of)]
                   [(:contract/id d) (:contract/history d)])
     :record     (:matter/record terms-of)
     :receipts   (:matter/receipts terms-of)
     :deadlines  all-deadlines
     :notes      notes
     :advisories advisories})})

;; ---------------------------------------------------------------- querying

(def resolvers
  [person-by-did person-by-email all-matters
   matter-core matter-documents matter-record matter-receipts matter-files matter-checklist
   contract-instance contract-history contract-state contract-status-history
   contract-template-declarations contract-matters contract-deadlines contract-all-deadlines
   contract-notes contract-advisories contract-attestations contract-timeline])

(def module
  {:biff.graph/resolvers resolvers})

(def ^:private graph-ctx (delay (biff.graph/new-ctx resolvers)))

(defn query
  "Runs `q` from `input` against the XTDB node in `ctx`, with the model
  registry read once for the whole query (or the `registry` given), `now` (default: the current time)
  for placing deadlines, and `as-of` (default: now) for states and matter
  membership."
  [{:keys [biff.xtdb/node]} input q & {:keys [now as-of registry]}]
  (let [now (or now (java.time.Instant/now))]
    (biff.graph/query (assoc @graph-ctx
                             :biff.xtdb/node node
                             :trustblocks/registry (or registry (store/registry-from-xtdb node))
                             :trustblocks/now now
                             :trustblocks/as-of (or as-of now))
                      input q)))

(def timeline-item
  "The selection a page asks for each timeline item: its fields, and the
  people it names resolved to labels -- then timeline/finish writes them in."
  (let [person [:identity/label [:? :identity/name]]]
    [:at :kind :title [:? :document] [:? :status] [:? :detail] [:? :recorded]
     {[:? :who] person} {[:? :subject] person} {[:? :by] person}]))

(defn timeline-items
  "Contract `id`'s timeline, finished -- people named -- oldest first."
  [ctx id & {:keys [now]}]
  (mapv timeline/finish
        (:contract/timeline (query ctx {:contract/id id} [{:contract/timeline timeline-item}]
                                   :now now))))

(defn checklist
  "Matter `id` held up against its package's manifest, as of `at` (default:
  now) -- manifest/checklist over what the graph reads, or nil when its
  package has no manifest."
  [ctx id & {:keys [at]}]
  (:matter/checklist (query ctx {:matter/id id} [{[:? :matter/checklist] [:*]}] :as-of at)))
