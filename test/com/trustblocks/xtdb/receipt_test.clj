(ns com.trustblocks.xtdb.receipt-test
  "Receipts and designations: a Director designates an officeholder to
  receive documents on one matter; the designee's receipts are signed,
  name the stored bytes, and rest on the credential that allowed them.
  Generic names here; real officeholders belong in the seed."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.interop :as interop]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.document :as doc]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.receipt :as receipt]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(def ^:private snow "com.trustblocks.snow@1.0.0.")
(defn- at [s] (Instant/parse s))
(def ^:private year-start (at "2026-01-01T00:00:00Z"))
(def ^:private year-end (at "2027-01-01T00:00:00Z"))
(def ^:private director "director@town.example.gov")
(def ^:private designee "zoning@town.example.gov")

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(defn- temp-root []
  (str (Files/createTempDirectory "receipts" (make-array FileAttribute 0))))

(defn- setup!
  "Two matters on two snow contracts; a Director who holds the receiving
  authorities; an officeholder who holds none; one stored pay application."
  [node root]
  (store/put-model! node "docs/examples/snow-removal.cto")
  (store/put-model! node "model/identity.cto")
  (doseq [id ["snow-a" "snow-b"]]
    (store/put-instance! node nil {"$class" (str snow "SnowRemoval") "contractId" id
                                   "$identifier" id "contractor" "Example Plowing LLC"
                                   "route" "Ward 3" "inchesTrigger" 2.0
                                   "ratePerVisit" {"$class" (str snow "Money") "amount" 480.0 "currency" "USD"}})
    (matter/open! node {:id (str "matter-" id) :terms id :opened-at year-start}))
  (let [town (identity/provision-town! node {:slug "town" :name "Town"})
        dir  (identity/provision-officeholder! node {:email director :name "A Director"
                                                     :office "Director of Public Works"})
        zon  (identity/provision-officeholder! node {:email designee :name "A Designee"
                                                     :office "Zoning Administrator"})
        grant (cert/grant-office! node (:xt/id town)
                                  {:officeholder-did (:xt/id dir) :name "A Director"
                                   :office "Director of Public Works"
                                   :authority ["RECEIVE_PAY_APPLICATIONS" "RECEIVE_INSPECTION_REPORTS"]
                                   :valid-from year-start :valid-until year-end})
        stored (doc/store! node root (.getBytes "Pay Application No. 1") :filename "pay-app-1.pdf")]
    {:director dir :designee zon :grant grant :pay-app (:xt/id stored)}))

(defn- designate! [node {:keys [designee]} & {:keys [matter authority from until]
                                              :or {matter "matter-snow-a"
                                                   authority ["RECEIVE_PAY_APPLICATIONS"]
                                                   from (at "2026-03-01T00:00:00Z")
                                                   until (at "2026-10-01T00:00:00Z")}}]
  (cert/designate! node {:designator-email director :designee-did (:xt/id designee)
                         :name "A Designee" :capacity "Designated Representative of the Director"
                         :matter matter :authority authority
                         :valid-from from :valid-until until}))

(defn- receive! [node pay-app & {:keys [email matter when authority]
                                 :or {email designee matter "matter-snow-a"
                                      when (at "2026-04-10T15:00:00Z")
                                      authority "RECEIVE_PAY_APPLICATIONS"}}]
  (receipt/receive! node {:email email :matter matter :document-hash pay-app
                          :received-from "Example Plowing LLC" :description "Pay Application No. 1"
                          :required-authority authority :received-at when}))

(deftest the-director-receives-under-their-own-grant
  (with-open [node (xt.node/start-node)]
    (let [{:keys [pay-app grant]} (setup! node (temp-root))
          jwt (receive! node pay-app :email director)
          [r] (receipt/receipts-for-matter node "matter-snow-a")]
      (is (string? jwt))
      (is (= pay-app (get r "documentHash")))
      (is (= "Example Plowing LLC" (get r "receivedFrom")))
      (is (= (at "2026-04-10T15:00:00Z") (get r "received-at")))
      (testing "the grant that allowed it is its evidence"
        (is (= grant (get-in (interop/jws-claims jwt) [:vc "evidence"])))))))

(deftest a-designee-receives-on-their-matter-only
  (with-open [node (xt.node/start-node)]
    (let [{:keys [pay-app] :as people} (setup! node (temp-root))]
      (testing "without a designation, the Zoning Administrator cannot receive"
        (is (= :missing-authority (refusal #(receive! node pay-app)))))
      (let [designation (designate! node people)]
        (testing "with one, they can -- and the designation is the evidence"
          (let [jwt (receive! node pay-app)]
            (is (= designation (get-in (interop/jws-claims jwt) [:vc "evidence"])))))
        (testing "the designation is on record against the matter"
          (is (= [cert/designation-class receipt/receipt-class]
                 (map :concerto/class (credential/for-matter node "matter-snow-a")))))
        (testing "but not on another matter"
          (is (= :missing-authority (refusal #(receive! node pay-app :matter "matter-snow-b")))))
        (testing "nor for an authority it does not name"
          (is (= :missing-authority (refusal #(receive! node pay-app
                                                        :authority "RECEIVE_INSPECTION_REPORTS")))))))))

(deftest outside-the-window-is-refused
  (with-open [node (xt.node/start-node)]
    (let [{:keys [pay-app] :as people} (setup! node (temp-root))]
      (designate! node people)
      (is (= :missing-authority (refusal #(receive! node pay-app :when (at "2026-02-01T00:00:00Z")))))
      (is (= :missing-authority (refusal #(receive! node pay-app :when (at "2026-11-01T00:00:00Z"))))))))

(deftest a-designator-can-delegate-only-what-they-hold
  (with-open [node (xt.node/start-node)]
    (let [people (setup! node (temp-root))]
      (is (= :missing-authority
             (refusal #(designate! node people :authority ["RECEIVE_PAY_APPLICATIONS" "APPROVE_PAYMENTS"]))))
      (is (= :missing-authority (refusal #(designate! node people :authority []))))
      (is (= :no-such-matter (refusal #(designate! node people :matter "no-such-matter"))))
      (testing "and a designee cannot pass it on"
        (designate! node people)
        (is (= :missing-authority
               (refusal #(cert/designate! node {:designator-email designee
                                                :designee-did (:xt/id (:director people))
                                                :name "x" :capacity "x" :matter "matter-snow-a"
                                                :authority ["RECEIVE_PAY_APPLICATIONS"]
                                                :valid-from (at "2026-04-01T00:00:00Z")
                                                :valid-until (at "2026-05-01T00:00:00Z")}))))))))

(deftest revoking-the-designator-ends-later-receipts-but-earlier-ones-stand
  (with-open [node (xt.node/start-node)]
    (let [root (temp-root)
          {:keys [pay-app director] :as people} (setup! node root)
          later (:xt/id (doc/store! node root (.getBytes "Pay Application No. 2")))]
      (designate! node people)
      (receive! node pay-app)
      (credential/revoke! node (:xt/id (first (credential/for-subject node (:xt/id director))))
                          (at "2026-05-01T00:00:00Z"))
      (is (= :missing-authority (refusal #(receive! node later :when (at "2026-05-10T00:00:00Z")))))
      (is (= [pay-app] (map #(get % "documentHash") (receipt/receipts-for-matter node "matter-snow-a"))))
      (testing "a receipt dated before the revocation is still allowed"
        (receive! node later :when (at "2026-04-20T00:00:00Z"))
        (is (= 2 (count (receipt/receipts-for-matter node "matter-snow-a"))))))))

(deftest receipt-refusals
  (with-open [node (xt.node/start-node)]
    (let [{:keys [pay-app]} (setup! node (temp-root))]
      (receive! node pay-app :email director)
      (is (= :already-received (refusal #(receive! node pay-app :email director))))
      (testing "the same document on another matter is its own receipt"
        (is (string? (receive! node pay-app :email director :matter "matter-snow-b"))))
      (is (= :no-such-stored-document
             (refusal #(receive! node (str "sha256:" (apply str (repeat 64 "0"))) :email director))))
      (is (= :no-such-matter (refusal #(receive! node pay-app :email director :matter "nope"))))
      (is (= :no-such-officeholder (refusal #(receive! node pay-app :email "nobody@example.gov")))))))
