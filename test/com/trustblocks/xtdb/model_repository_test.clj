(ns com.trustblocks.xtdb.model-repository-test
  "The model repository: models stored once per namespace@version, never
  changed, imported from the templates' own model directories, and pinned."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.xtdb.model-repository :as repo]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(def base "namespace org.example.base@1.0.0\nconcept Party { o String name }\n")
(def town "namespace org.example.town@1.0.0\nimport org.example.base@1.0.0.{Party}\nconcept Town extends Party { o String county }\n")

(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(deftest a-model-version-is-written-once
  (with-open [node (xt.node/start-node)]
    (let [m (repo/put-model! node base :by "tom" :note "first")]
      (is (= "org.example.base@1.0.0" (:model/id m)))
      (is (= "org.example.base" (:model/name m)))
      (is (= "1.0.0" (:model/version m)))
      (is (re-matches #"[0-9a-f]{64}" (:model/hash m)))
      (is (= m (repo/get-model node "org.example.base@1.0.0")))
      (testing "the same model, laid out differently, is not a change"
        (is (= m (repo/put-model! node "namespace   org.example.base@1.0.0\n\nconcept Party {\n  o String name\n}\n"))))
      (testing "different content under the same version is refused"
        (is (= :model-version-immutable
               (tag #(repo/put-model! node "namespace org.example.base@1.0.0\nconcept Party { o String name o String id }\n"))))
        (is (= m (repo/get-model node "org.example.base@1.0.0")) "and nothing changed"))
      (testing "a change is a new version"
        (repo/put-model! node "namespace org.example.base@1.1.0\nconcept Party { o String name o String id }\n")
        (is (= ["1.0.0" "1.1.0"] (repo/versions node "org.example.base")))
        (is (= "1.1.0" (:model/version (repo/latest node "org.example.base")))))
      (testing "a namespace needs a version, and the text must parse"
        (is (= :invalid-model (tag #(repo/put-model! node "namespace org.example.nover\nconcept A {}"))))
        (is (= :invalid-model (tag #(repo/put-model! node "namespace org.example.bad@1.0.0\nconcept {")))))
      (is (nil? (repo/get-model node "org.example.absent@1.0.0"))))))

(deftest imports-make-a-closure-and-a-registry
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node town)
    (testing "models load in any order; what is missing is reported"
      (is (= {:order [] :missing ["org.example.base@1.0.0"]}
             (select-keys (repo/closure node "org.example.base@1.0.0") [:order :missing])))
      (is (= {:order ["org.example.town@1.0.0"] :missing ["org.example.base@1.0.0"]}
             (repo/closure node "org.example.town@1.0.0")) "what is stored is listed; what it needs is missing")
      (is (= :model-missing (tag #(repo/registry node "org.example.town@1.0.0"))))
      (is (= :model-missing (tag #(repo/pins node "org.example.town@1.0.0")))))
    (repo/put-model! node base)
    (is (= {:order ["org.example.base@1.0.0" "org.example.town@1.0.0"] :missing []}
           (repo/closure node "org.example.town@1.0.0")))
    (is (some? (repo/registry node "org.example.town@1.0.0")))))

(deftest pins-fingerprint-the-closure
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node base)
    (repo/put-model! node town)
    (let [p (repo/pins node "org.example.town@1.0.0")]
      (is (= ["org.example.base@1.0.0" "org.example.town@1.0.0"] (keys (:pins p))))
      (is (re-matches #"[0-9a-f]{64}" (:hash p)))
      (is (= p (repo/pins node "org.example.town@1.0.0")) "stable")
      (is (= [] (repo/verify-pins node (:pins p))))
      (testing "a model that is not there, or is not what was pinned"
        (is (= [{:problem :model-missing :model "org.example.gone@1.0.0"}]
               (repo/verify-pins node (assoc (:pins p) "org.example.gone@1.0.0" "abc"))))
        (is (= [{:problem :model-changed :model "org.example.base@1.0.0"}]
               (repo/verify-pins node (assoc (:pins p) "org.example.base@1.0.0" "abc"))))))))

(deftest reads-refuse-a-model-altered-after-it-was-stored
  (with-open [node (xt.node/start-node)]
    (repo/put-model! node base)
    (xt/execute-tx node [[:patch-docs :model-version {:xt/id "org.example.base@1.0.0"
                                                      :cto "namespace org.example.base@1.0.0\nconcept Party { o String other }\n"}]])
    (is (= :model-corrupt (tag #(repo/get-model node "org.example.base@1.0.0"))))
    (is (= [{:problem :model-corrupt :model "org.example.base@1.0.0"}]
           (repo/verify-pins node {"org.example.base@1.0.0" "x"})))))

(deftest the-templates-own-models-import
  (when-let [r (io/resource "manager-employment-contract/model/agreement.cto")]
    (with-open [node (xt.node/start-node)]
      (let [dir     (.getParentFile (io/file r))
            results (repo/import-dir! node dir)]
        (is (seq results))
        (is (every? #(#{:imported :unchanged} (:result %)) results) (pr-str (remove #(#{:imported :unchanged} (:result %)) results)))
        (is (some? (repo/get-model node "com.trustblocks.municipal.agreement@1.0.0")))
        (testing "importing again changes nothing"
          (is (every? #(= :unchanged (:result %)) (repo/import-dir! node dir))))
        (testing "a template's root model resolves with everything it imports"
          (let [root (->> (repo/models node) (map :id) (filter #(re-find #"employment" %)) first)]
            (is root)
            (is (some? (repo/registry node root)))
            (is (= [] (:missing (repo/closure node root))))))))))
