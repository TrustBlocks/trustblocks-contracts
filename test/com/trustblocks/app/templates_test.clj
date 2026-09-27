(ns com.trustblocks.app.templates-test
  "The template library -- each template's text, model and code -- called
  directly as the other handler tests do."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.certify :as certify]
            [com.trustblocks.app.templates :as templates]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(defn- body [response] (:body response))

(defn- page [f node uri] (body (f {:biff.xtdb/node node :uri uri})))

(deftest the-library
  (with-open [node (xt.node/start-node)]
    (doseq [t ["manager-employment-contract" "street-resurfacing/contract"
               "street-resurfacing/pay-application"]]
      (archive/ingest-dir! node (library/dir t)))
    (testing "every template, its kind and what code it has"
      (let [list (body (templates/list-page {:biff.xtdb/node node}))]
        (is (str/includes? list "street-resurfacing-pay-application"))
        (is (str/includes? list "clause · lifecycle"))
        (is (str/includes? list "clause · lifecycle · attestation"))
        (is (str/includes? list "clause · lifecycle · deadlines"))))
    (testing "Text: drafted from the sample, with the TemplateMark beneath"
      (let [text (page templates/text-page node "/templates/street-resurfacing-pay-application/text")]
        (is (str/includes? text "CONTRACTOR PAY REQUEST"))
        (is (str/includes? text "{{#ulist payItems}}"))))
    (testing "Model: its own namespace first"
      (let [model (page templates/model-page node "/templates/street-resurfacing-pay-application/model")]
        (is (< (str/index-of model "com.trustblocks.municipal.construction.payapplication@1.0.0")
               (str/index-of model "com.trustblocks.lifecycle@1.0.0")))))
    (testing "Logic: each request, what calls it, and the clause lines that handle it"
      (let [logic (page templates/code-page node "/templates/street-resurfacing-pay-application/code")]
        (is (re-find #"The contract.{1,6}s code" logic))
        (is (str/includes? logic "The Finance Officer certifies payment was made."))
        (is (str/includes? logic "a Receipt holding RECEIVE_PAY_APPLICATIONS"))
        (is (str/includes? logic "Payment due"))
        (is (str/includes? logic "entering APPROVED"))
        (is (not (str/includes? logic "scheduler")))
        (is (re-find #"Handled by the clause at <a [^>]*href=\"#L\d+\"" logic))
        (is (str/includes? logic "passes this event through"))
        (is (str/includes? logic "Not approved by the engineer")))
      (let [logic (page templates/code-page node "/templates/manager-employment-contract/code")]
        (is (str/includes? logic "The Town Council approves the contract, on councilActionDate"))
        (is (str/includes? logic "an Attestation holding CERTIFY_MINUTES"))
        (is (not (str/includes? logic "The trigger endpoint")))
        (is (str/includes? logic "after checking the officeholder holds CERTIFY_MINUTES"))
        (is (str/includes? logic ";; BoardApprovalRequest -- called when the Town Clerk certifies")))
      (let [logic (page templates/code-page node "/templates/street-resurfacing-contract/code")]
        (is (str/includes? logic "Completion Date"))
        (is (str/includes? logic "noticeToProceedDate"))))
    (is (= 404 (:status (templates/code-page {:biff.xtdb/node node :uri "/templates/nope/code"}))))))

(deftest the-contract-says-who-may-attest
  (with-open [node (xt.node/start-node)]
    (archive/ingest-dir! node (library/dir "manager-employment-contract"))
    (archive/ingest-dir! node (library/dir "street-resurfacing/contract"))
    (store/put-instance!
     node nil (json/parse-string (library/sample "manager-employment-contract")))
    (store/put-instance!
     node nil (json/parse-string (library/sample "street-resurfacing/contract")))
    (is (= "CERTIFY_MINUTES" (certify/required-authority node "china-grove-gover-2025")))
    (is (nil? (certify/required-authority node "china-grove-str-27")))))
