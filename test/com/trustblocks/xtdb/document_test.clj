(ns com.trustblocks.xtdb.document-test
  "The document store: content-addressed files, one XTDB record per
  document, and reads that refuse bytes no longer matching their hash."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.xtdb.document :as doc]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(defn- temp-root [] (str (Files/createTempDirectory "docs" (make-array FileAttribute 0))))

(defn- sha256 [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (MessageDigest/getInstance "SHA-256") bs))))

(def ^:private report (.getBytes "Inspection report: pay items 5.1, 5.2 inspected and approved." "UTF-8"))

(deftest a-document-is-stored-by-its-hash-and-reads-back
  (with-open [node (xt.node/start-node)]
    (let [root   (temp-root)
          record (doc/store! node root report :filename "inspection.pdf" :media-type "application/pdf")]
      (is (= (str "sha256:" (sha256 report)) (:xt/id record)))
      (is (= (count report) (:document/size record)))
      (is (= "application/pdf" (:document/media-type record)))
      (is (= record (doc/get-document node (:xt/id record))))
      (is (= (seq report) (seq (doc/content root (:xt/id record)))))
      (testing "the file sits at its hash's path, and nothing is left behind in tmp/"
        (is (.exists (io/file root "sha256" (subs (sha256 report) 0 2) (sha256 report))))
        (is (empty? (.listFiles (io/file root "tmp"))))))))

(deftest the-same-bytes-are-one-document
  (with-open [node (xt.node/start-node)]
    (let [root   (temp-root)
          first  (doc/store! node root report :filename "first-name.pdf")
          second (doc/store! node root (java.io.ByteArrayInputStream. report) :filename "second-name.pdf")]
      (is (= first second) "storing it again returns the existing record, first name kept")
      (is (= "first-name.pdf" (:document/filename second))))))

(deftest reads-refuse-bytes-that-no-longer-match
  (with-open [node (xt.node/start-node)]
    (let [root (temp-root)
          {id :xt/id} (doc/store! node root report)
          f    (io/file root "sha256" (subs (sha256 report) 0 2) (sha256 report))
          tag  (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))]
      (testing "altered on disk"
        (spit f "tampered")
        (is (= :document-corrupt (tag #(doc/content root id)))))
      (testing "gone from disk"
        (.delete f)
        (is (= :document-missing (tag #(doc/content root id)))))
      (testing "not an id at all"
        (is (= :invalid-document-id (tag #(doc/content root "report.pdf"))))))))

(deftest a-string-is-not-a-document-source
  ;; clojure.java.io would treat it as a URL and fetch it.
  (with-open [node (xt.node/start-node)]
    (is (= :invalid-document-source
           (try (doc/store! node (temp-root) "https://example.com/report.pdf") nil
                (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e))))))))

(deftest concurrent-stores-of-the-same-bytes-agree
  (with-open [node (xt.node/start-node)]
    (let [root    (temp-root)
          results (->> (range 8) (mapv (fn [_] (future (doc/store! node root report)))) (mapv deref))]
      (is (apply = (map :xt/id results)))
      (is (= (seq report) (seq (doc/content root (:xt/id (first results)))))))))
