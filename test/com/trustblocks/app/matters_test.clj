(ns com.trustblocks.app.matters-test
  "The matter page and stored files, called directly as the other handler
  tests do -- over the STR-27 demo matter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.matters :as matters]
            [com.trustblocks.xtdb.matter :as matter]
            [street-resurfacing-matter-demo :as demo]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- body [response]
  (let [b (:body response)] (if (string? b) b (slurp b))))

(deftest the-str-27-matter-page
  (let [root (str (Files/createTempDirectory "matters" (make-array FileAttribute 0)))]
    (with-open [node (xt.node/start-node)]
      (demo/load! node root)
      (let [ctx  {:biff.xtdb/node node :trustblocks/document-root root}
            page (body (matters/show-page (assoc ctx :uri "/matters/str-27")))]
        (testing "documents, each with its status"
          (is (every? #(str/includes? page %) ["str-27-pay-1" "PAID" "APPROVED" "RECEIVED"])))
        (testing "the designation and the signed record, by name and authority"
          (is (str/includes? page "Teresa Barringer, Zoning Administrator"))
          (is (str/includes? page "Designated Representative of the Director of Public Works"))
          (is (str/includes? page "Under designation by David Ketner, Director of Public Works"))
          (is (str/includes? page "Rebekah Newsome, Finance Director"))
          (is (= 16 (count (re-seq #"signature verified" page)))))
        (testing "the list"
          (is (str/includes? (body (matters/list-page ctx)) "6 documents")))
        (testing "a stored file is served, re-hashed; a corrupted one is not"
          (let [file (some :stored-document (matter/documents node demo/matter-id))
                ok   (matters/stored-document (assoc ctx :uri (str "/documents/" file)))]
            (is (= 200 (:status ok)))
            (is (str/starts-with? (body ok) "%PDF-1.4"))
            (let [hex (subs file 7)
                  f   (java.io.File. (str root "/sha256/" (subs hex 0 2) "/" hex))]
              (spit f "tampered")
              (is (= 410 (:status (matters/stored-document (assoc ctx :uri (str "/documents/" file)))))))))
        (is (= 404 (:status (matters/show-page (assoc ctx :uri "/matters/nope")))))))))
