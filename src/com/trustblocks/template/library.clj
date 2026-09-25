(ns com.trustblocks.template.library
  "Where the app's own contract templates come from: the
  TrustBlocks/trustblocks-templates repository, a git dependency in
  deps.edn whose templates/ directory is on the classpath -- each template
  under its own name, e.g. manager-employment-contract/package.json.

  That repository is the source of truth for our municipal templates:
  their models, their TemplateMark text, and their Clojure clause logic.
  This app runs them; it no longer carries copies. The :templates-local
  alias points the dependency at a sibling ../trustblocks-templates
  checkout instead of the pinned commit, to try a template change here
  before committing it there."
  (:require [clojure.java.io :as io]))

(defn dir
  "The directory holding template `template-name`, as a java.io.File -- what
  com.trustblocks.template.archive/ingest-dir! reads. A git or local
  dependency is always unpacked on disk, so the resource is a file: URL."
  ^java.io.File [template-name]
  (let [pkg (or (io/resource (str template-name "/package.json"))
                (throw (ex-info (str "No template named " template-name
                                     " on the classpath -- is it in trustblocks-templates?")
                                {:template template-name})))]
    (.getParentFile (io/file pkg))))

(defn sample
  "The template's sample.json, as a string."
  [template-name]
  (slurp (io/file (dir template-name) "sample.json")))
