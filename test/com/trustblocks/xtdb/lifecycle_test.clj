(ns com.trustblocks.xtdb.lifecycle-test
  "The pay application's lifecycle, as its template declares it, enforced by
  execute/trigger!: each event only in the states it may occur in, each
  certified as the template requires, the timed one only once its days have
  passed -- and a clause, when there is one, held to the same transitions."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(def ^:private pay "com.trustblocks.municipal.construction.payapplication@1.0.0.")
(def ^:private root (str pay "ContractorPayRequest"))
(def ^:private id "str-27-pay-1")

(defn- event [name & {:as fields}] (merge {"$class" (str pay name)} fields))

(defn- by [credential authority]
  {:credential credential :authority authority :did "did:key:z6MkExample"})

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(defn- setup!
  "The pay application template, and a blank pay request -- with the
  template's clause removed, so these tests hold the lifecycle alone to
  what it declares; advance-test runs the clause."
  [node]
  (let [ingested (archive/ingest-dir! node (library/dir "street-resurfacing/pay-application"))]
    (xt/execute-tx node [[:delete-docs execute/clause-table root]])
    (store/put-instance! node nil (assoc (json/parse-string (library/sample "street-resurfacing/pay-application"))
                                         "payRequestId" id))
    ingested))

(defn- trigger! [node request at & [certification]]
  (execute/trigger! node id request at :certification certification))

(defn- status [node & [at]]
  (get (execute/current-state node id :at (some-> at execute/->instant)) "status"))

(deftest the-template-brings-its-lifecycle
  (with-open [node (xt.node/start-node)]
    (let [{:keys [lifecycle clause]} (setup! node)]
      (is (= root lifecycle))
      (is (= root clause))
      (is (= "ContractorPayRequestState" (get (execute/get-lifecycle node root) "stateType"))))))

(deftest a-pay-request-moves-only-as-its-lifecycle-allows
  (with-open [node (xt.node/start-node)]
    (setup! node)
    (testing "receipt must be certified, by the authority the template names"
      (is (= :certification-required (refusal #(trigger! node (event "PayRequestReceived") "2026-03-25T14:00:00Z"))))
      (is (= :certification-required
             (refusal #(trigger! node (event "PayRequestReceived") "2026-03-25T14:00:00Z"
                                 (by "Receipt" "RECEIVE_INSPECTION_REPORTS"))))))
    (testing "an event that cannot begin the lifecycle"
      (is (= :no-transition (refusal #(trigger! node (event "PaymentApproved") "2026-03-25T14:00:00Z"
                                                (by "Attestation" "APPROVE_PAYMENTS"))))))
    (let [{:keys [response]} (trigger! node (event "PayRequestReceived") "2026-03-25T14:00:00Z"
                                       (by "Receipt" "RECEIVE_PAY_APPLICATIONS"))]
      (is (= "RECEIVED" (status node)))
      (is (= {"event" "PayRequestReceived" "to" "RECEIVED"} (select-keys response ["event" "from" "to"]))))
    (testing "no skipping ahead"
      (is (= :no-transition (refusal #(trigger! node (event "PaymentApproved") "2026-03-26T14:00:00Z"
                                                (by "Attestation" "APPROVE_PAYMENTS"))))))
    (testing "either certification the template accepts will do"
      (trigger! node (event "InspectionCertified" "approvedPayItems" []) "2026-04-01T14:00:00Z"
                (by "Receipt" "RECEIVE_INSPECTION_REPORTS"))
      (is (= "INSPECTED" (status node))))
    (trigger! node (event "PayRequestCertified") "2026-04-02T14:00:00Z" (by "Attestation" "CERTIFY_PAY_APPLICATIONS"))
    (trigger! node (event "PaymentApproved") "2026-04-05T14:00:00Z" (by "Attestation" "APPROVE_PAYMENTS"))
    (testing "overdue only thirty days after approval, and certified by nobody"
      (is (= :too-early (refusal #(trigger! node (event "PaymentOverdue") "2026-04-20T00:00:00Z"))))
      (trigger! node (event "PaymentOverdue") "2026-05-05T14:00:00Z")
      (is (= "OVERDUE" (status node))))
    (trigger! node (event "PaymentCertified") "2026-05-07T14:00:00Z" (by "Attestation" "CERTIFY_PAYMENTS"))
    (testing "paid is final"
      (is (= "PAID" (status node)))
      (is (= :no-transition (refusal #(trigger! node (event "PayRequestReturned" "reason" "x") "2026-05-08T00:00:00Z"
                                                (by "Attestation" "CERTIFY_PAY_APPLICATIONS"))))))
    (testing "the record: each status as of its date, each execution with its certification"
      (is (= "INSPECTED" (status node "2026-04-01T20:00:00Z")))
      (is (= "APPROVED" (status node "2026-04-20T00:00:00Z")))
      (let [certs (mapv :certification (execute/history node id))]
        (is (= 6 (count certs)))
        (is (= {:credential "Receipt" :authority "RECEIVE_PAY_APPLICATIONS" :did "did:key:z6MkExample"}
               (first certs)))
        (is (= "RECEIVE_INSPECTION_REPORTS" (:authority (second certs))))
        (testing "and the timed event, certified by nobody"
          (is (nil? (nth certs 4))))))))

(deftest a-returned-request-is-finished
  (with-open [node (xt.node/start-node)]
    (setup! node)
    (trigger! node (event "PayRequestReceived") "2026-03-25T14:00:00Z" (by "Receipt" "RECEIVE_PAY_APPLICATIONS"))
    (trigger! node (event "PayRequestReturned" "reason" "Quantities exceed the engineer's certificate")
              "2026-03-28T14:00:00Z" (by "Attestation" "CERTIFY_PAY_APPLICATIONS"))
    (is (= "RETURNED" (status node)))
    (is (= :no-transition (refusal #(trigger! node (event "InspectionCertified" "approvedPayItems" []) "2026-03-29T14:00:00Z"
                                              (by "Attestation" "CERTIFY_INSPECTIONS")))))))

(deftest a-clause-is-held-to-the-lifecycle
  (with-open [node (xt.node/start-node)]
    (setup! node)
    (execute/put-clause! node root
                         (str "{:response {\"$class\" \"com.trustblocks.lifecycle@1.0.0.TransitionResponse\""
                              " \"$timestamp\" now \"event\" \"PayRequestReceived\" \"to\" \"PAID\"}"
                              " :state {\"$class\" \"" pay "ContractorPayRequestState\""
                              " \"payRequestId\" (get data \"payRequestId\") \"status\" \"PAID\"}}"))
    (is (= :invalid-clause-output
           (refusal #(trigger! node (event "PayRequestReceived") "2026-03-25T14:00:00Z"
                               (by "Receipt" "RECEIVE_PAY_APPLICATIONS")))))
    (is (nil? (status node)))))
