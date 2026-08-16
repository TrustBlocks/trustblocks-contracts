(ns com.trustblocks.store
  "Guarded ingestion into XTDB.

  XTDB is the source of truth, and it enforces nothing -- no required fields,
  ranges, or enum membership. Anything writing over the Postgres wire bypasses
  every Concerto constraint. So all writes funnel through `put-instance!`,
  which resolves the Malli schema from $class and validates before insert.

  Takes an XTDB node explicitly, so it works against a bare node in the REPL or
  against Biff's (:biff.xtdb/node ctx)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [com.trustblocks.concerto :as concerto]
            [com.trustblocks.concerto.model :as model]
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
  (let [raw     (slurp cto-path)
        parsed  (-> (model/parse-cto cto-path :concerto-cli concerto-cli)
                    model/strip-locations)
        ns-     (get parsed "namespace")
        decls   (model/declarations parsed)]
    (xt/execute-tx
     node
     (into [[:put-docs model-table
             {:xt/id            ns-
              :concerto/source  raw
              :concerto/path    (str cto-path)
              :concerto/model   (concerto/->xtdb parsed)
              :declaration-count (count decls)}]]
           (for [{:keys [fqn name declaration]} decls]
             [:put-docs declaration-table
              {:xt/id               fqn
               :concerto/namespace  ns-
               :concerto/name       name
               :concerto/kind       (get declaration "$class")
               :concerto/declaration (concerto/->xtdb declaration)}])))
    {:namespace ns- :declarations (mapv :fqn decls)}))

(defn load-registry
  "Build a declaration registry from CTO files on disk. The model directory of
  an Accord template vendors its imports alongside model.cto, so pointing at the
  directory resolves inheritance chains that cross namespaces."
  [cto-paths & {:keys [concerto-cli]}]
  (->> cto-paths
       (map #(model/parse-cto % :concerto-cli concerto-cli))
       (map model/strip-locations)
       model/registry))

(defn registry-from-xtdb
  "Rebuild the declaration registry from XTDB itself, with no filesystem access.

  `put-model!` stores each parsed model whole, so reading it back and running it
  through the same `model/registry` gives an identical registry. This is what
  makes XTDB the source of truth in practice rather than just in principle: the
  schema needed to interpret a record lives beside the record.

  Building this scans every stored model, so callers doing many reads should
  build it once and pass it as :reg."
  [node]
  (->> (xt/q node '(from :concerto-model [*]))
       (keep :concerto/model)
       (map concerto/<-xtdb)
       model/registry))

(defn model-dir-files
  "Every .cto in a directory (a template's model/ folder)."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.endsWith (.getName %) ".cto"))
       (sort-by #(.getName %))
       vec))

;; ---------------------------------------------------------------- instances

(defn validate
  "Validate a Concerto instance against its $class. Returns nil when valid,
  otherwise a humanized explanation."
  [reg instance]
  (let [fqn (get instance "$class")]
    (when-not fqn
      (throw (ex-info "Concerto instance has no $class" {:instance instance})))
    (let [schema (model/->malli reg fqn)
          doc    (concerto/->xtdb instance)]
      (when-not (m/validate schema doc)
        (-> schema (m/explain doc) me/humanize)))))

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
    (let [doc (concerto/instance->doc
               instance
               :identity-field (model/identity-field reg fqn))]
      (xt/execute-tx node [[:put-docs table doc]])
      {:xt/id (:xt/id doc) :$class fqn})))

(defn get-instance
  "Read an instance back out as Concerto JSON, restoring $class and the original
  camelCase property names."
  [node id & {:keys [table reg] :or {table contract-table}}]
  ;; The id must be matched as a literal in the binding pattern. Writing
  ;; {:xt/id $id} with an :args param does NOT filter -- XTDB treats $id as
  ;; another column to project and returns the entire table.
  (let [q (list 'from table [{:xt/id id} (symbol "*")])]
    (when-let [record (first (xt/q node q))]
      ;; Falls back to the stored schema rather than to ->camelCase: inferring
      ;; the name is lossy for anything not plain camelCase (vechileID,
      ;; INITIAL_DUE), so a faithful read must not be opt-in.
      (let [reg (or reg (registry-from-xtdb node))]
        (concerto/<-xtdb record (memoize #(model/property-names reg %)))))))

(defn instance->json [instance] (json/generate-string instance {:pretty true}))
