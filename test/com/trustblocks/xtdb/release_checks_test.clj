(ns com.trustblocks.xtdb.release-checks-test
  "What a release checks beyond the clauses: the model the template was written
  against, the models it pins, whether the text states what the lifecycle
  requires, and attesting to a release."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.templatemark.parse :as parse]
            [com.trustblocks.xtdb.clause-library :as lib]
            [com.trustblocks.xtdb.model-repository :as repo]
            [com.trustblocks.xtdb.release :as rel]
            [com.trustblocks.xtdb.template-tree :as tt]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(def m "org.example.m@1.0.0")
(def cto "namespace org.example.m@1.0.0
concept Base { o String town }
concept Contract extends Base { o Double fee }
")
(def other "namespace org.example.other@1.0.0
concept Thing { o String town }
")

(defn- doc [s] (parse/parse s))
(defn- body [s] (:body (parse/parse s)))
(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))
(defn- rules [e] (set (map :rule (:problems (ex-data e)))))
(defn- refusal [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(defn- approved! [node id text & {:as more}]
  (let [c (lib/put-version! node (merge #:clause{:id id :body (body text)} more))]
    (lib/set-status! node id (:clause/version c) :approved)
    c))

(deftest a-release-holds-a-template-to-its-model-and-pins-the-models
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node cto)
    (approved! node "recitals" "The {{town}} pays {{fee}}." :clause/requires {:model m :root "Contract"})
    (tt/save! node "good" (doc "{{town}}. {{#clause recitals}}{{/clause}}") :model m :root "Contract"
              :library (lib/lookup node))
    (tt/save! node "plain" (doc "{{#clause recitals}}{{/clause}}"))
    (let [r (rel/release! node "good")]
      (is (= {:model m :root "Contract" :pins {m (:model/hash (repo/get-model node m))}} (:pinned r)))
      (is (re-matches #"[0-9a-f]{64}" (:pins-hash r)))
      (is (:ok? (rel/verify node (:sha r))))
      (testing "the models are part of the sha"
        (is (not= (:sha r) (:sha (rel/release! node "plain")))))
      (testing "a pinned model altered afterwards is noticed"
        (xt/execute-tx node [[:patch-docs :model-version {:xt/id m :cto "namespace org.example.m@1.0.0\nconcept Base { o String x }\n"}]])
        (let [{:keys [ok? problems]} (rel/verify node (:sha r))]
          (is (not ok?))
          (is (contains? (set (map :rule problems)) :model-corrupt))))
      (testing "and so is a release whose own pins were touched"
        (xt/execute-tx node [[:patch-docs :model-version {:xt/id m :cto cto}]])
        (is (:ok? (rel/verify node (:sha r))))
        (xt/execute-tx node [[:patch-docs :release {:xt/id (str "sha256:" (:sha r))
                                                    :pins (pr-str {m "0000"})}]])
        (is (contains? (set (map :rule (:problems (rel/verify node (:sha r))))) :pins-altered))))))

(deftest a-template-that-does-not-fit-its-model-is-not-released
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node cto)
    (let [saved (tt/save! node "wrong" (doc "{{town}} {{nosuch}}") :model m :root "Contract")]
      (is (= [:unknown-property] (map :rule (:problems saved))) "a draft is saved, with its problems"))
    (let [e (refusal #(rel/release! node "wrong"))]
      (is (= :release-refused (:trustblocks/error (ex-data e))))
      (is (= #{:template-does-not-fit-model} (rules e))))
    (testing "a clause that requires a model the template is not written against"
      (repo/put-model! node other)
      (approved! node "thing" "{{town}}" :clause/requires {:model "org.example.other@1.0.0" :root "Thing"})
      (tt/save! node "mixed" (doc "{{#clause thing}}{{/clause}}") :model m :root "Contract" :library (lib/lookup node))
      (is (= #{:clause-model-not-pinned} (rules (refusal #(rel/release! node "mixed"))))))))

(def lifecycle
  {"transitions" [{"event" "A1" "from" [] "to" "S1"
                   "requires" [{"credential" "Attestation" "authority" "AUTH1"}]}
                  {"event" "R1" "from" ["S1"] "to" "S2"
                   "requires" [{"credential" "Receipt" "authority" "AUTH2"}]}]})

(deftest a-release-says-which-required-certifications-its-text-does-not-state
  (with-open [node (xt.node/start-node)]
    (approved! node "approval" "This takes effect when the Board certifies it."
               :clause/states [{:event "A1" :authority "AUTH1"}])
    (tt/save! node "t" (doc "Agreement. {{#clause approval}}{{/clause}}"))
    (testing "the clause states the attestation; the receipt is left unsaid"
      (let [r (rel/release! node "t" :lifecycle lifecycle)]
        (is (= [[:receipt-unstated "R1" "AUTH2"]] (map (juxt :rule :event :authority) (:findings r))))
        (is (= (:findings r) (rel/findings node (:sha r) lifecycle)))))
    (testing "the template may say so for its own text; that is kept with the release"
      (let [r (rel/release! node "t" :lifecycle lifecycle :states [{:event "R1"}])]
        (is (= [] (:findings r)))
        (is (= [{:event "R1"}] (:states (rel/get-release node (:sha r)))))
        (is (= [] (rel/findings node (:sha r) lifecycle)))
        (xt/execute-tx node [[:patch-docs :release {:xt/id (str "sha256:" (:sha r)) :states (pr-str [{:event "A1"}])}]])
        (is (contains? (set (map :rule (:problems (rel/verify node (:sha r))))) :states-altered))))
    (testing "strict, a gap refuses the release"
      (tt/save! node "t2" (doc "Agreement with nothing stated."))
      (is (= #{:attestation-unstated :receipt-unstated}
             (rules (refusal #(rel/release! node "t2" :lifecycle lifecycle :require-stated? true)))))
      (is (= 2 (count (:findings (rel/release! node "t2" :lifecycle lifecycle)))) "otherwise it only reports"))
    (testing "an authority left out states the event for any authority"
      (approved! node "any" "Any." :clause/states [{:event "A1"} {:event "R1"}])
      (tt/save! node "t3" (doc "{{#clause any}}{{/clause}}"))
      (is (= [] (:findings (rel/release! node "t3" :lifecycle lifecycle)))))
    (is (= :invalid-clause (tag #(lib/put-version! node #:clause{:id "bad" :body (body "x") :states ["A1"]}))))))

(deftest an-attestation-can-be-about-a-release
  (with-open [node (xt.node/start-node)]
    (approved! node "c" "Clause.")
    (tt/save! node "t" (doc "{{#clause c}}{{/clause}}"))
    (let [r (rel/release! node "t")]
      (is (nil? (rel/attestation-subject node "contract-1")) "not deployed, nothing to attest to")
      (rel/deploy! node "contract-1" (:sha r))
      (let [subject (rel/attestation-subject node "contract-1")]
        (is (= (str "sha256:" (:sha r)) subject))
        (is (:ok? (rel/verify-attested node subject)))
        (is (= (:sha r) (:release (rel/verify-attested node subject)))))
      (testing "what was attested to is no longer what was released"
        (xt/execute-tx node [[:patch-docs :clause-version {:xt/id "c@1" :body (pr-str [{:node :text :text "Changed."}])}]])
        (is (not (:ok? (rel/verify-attested node (str "sha256:" (:sha r)))))))
      (testing "a reference to anything else is not verified here"
        (is (nil? (:ok? (rel/verify-attested node "STR-27/pay-1.pdf"))))))))
