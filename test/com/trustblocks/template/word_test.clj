(ns com.trustblocks.template.word-test
  "Word export: a contract with its data, and a template's blank form."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.contracts :as contracts]
            [com.trustblocks.app.templates :as templates]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.template.word :as word]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.util.zip ZipInputStream]))

(defn- document-xml
  "The text of a .docx's word/document.xml, tags stripped."
  [^bytes docx]
  (with-open [zin (ZipInputStream. (java.io.ByteArrayInputStream. docx))]
    (loop []
      (when-let [e (.getNextEntry zin)]
        (if (= "word/document.xml" (.getName e))
          (str/replace (slurp zin) #"<[^>]+>" "")
          (recur))))))

(defn- slurp-bytes [in]
  (let [out (java.io.ByteArrayOutputStream.)] (io/copy in out) (.toByteArray out)))

(deftest pandoc-is-available
  (is (zero? (:exit (shell/sh "pandoc" "--version")))
      "Word export needs Pandoc -- brew install pandoc"))

(deftest the-blank-form-leaves-every-blank-blank
  (with-open [node (xt.node/start-node)]
    (archive/ingest-dir! node (library/dir "manager-employment-contract"))
    (let [reg    (store/registry-from-xtdb node)
          sample (json/parse-string (library/sample "manager-employment-contract"))
          blank  (word/blank reg sample)]
      (testing "optional properties are gone; required ones -- the form's fixed terms -- stay"
        (is (contains? sample "retroactiveDate"))
        (is (not (contains? blank "retroactiveDate")))
        (is (= (get sample "municipalityName") (get blank "municipalityName"))))
      (testing "and the Word document shows the blank as its line"
        (let [text (document-xml (slurp-bytes (:body (templates/blank-docx
                                                      {:biff.xtdb/node node
                                                       :uri "/templates/manager-employment-contract/blank.docx"}))))]
          (is (str/includes? text "TOWN OF CHINA GROVE"))
          (is (not (str/includes? text "blank form")))
          (is (re-find #"_{6,}" text)))))))

(deftest a-contract-exports-with-its-data
  (with-open [node (xt.node/start-node)]
    (archive/ingest-dir! node (library/dir "street-resurfacing/pay-application"))
    (store/put-instance! node nil (assoc (json/parse-string (library/sample "street-resurfacing/pay-application"))
                                         "payRequestId" "pr-1" "contractorName" "Example Paving, LLC"
                                         "netAmountDue" 21576.87))
    (let [{:keys [status headers body]} (contracts/document-docx {:biff.xtdb/node node :uri "/contracts/pr-1/document.docx"})
          text (document-xml (slurp-bytes body))]
      (is (= 200 status))
      (is (str/includes? (get headers "content-disposition") "pr-1.docx"))
      (is (str/includes? text "CONTRACTOR PAY REQUEST"))
      (is (str/includes? text "Example Paving, LLC"))
      (is (str/includes? text "$21,576.87")))))
