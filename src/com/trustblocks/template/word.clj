(ns com.trustblocks.template.word
  "Word export: a contract, or a template's blank form, as a .docx.

    contract     the contract as drafted from its data -- a running
                 contract, or a draft
    blank form   the template with every blank left blank: its sample with
                 each optional property removed, since in our templates an
                 optional property is exactly a blank (see
                 trustblocks-templates' docs/model-conventions.md, Every
                 blank is a field). The form as issued, to send to a
                 bidder or a third party to fill in.

  Both render the template's TemplateMark exactly as the Contract View
  does (com.trustblocks.template.render) and hand the HTML to Pandoc,
  styled by resources/word/reference.docx (Times New Roman, 12 pt,
  headings in black). Pandoc is a runtime dependency of these exports
  only; without it they are refused as :pandoc-unavailable, and nothing
  else is affected."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [com.trustblocks.model.properties :as model.properties]
            [com.trustblocks.template.render :as render])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-file ^java.io.File [suffix]
  (.toFile (Files/createTempFile "trustblocks" suffix (make-array FileAttribute 0))))

(defn- reference-doc
  "The styles Pandoc applies, as a file it can read -- the resource may be
  inside a jar."
  ^java.io.File []
  (let [f (temp-file ".docx")]
    (with-open [in (io/input-stream (io/resource "word/reference.docx"))]
      (io/copy in f))
    f))

(defn docx
  "`grammar` rendered against `data`, as the bytes of a .docx -- the
  template's own text from its first line; no title of ours above it."
  ^bytes [grammar data]
  (let [html (str "<html><head><meta charset=\"utf-8\"></head><body>"
                  (render/render-html grammar data)
                  "</body></html>")
        ref  (reference-doc)
        out  (temp-file ".docx")]
    (try
      (let [{:keys [exit err]}
            (try
              (shell/sh "pandoc" "--from" "html" "--to" "docx"
                        "--reference-doc" (str ref) "--output" (str out)
                        :in html :in-enc "UTF-8")
              (catch java.io.IOException e
                (throw (ex-info "Word export needs Pandoc, which is not installed here"
                                {:trustblocks/error :pandoc-unavailable} e))))]
        (when-not (zero? exit)
          (throw (ex-info (str "Pandoc could not convert the document: " err)
                          {:trustblocks/error :pandoc-failed :exit exit})))
        (Files/readAllBytes (.toPath out)))
      (finally
        (.delete ref)
        (.delete out)))))

(defn blank
  "`instance` with every optional property removed, at every depth -- the
  form with its blanks left blank."
  [reg instance]
  (cond
    (and (map? instance) (get instance "$class"))
    (let [optional (set (keep #(when (:isOptional %) (:name %))
                              (model.properties/own-properties reg (get instance "$class"))))]
      (into {} (keep (fn [[k v]] (when-not (optional k) [k (blank reg v)]))) instance))

    (sequential? instance) (mapv #(blank reg %) instance)
    :else instance))
