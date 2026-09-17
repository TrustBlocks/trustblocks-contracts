;; Checks every template under docs/examples against Accord Project's own
;; toolchain: cicero-core must load the archive, and template-engine must
;; draft it from its own sample.json. Exits non-zero on any failure.
;;
;; This is nbb (Clojure on Node), not Clojure -- the reference implementation
;; is JavaScript and there is no JVM port of it, so the check has to run where
;; it lives. Written in Clojure anyway so it reads like the rest of the repo.
;;
;;   bin/template-conformance
;;
;; Why bother, when com.trustblocks.template.render already renders these
;; templates for the web UI: that renderer implements a subset, and a subset
;; written to the wrong semantics still looks fine on the page. This is the
;; oracle that says whether a template is actually valid TemplateMark. It
;; catches, for instance, a variable used inside a conditional block, which
;; Accord rejects and a naive renderer happily prints. See
;; docs/templatemark-conformance.md.

(ns conformance
  (:require ["@accordproject/cicero-core" :refer [Template]]
            ["@accordproject/template-engine" :refer [TemplateArchiveProcessor]]
            ["fs" :as fs]
            ["path" :as path]
            [promesa.core :as p]))

(defn- template-dirs
  "Directories under `root` that look like template archives -- a package.json
  is what cicero-core keys on, so it is what we look for."
  [root]
  (if-not (fs/existsSync root)
    []
    (->> (fs/readdirSync root)
         (map #(path/join root %))
         (filter #(fs/existsSync (path/join % "package.json")))
         sort)))

(defn- check
  "Load and draft one template. Returns a promise of true/false; prints either
  way, since a list of what passed is as useful as the failure."
  [dir]
  (let [name (path/basename dir)]
    (-> (p/let [sample (path/join dir "sample.json")
                _      (when-not (fs/existsSync sample)
                         (throw (js/Error. "no sample.json to draft from")))
                data   (-> (fs/readFileSync sample "utf8") js/JSON.parse)
                t      (.fromDirectory Template dir)
                out    (.draft (TemplateArchiveProcessor. t) data "markdown" #js {})]
          (println (str "  ok    " (.getIdentifier t) "  (" (count out) " chars drafted)"))
          true)
        (p/catch (fn [e]
                   (println (str "  FAIL  " name))
                   (println (str "        " (.-message e)))
                   false)))))

(p/let [root (or (first *command-line-args*) "docs/examples")
        dirs (template-dirs root)]
  (if (empty? dirs)
    (println (str "No templates found under " root " (looking for directories with a package.json)."))
    (p/let [_   (println (str "Accord conformance: " (count dirs) " template(s) under " root))
            oks (p/all (map check dirs))]
      (when (some false? oks)
        (println)
        (println "A template does not load or draft through Accord's own toolchain.")
        (println "See docs/templatemark-conformance.md for what the engine requires.")
        (js/process.exit 1)))))
