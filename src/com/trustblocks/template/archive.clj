(ns com.trustblocks.template.archive
  "Ingest an Accord Template Archive (.cta) -- or an unpacked template
  directory in the same layout -- into XTDB.

  A .cta is a zip: model/*.cto (the template's own model plus every import
  vendored alongside it -- the same directory shape
  com.trustblocks.template.audit already resolves for validation),
  text/grammar.tem.md (TemplateMark prose, stored verbatim -- rendering it
  *faithfully*, with Accord's full TemplateMark grammar, is a JavaScript
  toolchain (see docs/walkthrough.md); com.trustblocks.template.render
  implements a narrower {{var}}/{{#if}} substitution renderer for our own
  Contract View, not that), logic/*.ts (the template's clause logic, Ergo's
  successor), and package.json (name, version, description, and the
  accordproject.{runtime,cicero} it was authored against).

  Ingestion here means every model/*.cto persisted individually via
  com.trustblocks.xtdb.store/put-model!, so the whole template's schema is
  queryable and rebuildable from XTDB alone afterward -- no filesystem
  dependency, matching every other model this app stores.

  Storing a template's logic.ts is not the same as being able to run it. It
  is kept as a readable, citable artifact -- what a contract of this type
  was supposed to do, in the language it was authored in -- until a
  TypeScript execution backend exists. See com.trustblocks.xtdb.execute's
  runtimes: a contract ingested this way can be stored and queried today,
  and executed once :typescript is a real backend rather than only a
  refusal.

  A template whose logic is Clojure -- ours, from trustblocks-templates --
  says so in a package.json section of its own, since Accord's tools accept
  only es6/typescript as accordproject.runtime and would refuse the archive
  otherwise:

    \"trustblocks\": {\"runtime\": \"clojure\", \"logic\": \"logic/clause.clj\"}

  Ingesting such a template installs that file as the clause for the
  template's root type (the one concept carrying @template) through
  execute/put-clause!, which compiles it in the sandbox before storing it --
  so a template's logic lands exactly as a hand-installed clause would."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipFile]))

(def archive-table :template-archive)

;; ---------------------------------------------------------------- unzip

(defn unzip!
  "Extracts a .cta (or any zip) into a fresh temp directory, returned as a
  java.io.File. The caller owns cleanup; nothing here deletes it."
  [cta-path]
  (let [dest (.toFile (Files/createTempDirectory
                       "trustblocks-cta" (make-array FileAttribute 0)))]
    (with-open [zf (ZipFile. (io/file cta-path))]
      (doseq [entry (enumeration-seq (.entries zf))]
        (let [out (io/file dest (.getName entry))]
          (if (.isDirectory entry)
            (.mkdirs out)
            (do (io/make-parents out)
                (with-open [in (.getInputStream zf entry)]
                  (io/copy in out)))))))
    dest))

;; ---------------------------------------------------------------- ingest

(defn- package-metadata [dir]
  (let [f (io/file dir "package.json")]
    (when (.exists f)
      (json/parse-string (slurp f) true))))

(defn- logic-files
  "The template's own .ts logic sources -- not logic/generated/ (Concerto
  codegen output, derivable from the model we already store) and not
  *.test.ts (tests for a runtime we don't execute yet)."
  [dir]
  (let [logic-dir (io/file dir "logic")]
    (when (.exists logic-dir)
      (->> (file-seq logic-dir)
           (filter #(.isFile ^File %))
           (filter #(.endsWith (.getName ^File %) ".ts"))
           (remove #(.contains (.getPath ^File %) "/generated/"))
           (remove #(.contains (.getName ^File %) ".test."))
           (sort-by #(.getName ^File %))
           vec))))

(defn- grammar-text [dir]
  (let [f (io/file dir "text" "grammar.tem.md")]
    (when (.exists f) (slurp f))))

(defn- template-root
  "The fully-qualified name of the one declaration carrying @template among
  `namespaces` -- the template's root type, which its clause runs for.
  Asked of the registry rather than read from package.json, so there is
  one statement of it: the model's."
  [node namespaces]
  (let [reg   (store/registry-from-xtdb node)
        ns?   (set namespaces)
        roots (for [[fqn {:keys [namespace declaration]}] reg
                    :when (and (ns? namespace)
                               (some #(= "template" (:name %)) (:decorators declaration)))]
                fqn)]
    (when (not= 1 (count roots))
      (throw (ex-info "A template must have exactly one @template declaration"
                      {:namespaces namespaces :found (vec roots)})))
    (first roots)))

(defn- install-clause!
  "Installs a Clojure-logic template's clause -- see the ns docstring.
  Returns the root type it was installed for, or nil when the template's
  trustblocks section names no logic."
  [node dir namespaces {:keys [trustblocks]}]
  (when (:logic trustblocks)
    (let [{:keys [runtime logic]} trustblocks
          f (io/file dir logic)]
      (when-not (= "clojure" runtime)
        (throw (ex-info "Unsupported trustblocks.runtime" {:dir (str dir) :runtime runtime})))
      (when-not (.exists f)
        (throw (ex-info "trustblocks.logic names no file in the template"
                        {:dir (str dir) :logic logic})))
      (let [root (template-root node namespaces)]
        (execute/put-clause! node root (slurp f) :runtime :clojure)
        root))))

(defn- install-lifecycle!
  "Stores the lifecycle a template declares (trustblocks.lifecycle) for its
  root type -- see com.trustblocks.lifecycle. Returns the root type, or nil
  when it declares none."
  [node dir namespaces {:keys [trustblocks]}]
  (when-let [lifecycle (:lifecycle trustblocks)]
    (let [f (io/file dir lifecycle)]
      (when-not (.exists f)
        (throw (ex-info "trustblocks.lifecycle names no file in the template"
                        {:dir (str dir) :lifecycle lifecycle})))
      (let [root (template-root node namespaces)]
        (execute/put-lifecycle! node root (json/parse-string (slurp f)))
        root))))

(defn ingest-dir!
  "Loads every model/*.cto in `dir` into XTDB and records the template's
  package.json metadata plus its logic source (unexecuted -- see ns
  docstring) under archive-table, keyed by the package name. A template
  with Clojure logic also has its clause installed.

  Returns {:name :namespaces :logic-files :clause :lifecycle}, :clause and
  :lifecycle being the root type a clause or lifecycle was installed for, or
  nil."
  [node dir]
  (let [dir       (io/file dir)
        model-dir (io/file dir "model")
        ctos      (when (.exists model-dir) (store/model-dir-files model-dir))]
    (when (empty? ctos)
      (throw (ex-info "No model/*.cto files found" {:dir (str dir)})))
    (let [loaded  (mapv #(store/put-model! node %) ctos)
          pkg     (package-metadata dir)
          logic   (logic-files dir)
          id      (or (:name pkg) (.getName dir))
          grammar (grammar-text dir)
          record  (cond-> {:xt/id id
                           :archive/namespaces (mapv :namespace loaded)}
                    pkg
                    (merge {:archive/name        (:name pkg)
                            :archive/version     (:version pkg)
                            :archive/description (:description pkg)
                            :archive/runtime     (get-in pkg [:accordproject :runtime])
                            :archive/cicero      (get-in pkg [:accordproject :cicero])})

                    (seq logic)
                    (assoc :archive/logic
                           (mapv (fn [f] {:file (.getName ^File f) :source (slurp f)}) logic))

                    grammar
                    (assoc :archive/grammar grammar))]
      (xt/execute-tx node [[:put-docs archive-table record]])
      {:name        id
       :namespaces  (:archive/namespaces record)
       :logic-files (mapv :file (:archive/logic record []))
       :clause      (install-clause! node dir (:archive/namespaces record) pkg)
       :lifecycle   (install-lifecycle! node dir (:archive/namespaces record) pkg)})))

(defn by-namespace
  "The archive record whose :archive/namespaces includes `ns`, or nil.

  There's no explicit link recorded from a contract instance back to the
  archive it was minted from -- only every namespace an archive's ingest
  touched. A namespace belongs to at most one archive in practice (each
  model/*.cto is ingested from exactly one template directory), so this
  scan is how a $class's own template -- and its grammar, for Contract
  View -- gets found. Loads every archive into memory to do it; fine at
  the handful of templates this app knows about today, worth indexing if
  that stops being true."
  [node ns]
  (->> (xt/q node (list 'from archive-table [(symbol "*")]))
       (filter #(some #{ns} (:archive/namespaces %)))
       first))

(defn ingest-cta!
  "Unzips a .cta file to a temp directory and ingests it. See ingest-dir!."
  [node cta-path]
  (ingest-dir! node (unzip! cta-path)))
