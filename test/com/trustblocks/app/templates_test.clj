(ns com.trustblocks.app.templates-test
  "The template library -- each template's text, model and code -- called
  directly as the other handler tests do."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.certify :as certify]
            [com.trustblocks.app.templates :as templates]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
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
        (is (str/includes? list "clause · attestation"))
        (is (str/includes? list "text and model only"))))
    (testing "Text: drafted from the sample, with the TemplateMark beneath"
      (let [text (page templates/text-page node "/templates/street-resurfacing-pay-application/text")]
        (is (str/includes? text "CONTRACTOR PAY REQUEST"))
        (is (str/includes? text "{{#ulist payItems}}"))))
    (testing "Model: its own namespace first"
      (let [model (page templates/model-page node "/templates/street-resurfacing-pay-application/model")]
        (is (< (str/index-of model "com.trustblocks.municipal.construction.payapplication@1.0.0")
               (str/index-of model "com.trustblocks.lifecycle@1.0.0")))))
    (testing "Code: lifecycle and clause; attestation authority; or none at all"
      (let [code (page templates/code-page node "/templates/street-resurfacing-pay-application/code")]
        (is (str/includes? code "PaymentOverdue"))
        (is (str/includes? code "30 days pass"))
        (is (str/includes? code "Not approved by the engineer")))
      (is (str/includes? (page templates/code-page node "/templates/manager-employment-contract/code")
                         "must hold CERTIFY_MINUTES"))
      (is (str/includes? (page templates/code-page node "/templates/street-resurfacing-contract/code")
                         "text and model only")))
    (is (= 404 (:status (templates/code-page {:biff.xtdb/node node :uri "/templates/nope/code"}))))))

(deftest the-contract-says-who-may-attest
  (with-open [node (xt.node/start-node)]
    (archive/ingest-dir! node (library/dir "manager-employment-contract"))
    (archive/ingest-dir! node (library/dir "street-resurfacing/contract"))
    (com.trustblocks.xtdb.store/put-instance!
     node nil (cheshire.core/parse-string (library/sample "manager-employment-contract")))
    (com.trustblocks.xtdb.store/put-instance!
     node nil (cheshire.core/parse-string (library/sample "street-resurfacing/contract")))
    (is (= "CERTIFY_MINUTES" (certify/required-authority node "china-grove-gover-2025")))
    (is (nil? (certify/required-authority node "china-grove-str-27")))))
