(ns com.trustblocks.xtdb.template-tree-test
  "Templates as revisions: a chain of immutable saves, a head that moves, and
  clause-use rows that always agree with the head."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.templatemark.parse :as parse]
            [com.trustblocks.templatemark.printer :as printer]
            [com.trustblocks.templatemark.tree :as tree]
            [com.trustblocks.xtdb.template-tree :as tt]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(defn- doc [s] (parse/parse s))
(defn- inst [s] (java.time.Instant/parse s))
(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))
(defn- text [r] (printer/print-doc (:tree r)))

(deftest saving-builds-a-chain-of-revisions
  (with-open [node (xt.node/start-node)]
    (let [r1 (tt/save! node "snow" (doc "Hello {{town}}.") :by "ada" :note "start")
          t1 (tt/revision node "snow" 1)
          r2 (tt/save! node "snow" (:tree t1) :based-on 1 :note "no change")
          d2 (tree/update-node (:tree t1) (:id (first (:body (:tree t1)))) #(assoc % :md "Hi "))
          r3 (tt/save! node "snow" d2 :based-on 1 :by "grace" :note "friendlier")]
      (is (= {:rev 1 :changed? true} (select-keys r1 [:rev :changed?])))
      (is (false? (:changed? r2)) "identical content is not a new revision")
      (is (= 2 (:rev r3)))
      (is (= {:rev 2 :hash (:hash r3)} (tt/head node "snow")))
      (is (= (:hash r1) (:parent-hash (tt/revision node "snow" 2))) "revisions are chained")
      (is (= "Hello {{town}}." (text (tt/revision node "snow" 1))) "revision 1 is as it was")
      (is (= "Hi {{town}}." (text (tt/revision node "snow"))) "the head is revision 2")
      (is (= [[1 "start" "ada"] [2 "friendlier" "grace"]]
             (map (juxt :rev :note :author) (tt/revisions node "snow")))))))

(deftest a-save-must-start-from-the-head
  (with-open [node (xt.node/start-node)]
    (tt/save! node "t" (doc "One."))
    (testing "starting from nothing when there is a template"
      (is (= :stale-template (tag #(tt/save! node "t" (doc "Two."))))))
    (let [base (:tree (tt/revision node "t"))]
      (tt/save! node "t" (doc "Two.") :based-on 1)
      (testing "someone else saved first"
        (is (= :stale-template (tag #(tt/save! node "t" (doc "Three.") :based-on 1))))
        (is (= "Two." (text (tt/revision node "t"))) "and nothing was overwritten")))
    (testing "an invalid tree"
      (is (= :invalid-template (tag #(tt/save! node "u" {:node :document :id "x" :body [{:node :var}]})))))))

(deftest racing-saves-cannot-both-win
  (with-open [node (xt.node/start-node)]
    (tt/save! node "t" (doc "Base."))
    (let [results (doall (for [i (range 4)]
                           (future (try (tt/save! node "t" (doc (str "Edit " i ".")) :based-on 1)
                                        :saved
                                        (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))))
          rs (mapv deref results)]
      (is (= 1 (count (filter #{:saved} rs))) (pr-str rs))
      (is (= 3 (count (filter #{:stale-template} rs))))
      (is (= 2 (:rev (tt/head node "t")))))))

(deftest clause-uses-follow-the-head
  (with-open [node (xt.node/start-node)]
    (tt/save! node "a" (doc "{{#clause indemnity v=2}}{{/clause}} and {{#clause insurance}}{{/clause}}"))
    (tt/save! node "b" (doc "{{#clause indemnity v=3}}{{/clause}}"))
    (testing "which templates use a clause, and which version they pin"
      (is (= [["a" 2] ["b" 3]] (map (juxt :template :pin) (tt/clause-users node "indemnity"))))
      (is (= [["a" 2]] (map (juxt :template :pin) (tt/clause-users node "indemnity" :version 2))))
      (is (= [["a" :latest]] (map (juxt :template :pin) (tt/clause-users node "insurance")))
          "an unpinned reference floats")
      (is (empty? (tt/clause-users node "nothing"))))
    (testing "an edit that drops a use drops its row, in the same transaction"
      (let [t (:tree (tt/revision node "a"))
            use-node (:node-id (first (filter #(= "indemnity" (:clause %)) (tt/uses node "a"))))]
        (tt/save! node "a" (tree/remove-node t use-node) :based-on 1)
        (is (= ["b"] (map :template (tt/clause-users node "indemnity"))))
        (is (= ["insurance"] (map :clause (tt/uses node "a"))))
        (is (= 2 (:rev (first (tt/uses node "a")))))))
    (testing "an edit that changes a pin changes the row"
      (let [t (:tree (tt/revision node "b"))
            use-node (:node-id (first (tt/uses node "b")))]
        (tt/save! node "b" (tree/update-node t use-node #(assoc % :pin 4)) :based-on 1)
        (is (= [4] (map :pin (tt/clause-users node "indemnity"))))))
    (testing "an inline clause is a use too, with the hash of its words"
      (tt/save! node "c" (doc "{{#clause sig}}Signed.{{/clause}}"))
      (let [u (first (tt/uses node "c"))]
        (is (true? (:inline? u)))
        (is (re-matches #"[0-9a-f]{64}" (:hash u)))))))

(deftest asking-about-the-past
  (with-open [node (xt.node/start-node)]
    (tt/save! node "t" (doc "{{#clause old v=1}}{{/clause}}") :valid-from (inst "2026-01-01T00:00:00Z"))
    (tt/save! node "t" (doc "{{#clause new v=1}}{{/clause}}") :based-on 1 :valid-from (inst "2026-06-01T00:00:00Z"))
    (testing "the head, and its uses, on a given date"
      (is (= 1 (:rev (tt/head node "t" :at (inst "2026-03-01T00:00:00Z")))))
      (is (= 2 (:rev (tt/head node "t" :at (inst "2026-07-01T00:00:00Z")))))
      (is (= ["old"] (map :clause (tt/uses node "t" :at (inst "2026-03-01T00:00:00Z")))))
      (is (= ["new"] (map :clause (tt/uses node "t" :at (inst "2026-07-01T00:00:00Z")))))
      (is (= ["t"] (map :template (tt/clause-users node "old" :at (inst "2026-03-01T00:00:00Z")))))
      (is (empty? (tt/clause-users node "old" :at (inst "2026-07-01T00:00:00Z")))))
    (testing "as we understood it: before it was recorded, nothing"
      (is (nil? (tt/head node "t" :at (inst "2026-07-01T00:00:00Z") :as-of (inst "2020-01-01T00:00:00Z")))))))

(deftest diffing-revisions
  (with-open [node (xt.node/start-node)]
    (tt/save! node "t" (doc "Intro. {{#clause a v=1}}{{/clause}} End."))
    (let [t  (:tree (tt/revision node "t"))
          end-id (:id (last (:body t)))
          t2 (tree/insert t nil :body 1 {:node :text :md "NEW. "})
          t2 (tree/update-node t2 end-id #(assoc % :md " The end."))]
      (tt/save! node "t" t2 :based-on 1)
      (let [d (tt/diff-revisions node "t" 1 2)]
        (is (= ["NEW. "] (map #(get-in % [:node :md]) (:added d))))
        (is (= [" End."] (map #(get-in % [:before :md]) (:changed d))))
        (is (= [" The end."] (map #(get-in % [:after :md]) (:changed d))))
        (is (empty? (:removed d)))))
    (is (= :no-such-template (tag #(tt/diff-revisions node "t" 1 9))))))

(deftest a-revision-altered-after-it-was-stored-is-refused
  (with-open [node (xt.node/start-node)]
    (tt/save! node "t" (doc "Original."))
    (let [row (first (xt/q node '(from :template-revision [*])))]
      (xt/execute-tx node [[:put-docs :template-revision (assoc row :tree (pr-str (doc "Changed.")))]])
      (is (= :template-corrupt (tag #(tt/revision node "t" 1)))))))
