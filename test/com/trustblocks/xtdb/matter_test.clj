(ns com.trustblocks.xtdb.matter-test
  "A matter: documents joined to shared terms, advancing one state -- with
  everything execute/trigger! guarantees holding at the level of the matter."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.xtdb.document :as doc]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(def ^:private snow "com.trustblocks.snow@1.0.0.")

(def ^:private clause
  "(let [paid (if state (get state \"visitsPaid\") 0)]
     {:response {\"$class\" \"com.trustblocks.snow@1.0.0.VisitOutcome\"
                 \"outcomeId\" (get request \"reportId\") \"$identifier\" (get request \"reportId\")
                 \"$timestamp\" now \"outcome\" \"PAYABLE\"
                 \"amountDue\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\" \"amount\" 480.0 \"currency\" \"USD\"}}
      :state {\"$class\" \"com.trustblocks.snow@1.0.0.PaymentState\"
              \"contractId\" (get data \"contractId\") \"$identifier\" (get data \"contractId\")
              \"visitsPaid\" (+ paid 1)
              \"totalPaid\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\" \"amount\" (* 480.0 (+ paid 1)) \"currency\" \"USD\"}}})")

(defn- setup!
  "The snow-removal contract as terms, the WorkOrder model, and the clause."
  [node]
  (store/put-model! node "docs/examples/snow-removal.cto")
  (store/put-model! node "test/fixtures/matter-documents.cto")
  (execute/put-clause! node (str snow "SnowRemoval") clause)
  (store/put-instance! node nil {"$class" (str snow "SnowRemoval") "contractId" "snow-2026-014"
                                 "$identifier" "snow-2026-014" "contractor" "Glenwood Plowing LLC"
                                 "route" "Ward 3 arterials" "inchesTrigger" 2.0
                                 "ratePerVisit" {"$class" (str snow "Money") "amount" 480.0 "currency" "USD"}}))

(defn- work-order! [node id at]
  (store/put-instance! node nil {"$class" "test.matter@1.0.0.WorkOrder" "workOrderId" id
                                 "route" "Ward 3 arterials" "visitedAt" at "inchesFallen" 3.5}))

(defn- visit [id at]
  {"$class" (str snow "VisitReport") "reportId" id "$identifier" id
   "$timestamp" at "visitedAt" at "inchesFallen" 3.5})

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(deftest a-matter-is-advanced-by-its-documents
  (with-open [node (xt.node/start-node)]
    (setup! node)
    (matter/open! node {:id "winter-2026" :name "Ward 3 snow removal, winter 2026"
                        :package "snow-removal" :terms "snow-2026-014"
                        :opened-at "2026-01-01T00:00:00Z"})
    (doseq [[id at] [["wo-1" "2026-01-15T06:00:00Z"] ["wo-2" "2026-02-02T06:00:00Z"]]]
      (work-order! node id at)
      (matter/add-document! node "winter-2026" id :role "work-order" :at at)
      (matter/trigger! node "winter-2026" (visit id at) at :request-id id :document id))

    (testing "one state, advanced once per document"
      (is (= 2 (get (matter/state node "winter-2026") "visitsPaid")))
      (is (= 1 (get (matter/state node "winter-2026" :at "2026-01-20T00:00:00Z") "visitsPaid"))))

    (testing "the history names the document behind each execution"
      (is (= ["wo-1" "wo-2"] (mapv :document (matter/history node "winter-2026")))))

    (testing "membership is bitemporal"
      (is (= ["wo-1" "wo-2"] (mapv :xt/id (matter/documents node "winter-2026"))))
      (is (= ["wo-1"] (mapv :xt/id (matter/documents node "winter-2026" :at (Instant/parse "2026-01-20T00:00:00Z")))))
      (is (= "winter-2026" (matter/matter-of node "wo-2"))))

    (testing "execute/trigger!'s guarantees hold for the matter: a late document is out of order"
      (work-order! node "wo-0" "2026-01-10T06:00:00Z")
      (matter/add-document! node "winter-2026" "wo-0" :role "work-order" :at "2026-01-10T06:00:00Z")
      (is (= :out-of-order
             (refusal #(matter/trigger! node "winter-2026" (visit "wo-0" "2026-01-10T06:00:00Z")
                                        "2026-01-10T06:00:00Z" :request-id "wo-0" :document "wo-0")))))))

(deftest a-document-can-point-at-its-stored-file
  (with-open [node (xt.node/start-node)]
    (setup! node)
    (matter/open! node {:id "m" :terms "snow-2026-014" :opened-at "2026-01-01T00:00:00Z"})
    (work-order! node "wo-1" "2026-01-15T06:00:00Z")
    (let [root (str (Files/createTempDirectory "docs" (make-array FileAttribute 0)))
          {pdf :xt/id} (doc/store! node root (.getBytes "signed work order" "UTF-8") :media-type "application/pdf")]
      (is (= pdf (:stored-document (matter/add-document! node "m" "wo-1" :stored-document pdf
                                                         :at "2026-01-15T06:00:00Z")))))))

(deftest refusals
  (with-open [node (xt.node/start-node)]
    (setup! node)
    (store/put-instance! node nil {"$class" (str snow "SnowRemoval") "contractId" "snow-2026-015"
                                   "$identifier" "snow-2026-015" "contractor" "Glenwood Plowing LLC"
                                   "route" "Ward 4 collectors" "inchesTrigger" 2.0
                                   "ratePerVisit" {"$class" (str snow "Money") "amount" 480.0 "currency" "USD"}})
    (matter/open! node {:id "a" :terms "snow-2026-014" :opened-at "2026-01-01T00:00:00Z"})
    (matter/open! node {:id "b" :terms "snow-2026-015" :opened-at "2026-01-01T00:00:00Z"})
    (work-order! node "wo-1" "2026-01-15T06:00:00Z")
    (work-order! node "wo-2" "2026-01-16T06:00:00Z")
    (matter/add-document! node "a" "wo-1" :at "2026-01-15T06:00:00Z")
    (matter/add-document! node "b" "wo-2" :at "2026-01-16T06:00:00Z")
    (testing "opening"
      (is (= :no-such-instance (refusal #(matter/open! node {:id "c" :terms "nope" :opened-at "2026-01-01T00:00:00Z"}))))
      (is (= :matter-exists (refusal #(matter/open! node {:id "a" :terms "snow-2026-014" :opened-at "2026-01-01T00:00:00Z"}))))
      (is (= :terms-in-other-matter (refusal #(matter/open! node {:id "c" :terms "snow-2026-014" :opened-at "2026-01-01T00:00:00Z"})))))
    (testing "adding"
      (is (= :no-such-matter (refusal #(matter/add-document! node "nope" "wo-1" :at "2026-01-15T06:00:00Z"))))
      (is (= :no-such-instance (refusal #(matter/add-document! node "a" "nope" :at "2026-01-15T06:00:00Z"))))
      (is (= :no-such-stored-document (refusal #(matter/add-document! node "a" "wo-1" :stored-document "sha256:00" :at "2026-01-15T06:00:00Z"))))
      (is (= :document-in-other-matter (refusal #(matter/add-document! node "a" "wo-2" :at "2026-01-16T06:00:00Z")))))
    (testing "triggering with another matter's document, or before it joined"
      (is (= :document-not-in-matter
             (refusal #(matter/trigger! node "a" (visit "wo-2" "2026-01-16T06:00:00Z") "2026-01-16T06:00:00Z" :document "wo-2"))))
      (is (= :document-not-in-matter
             (refusal #(matter/trigger! node "a" (visit "wo-1" "2026-01-14T06:00:00Z") "2026-01-14T06:00:00Z" :document "wo-1"))))
      (is (= :no-such-matter (refusal #(matter/state node "nope")))))))
