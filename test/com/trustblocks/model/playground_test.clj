(ns com.trustblocks.model.playground-test
  "Checked here rather than only by hand, once, while writing the feature:
  the real risk in com.trustblocks.model.playground is not Clojure logic
  going wrong, it's the JVM-side LZ-String port silently drifting from what
  the two JavaScript playgrounds actually decode. compress-roundtrips pins
  that down for regressions; the cross-check against real Node lz-string
  and against both playgrounds' own rebuild/validate code, done once while
  writing this, is recorded in the namespace docstring rather than run
  here, since it needs Node and a checkout of both playground repos that
  this project does not otherwise depend on."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.model.playground :as pg]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [blazing.chain LZSEncoding]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- decode [url]
  (let [fragment (subs url (inc (.indexOf ^String url "#")))
        payload  (str/replace-first fragment #"^data=" "")]
    (json/parse-string
     (LZSEncoding/decompressFromEncodedURIComponent payload))))

;; A minimal single-namespace, single-file template -- everything
;; template-playground's share link can actually hold, so template-status
;; has something to say :url to. Written to a real temp directory because
;; archive/ingest-dir! reads from disk, same as any other template.
(def ^:private greeting-cto
  "namespace test.greeting@1.0.0

@template
concept Greeting {
  o String name
}
")

(def ^:private greeting-template "Hello, {{name}}.")

(defn- write-file! [dir & path-and-content]
  (let [content (last path-and-content)
        segs    (butlast path-and-content)
        f       (apply io/file dir segs)]
    (io/make-parents f)
    (spit f content)))

(defn- ingest-greeting! [node]
  (let [dir (.toFile (Files/createTempDirectory "pg-test" (make-array FileAttribute 0)))]
    (write-file! dir "model" "model.cto" greeting-cto)
    (write-file! dir "text" "grammar.tem.md" greeting-template)
    (archive/ingest-dir! node dir)))

(deftest namespaces-follows-supertypes-and-object-properties
  (with-open [node (xt.node/start-node)]
    (store/put-model! node "docs/examples/snow-removal.cto")
    (let [reg (store/registry-from-xtdb node)]
      (testing "a self-contained model closes over exactly its own namespace"
        (is (= #{"com.trustblocks.snow@1.0.0"}
               (pg/namespaces reg "com.trustblocks.snow@1.0.0.SnowRemoval")))
        (testing "including via an ObjectProperty (Money), not just superType"
          (is (= (pg/namespaces reg "com.trustblocks.snow@1.0.0.SnowRemoval")
                 (pg/namespaces reg "com.trustblocks.snow@1.0.0.Money"))))))))

(deftest concerto-url-carries-every-namespace-verbatim
  (with-open [node (xt.node/start-node)]
    (store/put-model! node "docs/examples/snow-removal.cto")
    (let [reg      (store/registry-from-xtdb node)
          url      (pg/concerto-url node reg "com.trustblocks.snow@1.0.0.SnowRemoval")
          sources  (decode url)
          original (store/model-source node "com.trustblocks.snow@1.0.0")]
      (is (.startsWith ^String url "https://concerto-playground.accordproject.org/#"))
      (is (= [original] sources)))))

(defn- with-origin-property
  "Runs `f` with the origin system property set to `origin`, restoring
  whatever value the JVM had before. The property outranks the
  TRUSTBLOCKS_TEMPLATE_PLAYGROUND_ORIGIN variable, so pinning it keeps these
  tests independent of a shell that exported the WebMCP override."
  [origin f]
  (let [k    "trustblocks.template-playground-origin"
        prev (System/getProperty k)]
    (System/setProperty k origin)
    (try
      (f)
      (finally
        (if prev
          (System/setProperty k prev)
          (System/clearProperty k))))))

(deftest template-status-succeeds-for-a-self-contained-template
  (with-open [node (xt.node/start-node)]
    (ingest-greeting! node)
    (let [reg      (store/registry-from-xtdb node)
          instance {"$class" "test.greeting@1.0.0.Greeting" "name" "Ada"}
          {:keys [url unavailable]} (with-origin-property
                                      "https://playground.accordproject.org"
                                      #(pg/template-status node reg instance))]
      (is (some? url))
      (is (nil? unavailable))
      (is (.startsWith ^String url "https://playground.accordproject.org/#data="))
      (let [payload (decode url)]
        (is (= greeting-template (get payload "templateMarkdown")))
        (is (= greeting-cto (get payload "modelCto")))
        (is (= instance (json/parse-string (get payload "data"))))))))

(deftest template-status-can-target-a-local-webmcp-playground
  (with-open [node (xt.node/start-node)]
    (ingest-greeting! node)
    (let [reg      (store/registry-from-xtdb node)
          instance {"$class" "test.greeting@1.0.0.Greeting" "name" "Ada"}
          {:keys [url]} (with-origin-property "http://localhost:5173/"
                          #(pg/template-status node reg instance))]
      (is (.startsWith ^String url "http://localhost:5173/#data=")))))

(deftest blank-origin-property-is-treated-as-unset
  ;; Falls through to the environment variable, if this shell exported one,
  ;; and otherwise to the deployed playground -- never to "".
  (let [env (System/getenv "TRUSTBLOCKS_TEMPLATE_PLAYGROUND_ORIGIN")]
    (is (= (if (str/blank? env)
             "https://playground.accordproject.org"
             (str/replace (str/trim env) #"/+$" ""))
           (with-origin-property "  " pg/template-playground-origin)))))

(deftest template-status-explains-a-missing-template
  (with-open [node (xt.node/start-node)]
    (store/put-model! node "docs/examples/snow-removal.cto")
    (let [reg      (store/registry-from-xtdb node)
          instance {"$class" "com.trustblocks.snow@1.0.0.SnowRemoval" "contractId" "x"}
          {:keys [url unavailable]} (pg/template-status node reg instance)]
      (is (nil? url))
      (is (re-find #"No contract template text on file" unavailable)))))

(deftest template-status-explains-too-many-namespaces
  ;; Reuses the same fixture archive as the walkthrough and dev/seed.clj,
  ;; rather than a synthetic multi-namespace model, so this fails the day
  ;; the real template stops needing more than one namespace -- exactly
  ;; when the explanation would go stale.
  (with-open [node (xt.node/start-node)]
    (archive/ingest-dir! node (library/dir "manager-employment-contract"))
    (let [reg      (store/registry-from-xtdb node)
          instance (json/parse-string
                    (library/sample "manager-employment-contract"))
          {:keys [url unavailable]} (pg/template-status node reg instance)]
      (is (nil? url))
      (is (re-find #"needs \d+ namespaces beyond what Template Playground bundles" unavailable)))))

(deftest compress-roundtrips
  (doseq [payload ["" "hello" "unicode: \u2603 \u00e9\u00e8" (apply str (repeat 500 "ab"))]]
    (is (= payload
           (LZSEncoding/decompressFromEncodedURIComponent
            (LZSEncoding/compressToEncodedURIComponent payload))))))
