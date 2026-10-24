(ns com.trustblocks.xtdb.advisory-test
  "The agent protocol: an AI agent has a DID of its own, holds no office, can
  be designated only to advise, and what it signs -- an Advisory -- moves
  nothing. Over the STR-27 demo matter."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.did.interop :as interop]
            [com.trustblocks.model.timeline :as timeline]
            [com.trustblocks.xtdb.advance :as advance]
            [com.trustblocks.xtdb.advisory :as advisory]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [street-resurfacing-matter-demo :as demo]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(def ^:private town "did:web:trustblocks.com:towns:china-grove")
(def ^:private from (Instant/parse "2026-09-01T00:00:00Z"))
(def ^:private until (Instant/parse "2027-06-30T00:00:00Z"))

(defn- setup!
  "The STR-27 matter; the Zoning Administrator holding ADVISE in her own
  right; and an agent she operates."
  [node]
  (demo/load! node (str (Files/createTempDirectory "agent" (make-array FileAttribute 0))))
  (let [za (identity/by-email node demo/barringer)]
    (cert/grant-office! node town {:officeholder-did (:xt/id za) :name "Teresa Barringer"
                                   :office "Zoning Administrator" :authority ["ADVISE"]
                                   :valid-from from :valid-until until})
    {:za    za
     :agent (identity/provision-agent! node {:name "Pay request assistant" :operator (:xt/id za)
                                             :purpose "Summarizes pay requests for the Zoning Administrator"})}))

(defn- designate! [node agent authority]
  (cert/designate! node {:designator-email demo/barringer :designee-did (:xt/id agent)
                         :name "Pay request assistant" :capacity "AI assistant to the Zoning Administrator"
                         :matter demo/matter-id :authority authority
                         :valid-from from :valid-until until}))

(deftest an-agent-is-an-identity-that-holds-no-office
  (with-open [node (xt.node/start-node)]
    (let [{:keys [za agent]} (setup! node)]
      (testing "its own DID and key, an operator, and no email"
        (is (identity/agent? agent))
        (is (= (:xt/id za) (:identity/operator agent)))
        (is (nil? (:identity/email agent))))
      (testing "an operator on file is required"
        (is (= :no-such-officeholder
               (refusal #(identity/provision-agent! node {:name "x" :operator "did:key:nobody"})))))
      (testing "the Town cannot grant it an office"
        (is (= :agent-cannot-hold-office
               (refusal #(cert/grant-office! node town {:officeholder-did (:xt/id agent) :name "x" :office "x"
                                                        :authority ["CERTIFY_PAYMENTS"]
                                                        :valid-from from :valid-until until}))))))))

(deftest an-agent-is-designated-only-to-advise
  (with-open [node (xt.node/start-node)]
    (let [{:keys [agent]} (setup! node)]
      (testing "not with an authority that decides anything -- even one the designator could delegate"
        (is (= :agent-authority-not-advisory
               (refusal #(designate! node agent ["RECEIVE_PAY_APPLICATIONS" "ADVISE"])))))
      (testing "only by someone holding ADVISE themselves"
        (is (= :missing-authority
               (refusal #(cert/designate! node {:designator-email demo/ketner :designee-did (:xt/id agent)
                                                :name "x" :capacity "x" :matter demo/matter-id
                                                :authority ["ADVISE"] :valid-from from :valid-until until})))))
      (testing "ADVISE, from the Zoning Administrator, for the matter"
        (is (string? (designate! node agent ["ADVISE"])))))))

(deftest what-an-agent-signs-is-advice
  (with-open [node (xt.node/start-node)]
    (let [{:keys [za agent]} (setup! node)
          designation (designate! node agent ["ADVISE"])
          at          (Instant/parse "2026-09-26T09:00:00Z")
          text        "Pay Request No. 3 arrived on the 25th; its milling to date is 96% of the contract quantity."
          jwt         (advisory/advise! node {:agent (:xt/id agent) :document "str-27-pay-3" :text text :at at})]
      (testing "signed by the agent, resting on its designation"
        (is (= (:xt/id agent) (:iss (interop/jws-claims jwt))))
        (is (= designation (get-in (interop/jws-claims jwt) [:vc "evidence"]))))
      (testing "it moves nothing"
        (is (= "RECEIVED" (get (execute/current-state node "str-27-pay-3") "status"))))
      (testing "on the document's timeline, and its matter's terms', marked as AI advice"
        (doseq [id ["str-27-pay-3" demo/contract-id]]
          (let [item (first (filter #(= :advisory (:kind %))
                                    (timeline/items node (store/registry-from-xtdb node) id (Instant/now))))]
            (is (= text (:title item)))
            (is (= "Pay request assistant (AI agent)" (:who item)))
            (is (re-find #"designated to advise by Teresa Barringer" (:detail item))))))
      (testing "refused: outside the matter, before the designation, by an officeholder"
        (is (= :missing-authority
               (refusal #(advisory/advise! node {:agent (:xt/id agent) :document "str-27-pay-1" :text "x"
                                                 :at (Instant/parse "2026-08-01T00:00:00Z")}))))
        (is (= :not-an-agent
               (refusal #(advisory/advise! node {:agent (:xt/id za) :document "str-27-pay-3" :text "x" :at at}))))
        (is (= :no-such-matter
               (refusal #(advisory/advise! node {:agent (:xt/id agent) :document demo/contract-id :text "x" :at at})))))
      (testing "and it can certify nothing: it has no way to act as an officeholder, and no authority to"
        (is (nil? (cert/authority-for node (:xt/id agent) "CERTIFY_PAY_APPLICATIONS" at :matter demo/matter-id)))
        (is (= :no-such-officeholder
               (refusal #(advance/advance! node {:email nil :document "str-27-pay-3" :credential "Attestation"
                                                 :event {"$class" "com.trustblocks.municipal.construction.payapplication@1.0.0.InspectionCertified"
                                                         "approvedPayItems" ["3.1"]}
                                                 :at at :statement "x"}))))))))

(deftest a-minimum-review-result-is-advice-too
  (with-open [node (xt.node/start-node)]
    (let [{:keys [agent]} (setup! node)
          _           (designate! node agent ["ADVISE"])
          at          (Instant/parse "2026-09-26T09:00:00Z")
          base        {:agent (:xt/id agent) :document "str-27-pay-3" :at at}]
      (let [jwt (advisory/advise! node (assoc base :text "The report is signed, dated and lists the quantities."
                                              :verdict "pass" :checklist ["Signed?" "Dated?" "Lists quantities?"]))
            vc  (:vc (interop/jws-claims jwt))]
        (is (= "pass" (get-in vc ["credentialSubject" "verdict"])))
        (is (= ["Signed?" "Dated?" "Lists quantities?"] (get-in vc ["credentialSubject" "checklist"]))))
      (is (= "RECEIVED" (get (execute/current-state node "str-27-pay-3") "status")) "a pass moves nothing")
      (is (= :invalid-advisory
             (refusal #(advisory/advise! node (assoc base :text "x" :verdict "approved"))))))))
