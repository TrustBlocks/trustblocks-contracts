(ns concerto.cto
  "CTO text -> metamodel EDN, by shelling out to Accord's `concerto` CLI.

  This is the one namespace that does I/O and the one that needs Node, which is
  why it is separate: `concerto.metamodel` and `concerto.malli` take data and
  have no opinion about where it came from. Anything that can produce metamodel
  JSON can feed them -- the CLI today, an instaparse port of
  `concerto-cto/lib/parser.pegjs` later, or a JSON file someone else generated.

  The grammar is normative and it drifts; reimplementing it is a standing
  obligation to track that drift. Consuming the metamodel instead is a contract
  against a versioned artifact."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [concerto.metamodel :as mm]))

(defn find-cli
  "Locate the `concerto` CLI: an explicit path, then $CONCERTO_CLI, then $PATH."
  [& {:keys [cli]}]
  (let [candidate (or cli
                      (System/getenv "CONCERTO_CLI")
                      "concerto")
        resolved  (if (.contains ^String candidate "/")
                    candidate
                    (let [{:keys [exit out]} (p/sh "sh" "-c" (str "command -v " candidate))]
                      (when (zero? exit) (str/trim out))))]
    (when (and resolved (.canExecute (io/file resolved)))
      resolved)))

(defn parse
  "Parse one .cto file to metamodel EDN, with source locations stripped."
  [cto-path & {:keys [cli] :as opts}]
  (let [f   (io/file cto-path)
        bin (find-cli opts)]
    (when-not (.exists f)
      (throw (ex-info "CTO file not found" {:path (str f)})))
    (when-not bin
      (throw (ex-info (str "concerto CLI not found. Install it with "
                           "`npm i -g @accordproject/concerto-cli`, or set "
                           "$CONCERTO_CLI to its path.")
                      {:tried (or cli (System/getenv "CONCERTO_CLI") "concerto")})))
    (let [{:keys [exit out err]} (p/sh bin "parse" "--model" (.getPath f))]
      (when-not (zero? exit)
        (throw (ex-info "concerto parse failed"
                        {:path (.getPath f) :exit exit :err err})))
      (-> (json/parse-string out)
          mm/json->edn
          mm/strip-locations))))

(defn model-files
  "Every .cto under a directory. An Accord template vendors its imports
  alongside model.cto, so pointing at the directory resolves inheritance chains
  that cross namespaces."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.endsWith (.getName ^java.io.File %) ".cto"))
       (sort-by #(.getName ^java.io.File %))
       vec))

(defn load-registry
  "Parse a .cto file or a directory of them into a declaration registry."
  [path & {:as opts}]
  (let [f (io/file path)]
    (->> (if (.isDirectory f) (model-files f) [f])
         (map #(parse % opts))
         mm/registry)))
