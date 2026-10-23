(ns com.trustblocks.xtdb.clause-library-test
  "The clause library: every change a new immutable version, content hashes
  chained through the history, state that changes over time, and reads that
  refuse a version altered after it was stored."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.templatemark.parse :as parse]
            [com.trustblocks.templatemark.printer :as printer]
            [com.trustblocks.templatemark.tree :as tree]
            [com.trustblocks.xtdb.clause-library :as lib]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(defn- body [s] (:body (parse/parse s)))
(defn- clause [id text & {:as more}] (merge #:clause{:id id :title id :body (body text)} more))
(defn- text [c] (printer/print-doc {:node :document :body (:clause/body c)}))
(defn- inst [s] (java.time.Instant/parse s))
(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(deftest every-change-is-a-new-version
  (with-open [node (xt.node/start-node)]
    (let [v1 (lib/put-version! node (clause "indemnity" "Old wording.") :by "clerk" :note "first draft")
          same (lib/put-version! node (clause "indemnity" "Old wording."))
          v2 (lib/put-version! node (clause "indemnity" "New wording for {{town}}.") :note "added the town")]
      (is (= 1 (:clause/version v1)))
      (is (= :draft (lib/status node "indemnity" 1)) "a new version starts as a draft")
      (is (= v1 same) "saving unchanged content is not a change")
      (is (= 2 (:clause/version v2)))
      (is (re-matches #"[0-9a-f]{64}" (:clause/hash v1)))
      (is (= (:clause/hash v1) (:clause/parent-hash v2)) "each version names the one before it")
      (is (nil? (:clause/parent-hash v1)))
      (is (not= (:clause/hash v1) (:clause/hash v2)))
      (testing "both versions stay readable, exactly as written"
        (is (= "Old wording." (text (lib/get-version node "indemnity" 1))))
        (is (= "New wording for {{town}}." (text (lib/get-version node "indemnity" 2))))
        (is (= 2 (:clause/version (lib/latest node "indemnity")))))
      (testing "history"
        (is (= [[1 "first draft" "clerk"] [2 "added the town" nil]]
               (map (juxt :version :note :author) (lib/versions node "indemnity"))))
        (is (= ["indemnity"] (lib/clause-ids node))))
      (testing "going back to old words is still a new version"
        (let [v3 (lib/put-version! node (clause "indemnity" "Old wording."))]
          (is (= 3 (:clause/version v3)))
          (is (= (:clause/hash v1) (:clause/hash v3)) "same words, same hash"))))))

(deftest a-stored-version-cannot-be-quietly-changed
  (with-open [node (xt.node/start-node)]
    (lib/put-version! node (clause "indemnity" "As approved."))
    (testing "the library has no way to alter a version; writing round the library is caught on read"
      (let [row (first (xt/q node '(from :clause-version [*])))]
        (xt/execute-tx node [[:put-docs :clause-version (assoc row :body (pr-str (body "Quietly rewritten.")))]])
        (is (= :clause-corrupt (tag #(lib/get-version node "indemnity" 1))))
        (is (= :clause-corrupt (tag #(lib/latest node "indemnity"))))))))

(deftest a-clause-must-be-sound-to-be-stored
  (with-open [node (xt.node/start-node)]
    (testing "id"
      (is (= :invalid-clause (tag #(lib/put-version! node (clause "1bad" "x")))))
      (is (= :invalid-clause (tag #(lib/put-version! node (clause "has space" "x"))))))
    (testing "a body that is not a template"
      (is (= :invalid-clause-body (tag #(lib/put-version! node #:clause{:id "x" :body [{:node :var}]}))))
      (is (= :invalid-clause (tag #(lib/put-version! node #:clause{:id "x" :body "text"})))))
    (testing "a dependency that floats"
      (is (= :unpinned-nested-clause (tag #(lib/put-version! node (clause "outer" "see {{#clause inner}}{{/clause}}")))))
      (is (= :unpinned-nested-clause (tag #(lib/put-version! node (clause "outer" "see {{#clause inner v=latest}}{{/clause}}")))))
      (is (some? (lib/put-version! node (clause "outer" "see {{#clause inner v=1}}{{/clause}}")))))
    (testing "a model fragment that does not parse"
      (is (= :invalid-clause-model (tag #(lib/put-version! node (clause "m" "x" :clause/model "concept {"))))))
    (testing "and one that does"
      (is (some? (lib/put-version! node (clause "m2" "{{n}}" :clause/model "concept M { o Integer n }")))))
    (is (not-any? #{"1bad" "x"} (lib/clause-ids node)) "nothing refused was stored")))

(deftest state-changes-over-time
  (with-open [node (xt.node/start-node)]
    (lib/put-version! node (clause "indemnity" "Wording.") :valid-from (inst "2026-01-01T00:00:00Z"))
    (is (= :draft (lib/status node "indemnity" 1)))
    (lib/set-status! node "indemnity" 1 :approved :by "council" :note "Resolution 26-14" :valid-from (inst "2026-02-01T00:00:00Z"))
    (is (= :approved (lib/status node "indemnity" 1)))
    (testing "as it stood before the approval took effect"
      (is (= :draft (lib/status node "indemnity" 1 :at (inst "2026-01-15T00:00:00Z")))
          "valid time says the approval took effect on Feb 1")
      (is (nil? (lib/status node "indemnity" 1 :at (inst "2025-12-01T00:00:00Z"))) "and the clause did not exist before Jan 1"))
    (testing "the timeline, who set each state, and when we recorded it"
      (let [h (lib/state-history node "indemnity" 1)]
        (is (= [:draft :approved] (map :status h)))
        (is (= (inst "2026-02-01T00:00:00Z") (:valid-to (first h))))
        (is (= "council" (:by (second h))))
        (is (= "Resolution 26-14" (:note (second h))))
        (is (= (inst "2026-02-01T00:00:00Z") (:valid-from (second h))))
        (is (nil? (:valid-to (second h))))
        (is (every? :recorded-at h))))
    (testing "the audit keeps what we believed before the back-dated approval, too"
      (let [a (lib/state-audit node "indemnity" 1)]
        (is (< (count (lib/state-history node "indemnity" 1)) (count a)))
        (is (some #(and (= :draft (:status %)) (nil? (:valid-to %)) (:recorded-to %)) a)
            "the draft that once ran on forever, until the approval cut it short")))
    (testing "the rules of the workflow"
      (is (= :invalid-transition (tag #(lib/set-status! node "indemnity" 1 :draft))) "approved words are replaced, not un-approved")
      (is (= :invalid-status (tag #(lib/set-status! node "indemnity" 1 :nonsense))))
      (is (= :no-such-clause (tag #(lib/set-status! node "indemnity" 9 :approved))))
      (lib/set-status! node "indemnity" 1 :retired)
      (is (= :retired (lib/status node "indemnity" 1)))
      (is (= :invalid-transition (tag #(lib/set-status! node "indemnity" 1 :approved))) "retired is final"))))

(deftest which-version-applied-on-a-date
  (with-open [node (xt.node/start-node)]
    (lib/put-version! node (clause "indemnity" "One.") :valid-from (inst "2026-01-01T00:00:00Z"))
    (lib/set-status! node "indemnity" 1 :approved :valid-from (inst "2026-01-02T00:00:00Z"))
    (lib/put-version! node (clause "indemnity" "Two.") :valid-from (inst "2026-06-01T00:00:00Z"))
    (lib/set-status! node "indemnity" 2 :approved :valid-from (inst "2026-06-10T00:00:00Z"))
    (let [at (fn [s] (some-> (lib/in-force node "indemnity" :at (inst s)) :clause/version))]
      (is (nil? (at "2025-12-31T00:00:00Z")) "before anything")
      (is (nil? (at "2026-01-01T12:00:00Z")) "written but not yet approved")
      (is (= 1 (at "2026-03-01T00:00:00Z")))
      (is (= 1 (at "2026-06-05T00:00:00Z")) "version 2 in effect but not approved yet")
      (is (= 2 (at "2026-07-01T00:00:00Z"))))
    (testing "as we understood it: before the library had any of this, nothing"
      (is (nil? (lib/in-force node "indemnity" :at (inst "2026-07-01T00:00:00Z")
                              :as-of (inst "2020-01-01T00:00:00Z")))))))

(deftest lookup-for-templates
  (with-open [node (xt.node/start-node)]
    (lib/put-version! node (clause "a" "One."))
    (lib/set-status! node "a" 1 :approved)
    (lib/put-version! node (clause "a" "Two (draft)."))
    (let [look (lib/lookup node)]
      (is (= 1 (:clause/version (look "a" 1))))
      (is (= 2 (:clause/version (look "a" 2))) "an exact pin is exact, whatever its state")
      (is (= 1 (:clause/version (look "a" :latest))) "floating picks up approved clauses only")
      (is (= 1 (:clause/version (look "a" nil))))
      (is (nil? (look "a" 9)))
      (is (nil? (look "nothing" :latest))))
    (is (= 2 (:clause/version ((lib/lookup node :statuses #{:draft :approved}) "a" :latest))))))

(deftest diffing-versions
  (with-open [node (xt.node/start-node)]
    (let [v1 (lib/put-version! node (clause "a" "Pay {{amount}} on time." :clause/model "concept A { o Double amount }"))
          ;; the builder edits the loaded tree, so nodes keep their ids
          doc  {:node :document :id "d" :body (:clause/body v1)}
          last-id (:id (last (:body doc)))]
      (lib/put-version! node (assoc (clause "a" "x") :clause/body
                                    (:body (tree/update-node doc last-id #(assoc % :md " within 30 days.")))
                                    :clause/model "concept A { o Double amount o Integer days }")))
    (let [d (lib/diff-versions node "a" 1 2)]
      (is (= [{:before " on time." :after " within 30 days."}]
             (map (fn [c] {:before (:md (:before c)) :after (:md (:after c))}) (:changed (:body d)))))
      (is (true? (:changed? (:model d))))
      (is (= "concept A { o Double amount }" (:before (:model d)))))
    (is (= :no-such-clause (tag #(lib/diff-versions node "a" 1 7))))))

(deftest racing-writers-get-distinct-versions
  (with-open [node (xt.node/start-node)]
    (let [fs (doall (for [i (range 6)]
                      (future (lib/put-version! node (clause "race" (str "Wording number " i "."))))))
          vs (mapv deref fs)]
      (is (= (range 1 7) (sort (map :clause/version vs))) "six different texts, six versions, none lost or doubled")
      (is (= 6 (count (lib/versions node "race"))))
      (testing "and the chain is unbroken"
        (let [by-v (sort-by :version (lib/versions node "race"))]
          (is (every? (fn [[a b]] (= (:hash a) (:parent-hash b))) (partition 2 1 by-v))))))))
