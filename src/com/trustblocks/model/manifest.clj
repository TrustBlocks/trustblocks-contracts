(ns com.trustblocks.model.manifest
  "A package's manifest, and a matter held up against it.

  The manifest (trustblocks-templates' packages/<package>/manifest.edn,
  typed by com.trustblocks.manifest@1.0.0.Manifest -- see that repo's
  docs/manifest.md) says what a matter made from the package consists of:
  its phases, each kind of document with its treatment, who supplies it,
  how many and who receives it, the milestones, the law it rests on, and
  guides to where to look. It is read from the classpath, from the
  templates commit deps.edn pins.

  The checklist puts what is on record beside what the manifest lists, by
  role:

    TERMS     the matter's terms instance
    TEMPLATE  the matter's documents that joined with the role
              (com.trustblocks.xtdb.matter/add-document!)
    STORED    the matter's receipts naming the role -- a stored file with no
              instance of its own (com.trustblocks.xtdb.receipt), received on
              its own or with another document (an engineer's certificate
              with a pay request)

  Like deadlines, the checklist is shown, never acted on: a required
  document not on record is something to see, not a refusal or a notice."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.receipt :as receipt]))

(defn source
  "The text of `package`'s manifest.edn, or nil when it has none."
  [package]
  (some-> package (str "/manifest.edn") io/resource slurp))

(defn for-package
  "`package`'s manifest as EDN data, or nil."
  [package]
  (some-> (source package) edn/read-string))

;; ------------------------------------------------------------------ JSON

(def ^:private ns-prefix "com.trustblocks.manifest@1.0.0.")

(def ^:private nested
  "Which concept each array holds, by the property it sits under -- as
  trustblocks-templates' bb/manifest.clj, which `bb check` holds to the
  model."
  {:phases :Phase :documents :DocumentRole :fields :StoredField
   :milestones :Milestone :references :LegalReference :intake :IntakeSection
   :guides :Guide})

(defn- camel [k]
  (let [[head & tail] (str/split (name k) #"-")]
    (apply str head (map str/capitalize tail))))

(defn ->concerto
  "The manifest in the model's own terms: camelCase keys and a $class on
  every object -- the manifest.json `bb build` writes."
  ([m] (->concerto :Manifest m))
  ([concept m]
   (into (array-map "$class" (str ns-prefix (name concept)))
         (for [[k v] m]
           [(camel k)
            (if (and (sequential? v) (nested k) (every? map? v))
              (mapv #(->concerto (nested k) %) v)
              v)]))))

;; ------------------------------------------------------------------ checklist

(defn- on-record
  "What matter `matter-id` has on record for each role, as of `at`:
  {role [{:kind :instance|:receipt :id :at :file :status}]}."
  [node {:keys [xt/id] :matter/keys [terms]} terms-role at]
  (let [docs     (matter/documents node id :at at)
        members  (for [{:keys [xt/id role joined stored-document]} docs]
                   [role {:kind :instance :id id :at joined :file stored-document
                          :status (get (execute/current-state node id :at at) "status")}])
        ;; A receipt of a document's own file is that document, already
        ;; listed; any other names a stored document of its own -- an
        ;; engineer's certificate received with a pay request.
        own-files (set (keep :stored-document docs))
        receipts (for [r (receipt/receipts-for-matter node id)
                       :when (and (get r "documentRole")
                                  (not (own-files (get r "documentHash")))
                                  (not (.isAfter ^java.time.Instant (get r "received-at") at)))]
                   [(get r "documentRole")
                    {:kind :receipt :id (get r "documentHash") :at (get r "received-at")
                     :file (get r "documentHash") :from (get r "receivedFrom")
                     :description (get r "description") :issuer (get r "issuer")
                     :document (get r "contractId")}])]
    (reduce (fn [acc [role entry]] (update acc role (fnil conj []) entry))
            {terms-role [{:kind :instance :id terms :terms true}]}
            (concat members receipts))))

(defn checklist
  "Each document the manifest lists, with what matter `matter` has on
  record for it as of `at` (default: now), in the manifest's order:
  the manifest's DocumentRole map plus :on-record and :standing --

    :on-record      at least one on record
    :not-on-record  required, none on record, and its phase begun
    :not-yet        required, but its phase not yet begun
    :optional       not required, none on record

  A phase has begun when it, or a later phase, has anything on record.
  Also returns, under :unlisted, what is on record under a role the
  manifest does not list, so nothing on file goes unshown."
  [node manifest matter & {:keys [at]}]
  (let [at       (or at (java.time.Instant/now))
        docs     (:documents manifest)
        terms    (:role (first (filter #(= "TERMS" (:treatment %)) docs)))
        record   (on-record node matter terms at)
        order    (zipmap (map :name (:phases manifest)) (range))
        begun    (reduce max -1 (keep #(when (seq (record (:role %))) (order (:phase %))) docs))]
    {:documents
     (vec (for [{:keys [role phase required] :as d} docs
                :let [entries (record role)]]
            (assoc d :on-record (vec entries)
                   :standing (cond (seq entries)                   :on-record
                                   (not required)                  :optional
                                   (<= (order phase 0) begun)      :not-on-record
                                   :else                           :not-yet))))

     :unlisted
     (into (sorted-map) (apply dissoc record (map :role docs)))}))
