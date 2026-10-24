(ns com.trustblocks.xtdb.model-use-test
  "Clauses and templates held to a pinned model, and the questions that
  follows from knowing which properties they read."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.templatemark.parse :as parse]
            [com.trustblocks.xtdb.clause-library :as lib]
            [com.trustblocks.xtdb.impact :as impact]
            [com.trustblocks.xtdb.model-repository :as repo]
            [com.trustblocks.xtdb.model-use :as use]
            [com.trustblocks.xtdb.template-tree :as templates]
            [xtdb.node :as xt.node]))

(def m "org.example.m@1.0.0")
(def cto "namespace org.example.m@1.0.0
concept Base { o String town o String county optional }
concept Contract extends Base { o Double fee o String note optional }
")

(defn- body [s] (:body (parse/parse s)))
(defn- doc [s] (parse/parse s))
(defn- inst [s] (java.time.Instant/parse s))
(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))
(def requires {:model m :root "Contract"})

(defn- clause [id text & {:as more}]
  (merge #:clause{:id id :title id :body (body text) :requires requires} more))

(defn- fields [node kind owner] (map :field (use/reads-of node kind owner)))

(deftest a-clause-is-held-to-the-model-it-requires
  (with-open [node (xt.node/start-node)]
    (testing "the model has to be in the repository"
      (is (= :model-missing (tag #(lib/put-version! node (clause "recitals" "{{town}}"))))))
    (repo/put-model! node cto)
    (let [v1 (lib/put-version! node (clause "recitals" "The {{town}} pays {{fee}}."))]
      (is (= requires (:clause/requires v1)))
      (is (= requires (:clause/requires (lib/get-version node "recitals" 1))) "kept, and part of the hash")
      (is (= ["org.example.m.Base.town" "org.example.m.Contract.fee"] (fields node :clause "recitals@1"))
          "a property is named by the type that declares it")
      (testing "a placeholder the model does not have is refused, nothing stored"
        (is (= :clause-does-not-fit-model (tag #(lib/put-version! node (clause "bad" "{{nosuch}}")))))
        (is (nil? (lib/latest node "bad"))))
      (testing "a clause that lists what it reads may not read more"
        (is (= :undeclared-read (tag #(lib/put-version! node (clause "tight" "{{town}} {{fee}}"
                                                                     :clause/requires (assoc requires :fields ["town"]))))))
        (is (some? (lib/put-version! node (clause "tight" "{{town}} {{fee}}"
                                                  :clause/requires (assoc requires :fields ["town" "fee"]))))))
      (testing "a clause that names no model is as before"
        (let [free (lib/put-version! node {:clause/id "free" :clause/title "free" :clause/body (body "{{whatever}}")})]
          (is (nil? (:clause/requires free)))
          (is (= [] (fields node :clause "free@1")))))
      (testing "requiring a model is a different clause from not"
        (is (not= (:clause/hash v1)
                  (:clause/hash (lib/put-version! node {:clause/id "other" :clause/title "o" :clause/body (body "The {{town}} pays {{fee}}.")}))))))))

(deftest templates-record-what-they-read-and-who-reads-a-property
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node cto)
    (lib/put-version! node (clause "recitals" "The {{town}} pays {{fee}}.") :valid-from (inst "2025-12-01T00:00:00Z"))
    (let [library (lib/lookup node :statuses #{:draft :approved})
          r1 (templates/save! node "t1" (doc "{{town}} {{#clause recitals v=1}}{{/clause}}")
                              :model m :root "Contract" :library library :valid-from (inst "2026-01-01T00:00:00Z"))]
      (is (= [] (:problems r1)))
      (is (= {:model m :root "Contract"} (select-keys (templates/head node "t1") [:model :root])))
      (is (= ["org.example.m.Base.town" "org.example.m.Contract.fee"] (fields node :template "t1"))
          "its own text and the clause it pulls in")
      (testing "who reads a property"
        (is (= #{[:clause "recitals@1"] [:template "t1"]}
               (set (map (juxt :kind :owner) (use/readers node "org.example.m.Base.town")))) "by field, any version")
        (is (= 2 (count (use/readers node "org.example.m@1.0.0.Base.town"))) "or by one version's property")
        (is (= [] (use/readers node "org.example.m.Base.county"))))
      (testing "a property change reaches the clauses that read it, and the templates that use them"
        (is (= {:clauses   [{:clause "recitals" :version 1}]
                :templates [{:template "t1" :via nil} {:template "t1" :via ["recitals" 1]}]}
               (impact/impact node "org.example.m.Base.town")))
        (is (= {:clauses [{:clause "recitals" :version 1}] :templates [{:template "t1" :via ["recitals" 1]}]}
               (impact/impact node "org.example.m.Contract.fee"))))
      (testing "a later save replaces what the template reads; the past is still asked"
        (templates/save! node "t1" (doc "Nothing here.") :based-on 1 :valid-from (inst "2026-02-01T00:00:00Z"))
        (is (= [] (fields node :template "t1")))
        (is (= ["org.example.m.Base.town" "org.example.m.Contract.fee"]
               (map :field (use/reads-of node :template "t1" :at (inst "2026-01-15T00:00:00Z")))))
        (is (= #{[:clause "recitals@1"]} (set (map (juxt :kind :owner) (use/readers node "org.example.m.Base.town")))))
        (is (= 2 (count (use/readers node "org.example.m.Base.town" :at (inst "2026-01-15T00:00:00Z"))))))
      (testing "the model stays with the template unless told otherwise"
        (is (= m (:model (templates/head node "t1"))))))))

(deftest a-draft-may-be-wrong-but-says-so
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node cto)
    (let [r (templates/save! node "t2" (doc "{{town}} {{nosuch}}") :model m :root "Contract")]
      (is (true? (:changed? r)) "saved")
      (is (= [:unknown-property] (map :rule (:problems r))))
      (is (= ["org.example.m.Base.town"] (fields node :template "t2")) "what resolves is recorded"))
    (testing "a template cannot name a model that is not stored, or a type it lacks"
      (is (= :model-missing (tag #(templates/save! node "t3" (doc "x") :model "org.example.none@1.0.0" :root "A"))))
      (is (= :invalid-template (tag #(templates/save! node "t3" (doc "x") :model m :root "Nope")))))))

(deftest what-the-model-offers-that-nothing-reads
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node cto)
    (is (= ["org.example.m.Base.county" "org.example.m.Base.town" "org.example.m.Contract.fee" "org.example.m.Contract.note"]
           (impact/unused-properties node m)))
    (templates/save! node "t1" (doc "{{town}} {{fee}}") :model m :root "Contract")
    (is (= ["org.example.m.Base.county" "org.example.m.Contract.note"] (impact/unused-properties node m)))
    (testing "models that import a model"
      (repo/put-model! node "namespace org.example.n@1.0.0\nimport org.example.m@1.0.0.{Contract}\nconcept Z { o Contract c }\n")
      (is (= ["org.example.n@1.0.0"] (repo/importers node m))))))
