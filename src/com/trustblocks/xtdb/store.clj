(ns com.trustblocks.xtdb.store
  "Guarded ingestion into XTDB.

  XTDB is the source of truth, and it enforces nothing -- no required fields,
  ranges, or enum membership. Anything writing over the Postgres wire bypasses
  every Concerto constraint. So all writes funnel through `put-instance!`,
  which resolves the Malli schema from $class and validates before insert.

  Schema compilation lives in `concerto-clj`; this namespace supplies the XTDB
  key transform and the storage. Takes an XTDB node explicitly, so it works
  against a bare node in the REPL or against Biff's (:biff.xtdb/node ctx)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [com.trustblocks.concerto.cto :as cto]
            [com.trustblocks.concerto.malli :as cm]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.xtdb.keys :as k]
            [malli.core :as m]
            [malli.error :as me]
            [xtdb.api :as xt]))

(def model-table       :concerto-model)
(def declaration-table :concerto-declaration)
(def contract-table    :contract)

;; ---------------------------------------------------------------- schema

(defn put-model!
  "Store a CTO model as data: the parsed metamodel plus the original source, and
  one record per declaration keyed by fully qualified name. Schemas then get
  XTDB's bitemporal history for free, exactly like the contracts they describe."
  [node cto-path & {:keys [concerto-cli]}]
  (let [raw    (slurp cto-path)
        parsed (cto/parse cto-path :cli concerto-cli)
        ns-    (:namespace parsed)
        decls  (mm/declarations parsed)]
    (xt/execute-tx
     node
     (into [[:put-docs model-table
             {:xt/id             ns-
              :concerto/source   raw
              :concerto/path     (str cto-path)
              ;; The parsed model is stored as EDN with Concerto's own key
              ;; spelling, so it round-trips without the model to interpret it.
              :concerto/model    (pr-str parsed)
              :declaration-count (count decls)}]]
           (for [{:keys [fqn name declaration]} decls]
             [:put-docs declaration-table
              {:xt/id                fqn
               :concerto/namespace   ns-
               :concerto/name        name
               :concerto/kind        (:$class declaration)
               :concerto/declaration (pr-str declaration)}])))
    {:namespace ns- :declarations (mapv :fqn decls)}))

(defn load-registry
  "Build a declaration registry from CTO files on disk. The model directory of
  an Accord template vendors its imports alongside model.cto, so pointing at the
  directory resolves inheritance chains that cross namespaces."
  [cto-paths & {:keys [concerto-cli]}]
  (mm/registry (map #(cto/parse % :cli concerto-cli) cto-paths)))

(defn registry-from-xtdb
  "Rebuild the declaration registry from XTDB itself, with no filesystem access.

  `put-model!` stores each parsed model whole, so reading it back gives an
  identical registry. This is what makes XTDB the source of truth in practice
  rather than just in principle: the schema needed to interpret a record lives
  beside the record.

  Building this scans every stored model, so callers doing many reads should
  build it once and pass it as :reg."
  [node]
  (->> (xt/q node '(from :concerto-model [*]))
       (keep :concerto/model)
       (map read-string)
       mm/registry))

(defn model-source
  "The original .cto text for a namespace, as put-model! stored it verbatim --
  for showing a $class's declaration in native Concerto syntax rather than
  our own derived rendering of it."
  [node ns]
  ;; Projected columns are symbols, not keywords -- see get-instance's
  ;; (symbol "*") for the same reason: XTQL silently ignores a keyword here
  ;; rather than projecting it, so the query still "succeeds" with an empty row.
  (:concerto/source
   (first (xt/q node (list 'from model-table [{:xt/id ns} (symbol "concerto/source")])))))

(defn model-dir-files
  "Every .cto in a directory (a template's model/ folder)."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.endsWith (.getName ^java.io.File %) ".cto"))
       (sort-by #(.getName ^java.io.File %))
       vec))

;; ---------------------------------------------------------------- instances

(defn schema
  "The Malli schema for a $class, keyed the way XTDB stores it."
  [reg fqn]
  (cm/->schema reg fqn :key-fn k/field->xt))

(defn validate
  "Validate a Concerto instance against its $class. Returns nil when valid,
  otherwise a humanized explanation.

  Validates the converted record *before* :xt/id and any derived keys are added,
  which is what lets the compiled schema stay closed."
  [reg instance]
  (let [fqn (get instance "$class")]
    (when-not fqn
      (throw (ex-info "Concerto instance has no $class" {:instance instance})))
    (let [s   (schema reg fqn)
          doc (k/->xtdb instance)]
      (when-not (m/validate s doc)
        (-> s (m/explain doc) me/humanize)))))

(defn put-instance!
  "Validate a Concerto instance against its $class, then write it to XTDB.
  Refuses to write anything that does not validate.

  A nil `reg` resolves the schema from XTDB, so a caller that has already run
  `put-model!` need not keep a registry around."
  [node reg instance & {:keys [table] :or {table contract-table}}]
  (let [reg (or reg (registry-from-xtdb node))
        fqn (get instance "$class")]
    (when-let [errs (validate reg instance)]
      (throw (ex-info "Concerto instance failed validation"
                      {:$class fqn :errors errs})))
    (let [doc (k/instance->doc instance :identity-field (mm/identity-field reg fqn))]
      (xt/execute-tx node [[:put-docs table doc]])
      {:xt/id (:xt/id doc) :$class fqn})))

(defn get-instance
  "Read an instance back out as Concerto JSON, restoring $class and the original
  camelCase property names.

  `:at`, an Instant, reads the instance as it stood at that point in valid
  time rather than as it stands now."
  [node id & {:keys [table reg at] :or {table contract-table}}]
  ;; The id must be matched as a literal in the binding pattern. Writing
  ;; {:xt/id $id} with an :args param does NOT filter -- XTDB treats $id as
  ;; another column to project and returns the entire table.
  (let [bind [{:xt/id id} (symbol "*")]
        q    (list 'from table (if at
                                 {:for-valid-time (list 'at at) :bind bind}
                                 bind))]
    (when-let [record (first (xt/q node q))]
      ;; Falls back to the stored schema rather than to ->camelCase: inferring
      ;; the name is lossy for anything not plain camelCase (vechileID,
      ;; INITIAL_DUE), so a faithful read must not be opt-in.
      (let [reg (or reg (registry-from-xtdb node))]
        (k/<-xtdb record (memoize #(k/property-names reg %)))))))

(defn instance->json [instance] (json/generate-string instance {:pretty true}))

(defn list-instances
  "Every stored instance's :xt/id and $class -- enough for a listing page
  without paying for a full get-instance (name restoration, nested
  recursion) per row. No ordering is requested; XTDB doesn't preserve
  insertion order, so this is fine for the handful of contracts a listing
  page shows today and worth revisiting (a valid-time or $class filter
  pushed into the query) once that stops being true."
  [node & {:keys [table] :or {table contract-table}}]
  (->> (xt/q node (list 'from table [(symbol "*")]))
       (mapv (fn [record] {:id (:xt/id record) :class (:concerto/class record)}))))
