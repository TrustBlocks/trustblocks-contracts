(ns com.trustblocks.xtdb.model-repository
  "The model repository: Concerto models kept as immutable, pinnable versions.

  Our shared models (MunicipalAgreement, the attestation pair, the lifecycle
  and deadline types) and the Accord models they import are authored as .cto
  files in trustblocks-templates, and each template carries its own copy. This
  is the one place the running app reads them from: a model is imported once,
  and a template, clause or release names the exact models it was built
  against.

  A Concerto namespace already carries its version (\"com.trustblocks.
  attestation@1.0.0\"), so a model's id is that namespace, written once and
  never altered. Writing different content under a namespace that exists is
  refused: the way to change a model is a new version. What counts as the
  same content is the parsed model, not the text, so reformatting a file is
  not a change. Every read re-parses and re-hashes what it returns, and a
  model that no longer matches its hash is refused, never served.

  `closure` follows imports, `registry` builds the declaration registry a
  composition or type check needs, and `pins` fingerprints a root model and
  everything it imports, which is what a release records so the schema it was
  built against cannot change under it. `verify-pins` is the question asked
  later."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.concerto.parser :as parser]
            [com.trustblocks.templatemark.clauses :as clauses]
            [xtdb.api :as xt]))

(def table :model-version)

;; ----------------------------------------------------------------- helpers

(defn- refuse [error msg data]
  (throw (ex-info msg (assoc data :trustblocks/error error))))

(defn- ->instant [x] (cond (nil? x) nil (instance? java.time.Instant x) x :else (java.time.Instant/parse x)))

(defn- assert-failed? [e] (= :xtdb/assert-failed (:xtdb.error/code (ex-data e))))

(defn- unindexed? [e] (= :xtdb/unindexed-tx (:xtdb.error/code (ex-data e))))

(defn- with-reads
  "Runs `f` again a moment later when XTDB 2.1 says a read came before it had
  finished indexing. Nothing has been written then, so a read is safe to repeat."
  [f]
  (loop [n 0]
    (let [r (try {:ok (f)} (catch Exception e (if (and (unindexed? e) (< n 20)) {:retry e} (throw e))))]
      (if (contains? r :ok)
        (:ok r)
        (do (Thread/sleep (inc n)) (recur (inc n)))))))

(def ^:private star (symbol "*"))

(defn- builtin?
  "Namespaces the Concerto runtime provides itself: nothing to look up."
  [ns]
  (boolean (re-matches #"concerto(@[0-9][^.]*\.[0-9]+\.[0-9]+[^.]*)?(\..+)?" ns)))

(defn model-hash
  "SHA-256 of a parsed model, independent of how the text was laid out."
  [model]
  (clauses/sha-256 (pr-str (clauses/canonical model))))

(defn- imported-namespaces [model]
  (into (sorted-set) (comp (map :namespace) (remove builtin?)) (:imports model)))

(defn- split-namespace
  "\"com.trustblocks.attestation@1.0.0\" -> [\"com.trustblocks.attestation\" \"1.0.0\"]."
  [ns]
  (when-let [[_ n v] (re-matches #"(.+)@(.+)" (str ns))] [n v]))

(defn- parse [ns cto]
  (try (parser/parse-string cto)
       (catch Exception e
         (refuse :invalid-model (str "Model " ns " does not parse: " (ex-message e)) {:model ns}))))

;; -------------------------------------------------------------------- rows

(defn- model->row [{:model/keys [id name version cto hash imports note author]}]
  (cond-> {:xt/id id :name name :version version :cto cto :hash hash
           :imports (pr-str imports)}
    note   (assoc :note note)
    author (assoc :author author)))

(defn- row->model [row]
  (cond-> #:model{:id      (:xt/id row)
                  :name    (:name row)
                  :version (:version row)
                  :cto     (:cto row)
                  :hash    (:hash row)
                  :imports (edn/read-string (:imports row))}
    (:note row)   (assoc :model/note (:note row))
    (:author row) (assoc :model/author (:author row))))

(defn- verified
  "The model a stored row holds, after checking it still hashes as written."
  [row]
  (let [m      (row->model row)
        parsed (try (parser/parse-string (:model/cto m)) (catch Exception _ nil))]
    (when-not (and parsed (= (:model/hash m) (model-hash parsed)))
      (refuse :model-corrupt
              (str "Model " (:model/id m) " no longer matches its hash; it was altered after it was stored")
              {:model (:model/id m)}))
    m))

;; ------------------------------------------------------------------ writes

(defn put-model!
  "Store the Concerto model in `cto` under its own namespace and return it as
  {:model/id :model/name :model/version :model/cto :model/hash :model/imports}.

  The namespace must carry a version. Writing a model whose content equals the
  stored one changes nothing and returns the stored model; writing different
  content under a namespace that exists is refused (:model-version-immutable):
  publish it as a new version. A model's imports need not be present yet, so
  models may be loaded in any order; `closure` and `verify-pins` report what
  is missing.

  Options: `:valid-from` (default now), `:by`, `:note`."
  [node cto & {:keys [valid-from by note]}]
  (let [parsed (parse "(unknown)" cto)
        ns     (:namespace parsed)
        [n v]  (split-namespace ns)]
    (when-not n
      (refuse :invalid-model (str "A stored model's namespace carries a version, as name@1.0.0; not " (pr-str ns))
              {:model ns}))
    (let [h (model-hash parsed)]
      (if-let [stored (some-> (first (with-reads #(xt/q node (list 'from table {:bind [{:xt/id ns} star]})))) verified)]
        (if (= h (:model/hash stored))
          stored
          (refuse :model-version-immutable
                  (str "Model " ns " is already stored with different content. A model version is never changed; give the change a new version.")
                  {:model ns :stored-hash (:model/hash stored) :hash h}))
        (let [m (cond-> #:model{:id ns :name n :version v :cto cto :hash h
                                :imports (vec (imported-namespaces parsed))}
                  note (assoc :model/note note)
                  by   (assoc :model/author by))]
          (try
            (xt/execute-tx node
                           [[:sql "ASSERT NOT EXISTS (SELECT 1 FROM model_version WHERE _id = ?)" [ns]]
                            [:put-docs {:into table :valid-from (or (->instant valid-from) (java.time.Instant/now))}
                             (model->row m)]])
            m
            (catch Exception e
              ;; a concurrent writer stored it first: it is then either the same model or a conflict
              (if (assert-failed? e) (put-model! node cto) (throw e)))))))))

(defn- cto-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".cto")))
       (sort-by #(.getPath ^java.io.File %))))

(defn import-files!
  "Import every .cto in `files` (paths or Files) with `put-model!`. A model
  that differs from the stored one under the same namespace does not stop the
  rest: it is reported. Returns, per file,
  {:file :id :result :imported|:unchanged|:conflict|:invalid :message}."
  [node files & {:as opts}]
  (mapv (fn [f]
          (let [file (io/file f), cto (slurp file), path (.getPath file)]
            (try
              (let [parsed (parser/parse-string cto)
                    ns     (:namespace parsed)
                    before (first (with-reads #(xt/q node (list 'from table {:bind [{:xt/id ns} star]}))))
                    m      (apply put-model! node cto (mapcat identity opts))]
                {:file path :id (:model/id m) :result (if before :unchanged :imported)})
              (catch clojure.lang.ExceptionInfo e
                (let [err (:trustblocks/error (ex-data e))]
                  {:file path :id (:model (ex-data e))
                   :result (if (= :model-version-immutable err) :conflict :invalid)
                   :message (ex-message e)})))))
        files))

(defn import-dir!
  "`import-files!` over every .cto under `dir`, recursively. Importing
  trustblocks-templates' shared/model/ and a template's vendored model/
  directory is how the repository is filled."
  [node dir & {:as opts}]
  (import-files! node (cto-files dir) opts))

;; ------------------------------------------------------------------- reads

(defn get-model
  "The model stored under namespace `ns`, or nil. Throws :model-corrupt if
  what is stored no longer matches its hash."
  [node ns]
  (some-> (first (with-reads #(xt/q node (list 'from table {:bind [{:xt/id ns} star]}))))
          verified))

(defn parsed
  "The metamodel EDN of the model stored under `ns`, or nil."
  [node ns]
  (some-> (get-model node ns) :model/cto parser/parse-string))

(defn models
  "A summary of every stored model, by id: {:id :name :version :hash :imports}."
  [node]
  (->> (with-reads #(xt/q node (list 'from table {:bind [{:xt/id 'id} 'name 'version 'hash 'imports]})))
       (map (fn [r] {:id (:id r) :name (:name r) :version (:version r) :hash (:hash r)
                     :imports (edn/read-string (:imports r))}))
       (sort-by :id) vec))

(defn versions
  "The stored versions of the model called `name` (e.g.
  \"com.trustblocks.municipal.agreement\"), as strings, in version order."
  [node name]
  (->> (models node)
       (filter #(= name (:name %)))
       (map :version)
       (sort-by (fn [v] (mapv #(or (parse-long %) 0) (re-seq #"\d+" v))))
       vec))

(defn latest
  "The highest stored version of the model called `name`, as a model, or nil."
  [node name]
  (when-let [v (last (versions node name))]
    (get-model node (str name "@" v))))

(defn importers
  "The stored models that import `ns` directly, by id."
  [node ns]
  (->> (models node) (filter #(some #{ns} (:imports %))) (mapv :id)))

;; ----------------------------------------------------------------- closure

(defn closure
  "The models `ns` needs, dependencies first and `ns` last, following imports:
  {:order [ns ...] :missing [ns ...]}. A namespace that is imported but not
  stored is in :missing, and is not in :order."
  [node ns]
  (let [seen (volatile! #{}), order (volatile! []), missing (volatile! (sorted-set))]
    (letfn [(visit [n]
              (when-not (@seen n)
                (vswap! seen conj n)
                (if-let [m (get-model node n)]
                  (do (doseq [i (:model/imports m)] (visit i))
                      (vswap! order conj n))
                  (vswap! missing conj n))))]
      (visit ns))
    {:order @order :missing (vec @missing)}))

(defn registry
  "The declaration registry (as com.trustblocks.concerto.metamodel/registry
  builds it) for `ns` and everything it imports, ready for drafting, type
  checking or composition. Refuses when a model it needs is not stored."
  [node ns]
  (let [{:keys [order missing]} (closure node ns)]
    (when (seq missing)
      (refuse :model-missing
              (str "Model " ns " needs " (str/join ", " missing) ", which " (if (next missing) "are" "is") " not in the repository")
              {:model ns :missing missing}))
    (mm/registry (mapv #(parsed node %) order))))

;; -------------------------------------------------------------------- pins

(defn pins
  "A fingerprint of `ns` and everything it imports: {:pins {ns hash ...}
  :hash sha}. The :hash covers every model in the closure, so it changes if
  any of them does. Refuses when something is missing."
  [node ns]
  (let [{:keys [order missing]} (closure node ns)]
    (when (seq missing)
      (refuse :model-missing (str "Model " ns " needs " (str/join ", " missing) ", which is not in the repository")
              {:model ns :missing missing}))
    (let [pm (into (sorted-map) (map (fn [n] [n (:model/hash (get-model node n))])) order)]
      {:root ns :pins pm :hash (clauses/sha-256 (pr-str (clauses/canonical pm)))})))

(defn verify-pins
  "Hold the repository up against `pins` (the :pins map of `pins`): what
  differs now. [] when every pinned model is stored and unchanged; otherwise
  {:problem :model-missing|:model-changed|:model-corrupt :model ns}."
  [node pins]
  (vec (mapcat (fn [[ns h]]
                 (try
                   (let [m (get-model node ns)]
                     (cond (nil? m)                [{:problem :model-missing :model ns}]
                           (not= h (:model/hash m)) [{:problem :model-changed :model ns}]))
                   (catch clojure.lang.ExceptionInfo e
                     [{:problem (or (:trustblocks/error (ex-data e)) :model-corrupt) :model ns}])))
               (sort pins))))
