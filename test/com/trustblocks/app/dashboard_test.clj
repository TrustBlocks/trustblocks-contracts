(ns com.trustblocks.app.dashboard-test
  "The contract dashboard over the STR-27 demo matter: where a contract
  stands, as of any day; its timeline, with its matter's documents; and
  notes, with the day they are about and the day they were recorded."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.auth :as auth]
            [com.trustblocks.app.contracts :as contracts]
            [com.trustblocks.model.reads :as reads]
            [com.trustblocks.xtdb.note :as note]
            [street-resurfacing-matter-demo :as demo]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- loaded! [node]
  (demo/load! node (str (Files/createTempDirectory "dash" (make-array FileAttribute 0)))))

(defn- page [node id & [as-of]]
  (:body (contracts/show-page {:biff.xtdb/node node :uri (str "/contracts/" id)
                               :params (if as-of {:as-of as-of} {})})))

(deftest a-contracts-timeline-includes-its-matters-documents
  (with-open [node (xt.node/start-node)]
    (loaded! node)
    (let [items (reads/timeline-items {:biff.xtdb/node node} demo/contract-id)
          kinds (frequencies (map :kind items))]
      (testing "the pay requests' and the vendor form's certified steps, each with its document"
        (is (= 12 (:event kinds)))
        (is (some #(and (= "Payment certified" (:title %)) (= "str-27-pay-1" (:document %))) items)))
      (testing "the designation, and the dates the contract names"
        (is (= 1 (:designation kinds)))
        (is (some #(= "Notice to proceed date" (:title %)) items)))
      (testing "the deadlines: pay request 2's payment due 30 days after its Sept 3 approval"
        (is (some #(and (= :deadline (:kind %)) (= "str-27-pay-2" (:document %))
                        (= "Payment due" (:title %))
                        (= (Instant/parse "2026-10-03T15:00:00Z") (:at %)))
                  items))
        (testing "pay request 1's, met when it was paid"
          (is (some #(and (= :deadline (:kind %)) (= "str-27-pay-1" (:document %))
                          (= "Payment due -- met" (:title %)))
                    items)))
        (testing "the Completion Date: the Notice to Proceed's Commence Date + 270 days"
          (is (some #(and (= "Completion Date" (:title %)) (= "str-27-notice-to-proceed" (:document %))
                          (= (Instant/parse "2027-03-28T04:00:00Z") (:at %)))
                    items))))
      (testing "oldest first"
        (is (= (sort-by :at items) items))))))

(deftest the-dashboard-shows-where-it-stands-as-of-any-day
  (with-open [node (xt.node/start-node)]
    (loaded! node)
    (testing "today: approved, and what can happen next"
      (let [body (page node "str-27-pay-2")]
        (is (str/includes? body "Where it stands"))
        (is (str/includes? body ">APPROVED<"))
        (is (str/includes? body "PaymentCertified"))
        (is (str/includes? body "needs an Attestation under CERTIFY_PAYMENTS"))
        (is (not (str/includes? body "PaymentOverdue")))
        (testing "and the watch list: payment due, upcoming"
          (is (str/includes? body ">Deadlines<"))
          (is (re-find #"(?s)Oct 3, 2026.*?Upcoming.*?Payment due" body)))))
    (testing "as of Aug 26: only received"
      (let [body (page node "str-27-pay-2" "2026-08-26")]
        (is (str/includes? body "Where it stood on Aug 26, 2026"))
        (is (str/includes? body ">RECEIVED<"))
        (is (str/includes? body "InspectionCertified"))))
    (testing "the terms contract points to its matter"
      (is (str/includes? (page node demo/contract-id) "The terms of matter")))))

(deftest notes-keep-both-their-times
  (with-open [node (xt.node/start-node)]
    (loaded! node)
    (let [uid  (auth/create-user! {:biff.xtdb/node node} {:email demo/barringer})
          post (contracts/add-note {:biff.xtdb/node node :uri (str "/contracts/" demo/contract-id "/notes")
                                    :session {:uid uid}
                                    :params {:text "Two suppliers report unpaid invoices from the Contractor."
                                             :about "2026-08-15"}})
          [n]  (note/for-contract node demo/contract-id)]
      (is (= 303 (:status post)))
      (testing "about the day given; recorded now, by the database"
        (is (= (Instant/parse "2026-08-15T00:00:00Z") (:about n)))
        (is (< (Math/abs (- (inst-ms (:recorded n)) (System/currentTimeMillis))) 60000))
        (is (= demo/barringer (:author n))))
      (testing "on the timeline, marked as recorded after the day it is about"
        (let [body (page node demo/contract-id)]
          (is (str/includes? body "Two suppliers report unpaid invoices"))
          (is (str/includes? body "after the day it is about"))
          (is (str/includes? body "Teresa Barringer, Zoning Administrator")))))))
