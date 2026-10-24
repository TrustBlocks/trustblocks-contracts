(ns com.trustblocks.xtdb.release-test
  "Deploying: a release pins every clause to an exact, approved version and
  carries a SHA; a contract is deployed against it; and verification notices
  when a clause a contract depends on is no longer what was signed."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.templatemark.parse :as parse]
            [com.trustblocks.xtdb.clause-library :as lib]
            [com.trustblocks.xtdb.release :as rel]
            [com.trustblocks.xtdb.template-tree :as tt]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(defn- body [s] (:body (parse/parse s)))
(defn- doc [s] (parse/parse s))
(defn- inst [s] (java.time.Instant/parse s))
(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(defn- approved! [node id text & {:as more}]
  (let [c (lib/put-version! node (merge #:clause{:id id :body (body text)} more))]
    (lib/set-status! node id (:clause/version c) :approved)
    c))

(defn- library-with-templates [node]
  (approved! node "indemnity" "The Contractor indemnifies the Town.")
  (approved! node "indemnity" "The Contractor indemnifies and holds harmless the Town.")
  (approved! node "insurance" "Carry insurance. {{#clause indemnity v=2}}{{/clause}}")
  (tt/save! node "street"
            (doc "Contract. {{#clause indemnity}}{{/clause}} {{#clause insurance v=1}}{{/clause}} {{#clause sig}}Signed.{{/clause}}")))

(deftest a-release-pins-everything-and-says-so
  (with-open [node (xt.node/start-node)]
    (library-with-templates node)
    (let [r (rel/release! node "street" :by "clerk" :note "for the Spring bid")]
      (is (true? (:new? r)))
      (is (re-matches #"[0-9a-f]{64}" (:sha r)))
      (is (= "street" (:template-id r)))
      (is (= 1 (:template-rev r)))
      (testing "floating references became exact versions, nested ones included"
        (is (= [["indemnity" 2] ["insurance" 1]] (map (juxt :clause :version) (:manifest r)))))
      (testing "the release holds its own pinned copy of the template"
        (is (= [2 1] (map :pin (filter #(and (= :clause (:node %)) (nil? (:body %)))
                                       (tree-seq map? #(mapcat (fn [k] (get % k)) [:body]) (:tree r)))))))
      (testing "the same pinned content is the same release"
        (let [again (rel/release! node "street")]
          (is (false? (:new? again)))
          (is (= (:sha r) (:sha again))))))))

(deftest a-release-is-not-moved-by-the-library
  (with-open [node (xt.node/start-node)]
    (library-with-templates node)
    (let [r1 (rel/release! node "street")]
      (approved! node "indemnity" "A third, even stronger wording.")
      (let [r2 (rel/release! node "street")]
        (testing "a floating template releases differently now"
          (is (not= (:sha r1) (:sha r2)))
          (is (= [2 3] (map :version (filter #(= "indemnity" (:clause %)) (:manifest r2))))
              "indemnity 3 directly, and 2 because insurance 1 depends on it -- a release can hold both"))
        (testing "and the old release still holds, exactly as it was"
          (is (:ok? (rel/verify node (:sha r1))))
          (is (= [2] (map :version (filter #(= "indemnity" (:clause %)) (:manifest (rel/get-release node (:sha r1))))))))))))

(deftest only-approved-clauses-ship
  (with-open [node (xt.node/start-node)]
    (approved! node "indemnity" "Approved wording.")
    (lib/put-version! node #:clause{:id "draft-only" :body (body "Not yet approved.")})
    (tt/save! node "t" (doc "{{#clause indemnity}}{{/clause}} {{#clause draft-only v=1}}{{/clause}}"))
    (is (= :release-refused (tag #(rel/release! node "t"))))
    (is (re-find #"draft-only version 1 is draft, not approved"
                 (try (rel/release! node "t") (catch clojure.lang.ExceptionInfo e (ex-message e)))))
    (testing "unless it is asked for explicitly"
      (is (some? (rel/release! node "t" :allow-draft? true))))
    (testing "an unknown clause, or an unknown template"
      (tt/save! node "u" (doc "{{#clause nowhere v=1}}{{/clause}}"))
      (is (= :release-refused (tag #(rel/release! node "u"))))
      (is (= :no-such-template (tag #(rel/release! node "missing")))))))

(deftest verification-notices-a-clause-that-changed
  (with-open [node (xt.node/start-node)]
    (library-with-templates node)
    (let [r (rel/release! node "street")]
      (is (= {:ok? true :problems []} (rel/verify node (:sha r))))
      (testing "writing round the library to alter a pinned clause"
        (let [row (first (filter #(and (= "indemnity" (:clause-id %)) (= 2 (:version %)))
                                 (xt/q node '(from :clause-version [*]))))]
          (xt/execute-tx node [[:put-docs :clause-version (assoc row :body (pr-str (body "Quietly weakened.")))]]))
        (let [{:keys [ok? problems]} (rel/verify node (:sha r))]
          (is (false? ok?))
          (is (some #(and (= :clause-corrupt (:rule %)) (= "indemnity" (:clause %)) (= 2 (:version %))) problems))))
      (testing "and the other release facts"
        (is (= :no-such-release (-> (rel/verify node "deadbeef") :problems first :rule)))))))

(deftest verification-notices-a-forged-release
  (with-open [node (xt.node/start-node)]
    (library-with-templates node)
    (let [r (rel/release! node "street")
          row (first (xt/q node '(from :release [*])))]
      (testing "a manifest edited to hide a change"
        (xt/execute-tx node [[:put-docs :release (assoc row :manifest (pr-str (mapv #(assoc % :version 1) (:manifest r))))]])
        (is (some #(= :sha-mismatch (:rule %)) (:problems (rel/verify node (:sha r))))))
      (testing "a pinned template edited to say something else"
        (xt/execute-tx node [[:put-docs :release (assoc row :tree (pr-str (doc "Something else entirely.")))]])
        (is (some #(= :template-changed (:rule %)) (:problems (rel/verify node (:sha r)))))))))

(deftest a-contract-is-deployed-against-a-release
  (with-open [node (xt.node/start-node)]
    (library-with-templates node)
    (let [r1 (rel/release! node "street")]
      (rel/deploy! node "str-27" (:sha r1) :valid-from (inst "2026-03-01T00:00:00Z") :by "manager")
      (is (= (:sha r1) (rel/release-of node "str-27")))
      (is (nil? (rel/release-of node "str-27" :at (inst "2026-02-01T00:00:00Z"))) "not yet in force")
      (is (nil? (rel/release-of node "other-contract")))
      (testing "an amendment moves it to a newer release; both stay in history"
        (approved! node "indemnity" "Amended wording.")
        (let [r2 (rel/release! node "street")]
          (rel/deploy! node "str-27" (:sha r2) :valid-from (inst "2026-09-01T00:00:00Z") :note "amendment 1")
          (is (= (:sha r2) (rel/release-of node "str-27")))
          (is (= (:sha r1) (rel/release-of node "str-27" :at (inst "2026-06-01T00:00:00Z")))
              "what applied in June is still answerable")))
      (testing "a release that does not exist, or no longer holds, cannot be deployed against"
        (is (= :deploy-refused (tag #(rel/deploy! node "x" "deadbeef"))))
        (let [row (first (filter #(and (= "indemnity" (:clause-id %)) (= 2 (:version %))) (xt/q node '(from :clause-version [*]))))]
          (xt/execute-tx node [[:put-docs :clause-version (assoc row :body (pr-str (body "Altered.")))]])
          (is (= :deploy-refused (tag #(rel/deploy! node "new-contract" (:sha r1))))))))))

(deftest which-deployed-contracts-contain-a-clause
  (with-open [node (xt.node/start-node)]
    (library-with-templates node)
    (let [r1 (rel/release! node "street")]
      (approved! node "indemnity" "Third wording.")
      (let [r2 (rel/release! node "street")]
        (rel/deploy! node "c1" (:sha r1) :valid-from (inst "2026-01-01T00:00:00Z"))
        (rel/deploy! node "c2" (:sha r1) :valid-from (inst "2026-01-01T00:00:00Z"))
        (rel/deploy! node "c3" (:sha r2) :valid-from (inst "2026-01-01T00:00:00Z"))
        (is (= ["c1" "c2" "c3"] (map :contract (rel/deployed-with node "indemnity" :version 2)))
            "c3 also holds version 2, through insurance")
        (is (= ["c3"] (map :contract (rel/deployed-with node "indemnity" :version 3))))
        (is (= ["c1" "c2" "c3"] (distinct (map :contract (rel/deployed-with node "indemnity")))))
        (is (= [["c3" 2] ["c3" 3]] (map (juxt :contract :version) (filter #(= "c3" (:contract %)) (rel/deployed-with node "indemnity")))))
        (is (= ["c1" "c2" "c3"] (map :contract (rel/deployed-with node "insurance"))))
        (is (empty? (rel/deployed-with node "indemnity" :at (inst "2025-06-01T00:00:00Z"))) "nothing was deployed then")
        (is (empty? (rel/deployed-with node "nothing")))))))

(deftest checking-everything-in-force
  (with-open [node (xt.node/start-node)]
    (library-with-templates node)
    (let [r (rel/release! node "street")]
      (rel/deploy! node "c1" (:sha r))
      (rel/deploy! node "c2" (:sha r))
      (is (= {:ok? true :checked 1 :failures []} (rel/check-active node)))
      (let [row (first (filter #(and (= "insurance" (:clause-id %)) (= 1 (:version %))) (xt/q node '(from :clause-version [*]))))]
        (xt/execute-tx node [[:put-docs :clause-version (assoc row :body (pr-str (body "Tampered.")))]]))
      (let [{:keys [ok? checked failures]} (rel/check-active node)]
        (is (false? ok?))
        (is (= 1 checked))
        (is (= [["c1" "c2"]] (map :contracts failures)) "and it says which contracts rest on the damaged release")))))

(deftest a-clause-can-bring-its-data-into-the-release
  (with-open [node (xt.node/start-node)]
    (approved! node "insurance" "Carry {{minimumLimit as \"0,0.00\"}}."
               :clause/model "concept Insurance { o Double minimumLimit }")
    (tt/save! node "t" (doc "Fee {{fee}}. {{#clause insurance in=coverage}}{{/clause}}"))
    (let [base "namespace town.contract@1.0.0\nconcept Agreement { o Double fee }"
          r  (rel/release! node "t" :compose {:base base :root "Agreement"})
          r0 (rel/release! node "t" :compose {:base "namespace town.contract@1.0.0\nconcept Agreement { o Double fee o Integer extra optional }" :root "Agreement"})]
      (testing "the composed model is part of the release"
        (is (some? (:model r)))
        (is (re-matches #"[0-9a-f]{64}" (:model-hash r)))
        (is (= ["fee" "coverage"] (map :name (:properties (first (filter #(= "Agreement" (:name %)) (:declarations (:model r)))))))))
      (testing "a different model is a different release, with everything else the same"
        (is (not= (:sha r) (:sha r0))))
      (is (:ok? (rel/verify node (:sha r))))
      (testing "a stored model altered afterwards is noticed"
        (let [row (first (filter #(= (str "sha256:" (:sha r)) (:xt/id %)) (xt/q node '(from :release [*]))))]
          (xt/execute-tx node [[:put-docs :release (assoc row :model (pr-str {:namespace "tampered"}))]])
          (is (some #(= :model-changed (:rule %)) (:problems (rel/verify node (:sha r))))))))
    (testing "a template the model cannot support is refused"
      (tt/save! node "bad" (doc "{{ratee}}"))
      (is (= :release-refused (tag #(rel/release! node "bad" :compose {:base "namespace town.contract@1.0.0\nconcept Agreement { o Double fee }" :root "Agreement"})))))))
