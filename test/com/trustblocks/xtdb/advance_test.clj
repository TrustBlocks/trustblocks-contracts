(ns com.trustblocks.xtdb.advance-test
  "The STR-27 matter, end to end (dev/street_resurfacing_matter_demo.clj),
  and what advance! refuses: the wrong officeholder, the wrong step, a pay
  request certified with work the engineer has not approved, and one whose
  figures do not agree."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.interop :as interop]
            [com.trustblocks.xtdb.advance :as advance]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.receipt :as receipt]
            [com.trustblocks.xtdb.store :as store]
            [street-resurfacing-matter-demo :as demo]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(def ^:private pay "com.trustblocks.municipal.construction.payapplication@1.0.0.")

(defn- temp-root []
  (str (Files/createTempDirectory "advance" (make-array FileAttribute 0))))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(defn- refusal-message [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- status [node id] (get (execute/current-state node id) "status"))

(defn- event [name & {:as fields}] (merge {"$class" (str pay name)} fields))

(defn- advance! [node email id name credential when & {:as more}]
  (advance/advance! node (merge {:email email :document id :credential credential
                                 :event (event name) :at (Instant/parse when)
                                 :statement "x"}
                                more)))

(defn- another-request!
  "A copy of pay request 3 as `id`, received and inspected, the engineer
  approving `approved` -- with `changes` to its figures."
  [node root id approved changes]
  (let [request (merge (assoc (store/get-instance node "str-27-pay-3") "payRequestId" id) changes)
        stored  (document/store! node root (.getBytes (str "pay request " id)))
        cert    (document/store! node root (.getBytes (str "engineer's certificate " id)))]
    (store/put-instance! node nil request)
    (matter/add-document! node demo/matter-id id :role "pay-application"
                          :stored-document (:xt/id stored) :at "2026-09-25T00:00:00Z")
    (advance! node demo/barringer id "PayRequestReceived" "Receipt" "2026-09-25T16:00:00Z"
              :document-hash (:xt/id stored) :received-from "Example Paving, LLC")
    (advance/advance! node {:email demo/barringer :document id :credential "Receipt"
                            :event (event "InspectionCertified" "approvedPayItems" approved)
                            :at (Instant/parse "2026-09-26T09:00:00Z")
                            :document-hash (:xt/id cert) :received-from "the engineer"})
    id))

(deftest the-str-27-matter-end-to-end
  (with-open [node (xt.node/start-node)]
    (demo/load! node (temp-root))
    (testing "three pay requests, each where its lifecycle has brought it"
      (is (= ["PAID" "APPROVED" "RECEIVED"]
             (map #(status node (:xt/id %)) (matter/documents node demo/matter-id :role "pay-application")))))
    (testing "the engineer's approvals are the pay request's state"
      (is (= ["3.1" "5.1"] (get (execute/current-state node "str-27-pay-1") "approvedPayItems"))))
    (testing "the matter's record: the designation, a receipt for each file, an attestation for each act"
      (is (= {cert/designation-class 1 receipt/receipt-class 5 cert/attestation-class 5}
             (frequencies (map :concerto/class (credential/for-matter node demo/matter-id))))))
    (testing "the Zoning Administrator's receipts rest on the Director's designation"
      (let [designation (:jwt (first (filter #(= cert/designation-class (:concerto/class %))
                                             (credential/for-matter node demo/matter-id))))]
        (is (every? #(= designation (get-in (interop/jws-claims (get % "jwt")) [:vc "evidence"]))
                    (receipt/receipts-for-matter node demo/matter-id)))))
    (testing "each execution records who certified it, and how"
      (is (= [["Receipt" "RECEIVE_PAY_APPLICATIONS"] ["Receipt" "RECEIVE_INSPECTION_REPORTS"]
              ["Attestation" "CERTIFY_PAY_APPLICATIONS"] ["Attestation" "APPROVE_PAYMENTS"]
              ["Attestation" "CERTIFY_PAYMENTS"]]
             (map (comp (juxt :credential :authority) :certification)
                  (execute/history node "str-27-pay-1")))))))

(deftest advance-refuses
  (with-open [node (xt.node/start-node)]
    (let [root (temp-root)]
      (demo/load! node root)
      (testing "the wrong officeholder: the designee was given receiving, not certifying"
        (is (= :missing-authority
               (refusal #(advance! node demo/barringer "str-27-pay-3" "InspectionCertified" "Attestation"
                                   "2026-09-26T10:00:00Z")))))
      (testing "the Town Manager cannot certify payment"
        (is (= :missing-authority
               (refusal #(advance! node demo/gover "str-27-pay-2" "PaymentCertified" "Attestation"
                                   "2026-09-26T10:00:00Z")))))
      (testing "the wrong step"
        (is (= :no-transition
               (refusal #(advance! node demo/gover "str-27-pay-3" "PaymentApproved" "Attestation"
                                   "2026-09-26T10:00:00Z")))))
      (testing "work the engineer has not approved"
        (let [id (another-request! node root "str-27-pay-3a" ["5.1"] {})]
          (is (= "Not approved by the engineer: pay item 3.1; 7.1"
                 (refusal-message #(advance! node demo/ketner id "PayRequestCertified" "Attestation"
                                             "2026-09-26T11:00:00Z"))))
          (testing "and it is returned instead"
            (advance! node demo/ketner id "PayRequestReturned" "Attestation" "2026-09-26T12:00:00Z"
                      :event (event "PayRequestReturned" "reason" "Items 3.1 and 7.1 not approved"))
            (is (= "RETURNED" (status node id))))))
      (testing "figures that do not agree"
        (let [id (another-request! node root "str-27-pay-3b" ["3.1" "5.1" "7.1"]
                                   {"netAmountDue" 70000.0})]
          (is (re-find #"^The figures do not agree: the net amount due is \$70000, not \$69753.75"
                       (refusal-message #(advance! node demo/ketner id "PayRequestCertified" "Attestation"
                                                   "2026-09-26T11:00:00Z"))))
          (is (= "INSPECTED" (status node id))))))))
