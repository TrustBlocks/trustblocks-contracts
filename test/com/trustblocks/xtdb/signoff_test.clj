(ns com.trustblocks.xtdb.signoff-test
  "Sign-offs: a login says agreed or object, about one exact thing; it can
  change its mind and the record keeps both; the author's own does not count."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.templatemark.parse :as parse]
            [com.trustblocks.xtdb.clause-library :as lib]
            [com.trustblocks.xtdb.release :as rel]
            [com.trustblocks.xtdb.signoff :as so]
            [com.trustblocks.xtdb.template-tree :as tt]
            [xtdb.node :as xt.node]))

(defn- body [s] (:body (parse/parse s)))
(defn- tag [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:trustblocks/error (ex-data e)))))

(defn- draft! [node id text by]
  (lib/put-version! node #:clause{:id id :body (body text)} :by by))

(deftest a-thumbs-up-on-a-clause-version
  (with-open [node (xt.node/start-node)]
    (draft! node "indemnity" "Wording." "clerk@town.gov")
    (lib/sign! node "indemnity" 1 :by "manager@town.gov" :role "Town Manager" :note "Looks right")
    (lib/sign! node "indemnity" 1 :by "attorney@town.gov" :role "Town Attorney")
    (let [s (lib/signoffs node "indemnity" 1)]
      (is (= [["manager@town.gov" "Town Manager" :agree] ["attorney@town.gov" "Town Attorney" :agree]]
             (map (juxt :by :role :verdict) s)))
      (is (= "Looks right" (:note (first s))))
      (is (every? #(re-matches #"[0-9a-f]{64}" (:hash %)) s) "each is about the exact content")
      (is (= (:clause/hash (lib/get-version node "indemnity" 1)) (:hash (first s)))))
    (is (= {:agree ["manager@town.gov" "attorney@town.gov"] :object [] :others-agree 2}
           (lib/sign-off-summary node "indemnity" 1)))))

(deftest the-author-agreeing-with-themselves-does-not-count
  (with-open [node (xt.node/start-node)]
    (draft! node "a" "Wording." "clerk@town.gov")
    (lib/sign! node "a" 1 :by "clerk@town.gov")
    (let [s (first (lib/signoffs node "a" 1))]
      (is (true? (:own? s)) "recorded, and marked as the author's own"))
    (is (= 0 (:others-agree (lib/sign-off-summary node "a" 1))))
    (is (false? (:ok? (so/satisfied? node {:kind :clause :id "a@1"} 1))))
    (lib/sign! node "a" 1 :by "manager@town.gov")
    (is (true? (:ok? (so/satisfied? node {:kind :clause :id "a@1"} 1))))))

(deftest changing-your-mind-keeps-both
  (with-open [node (xt.node/start-node)]
    (draft! node "a" "Wording." "clerk@town.gov")
    (lib/sign! node "a" 1 :by "attorney@town.gov" :verdict :object :note "Missing the cap")
    (is (= [:object] (map :verdict (lib/signoffs node "a" 1))))
    (is (= ["attorney@town.gov"] (:object (lib/sign-off-summary node "a" 1))))
    (is (= {:ok? false :missing ["needs 1 agreement(s) from others, has 0"] :objections ["attorney@town.gov"]}
           (so/satisfied? node {:kind :clause :id "a@1"} 1)))
    (lib/sign! node "a" 1 :by "attorney@town.gov" :verdict :agree :note "Cap added elsewhere")
    (is (= [:agree] (map :verdict (lib/signoffs node "a" 1))) "one standing sign-off per person")
    (is (true? (:ok? (so/satisfied? node {:kind :clause :id "a@1"} 1))))
    (testing "the history keeps the objection and the change of mind, in order"
      (let [h (so/history node {:kind :clause :id "a@1"})]
        (is (= [:object :agree] (map :verdict h)))
        (is (= ["Missing the cap" "Cap added elsewhere"] (map :note h)))))
    (testing "a sign-off can be taken back"
      (lib/sign! node "a" 1 :by "attorney@town.gov" :verdict :withdrawn)
      (is (empty? (lib/signoffs node "a" 1)))
      (is (= [:object :agree :withdrawn] (map :verdict (so/history node {:kind :clause :id "a@1"})))))))

(deftest what-was-said-as-of-a-date
  (with-open [node (xt.node/start-node)]
    (draft! node "a" "Wording." "clerk@town.gov")
    (lib/sign! node "a" 1 :by "manager@town.gov" :valid-from (java.time.Instant/parse "2026-03-01T00:00:00Z"))
    (is (empty? (lib/signoffs node "a" 1 :at (java.time.Instant/parse "2026-02-01T00:00:00Z"))))
    (is (= 1 (count (lib/signoffs node "a" 1 :at (java.time.Instant/parse "2026-04-01T00:00:00Z")))))))

(deftest requirements-can-name-roles
  (with-open [node (xt.node/start-node)]
    (draft! node "a" "Wording." "clerk@town.gov")
    (lib/sign! node "a" 1 :by "manager@town.gov" :role "Town Manager")
    (let [subject {:kind :clause :id "a@1"}
          req {:agree 1 :roles #{"Town Attorney"}}]
      (is (= {:ok? false :missing ["needs an agreement from Town Attorney"] :objections []}
             (so/satisfied? node subject req)))
      (lib/sign! node "a" 1 :by "attorney@town.gov" :role "Town Attorney")
      (is (true? (:ok? (so/satisfied? node subject req)))))))

(deftest bad-sign-offs-are-refused
  (with-open [node (xt.node/start-node)]
    (draft! node "a" "Wording." "clerk@town.gov")
    (is (= :no-such-clause (tag #(lib/sign! node "a" 9 :by "x@y"))))
    (is (= :invalid-signoff (tag #(lib/sign! node "a" 1))) "needs a login")
    (is (= :invalid-signoff (tag #(lib/sign! node "a" 1 :by "x@y" :verdict :maybe))))
    (is (= :invalid-signoff (tag #(so/sign! node {:kind :banana :id "x" :hash "h"} :by "x@y"))))))

(deftest a-release-can-require-sign-offs-on-its-clauses
  (with-open [node (xt.node/start-node)]
    (draft! node "indemnity" "Wording." "clerk@town.gov")
    (lib/set-status! node "indemnity" 1 :approved :by "clerk@town.gov")
    (tt/save! node "t" (parse/parse "{{#clause indemnity v=1}}{{/clause}}"))
    (testing "without the sign-off it is refused, saying who is missing"
      (is (= :release-refused (tag #(rel/release! node "t" :require-signoffs 1))))
      (is (re-find #"indemnity version 1 needs 1 agreement"
                   (try (rel/release! node "t" :require-signoffs 1) (catch clojure.lang.ExceptionInfo e (ex-message e))))))
    (testing "the author's own thumbs-up is not enough"
      (lib/sign! node "indemnity" 1 :by "clerk@town.gov")
      (is (= :release-refused (tag #(rel/release! node "t" :require-signoffs 1)))))
    (testing "an objection stops it, whoever else agrees"
      (lib/sign! node "indemnity" 1 :by "manager@town.gov")
      (lib/sign! node "indemnity" 1 :by "attorney@town.gov" :verdict :object)
      (is (re-find #"objection from attorney@town.gov"
                   (try (rel/release! node "t" :require-signoffs 1) (catch clojure.lang.ExceptionInfo e (ex-message e))))))
    (testing "agreed, it releases, and the release remembers who had signed off"
      (lib/sign! node "indemnity" 1 :by "attorney@town.gov" :role "Town Attorney")
      (let [r (rel/release! node "t" :require-signoffs 2)]
        (is (true? (:new? r)))
        (is (= #{"clerk@town.gov" "manager@town.gov" "attorney@town.gov"}
               (set (map :by (get (:signoffs r) "indemnity@1")))))))
    (testing "without a requirement nothing is asked"
      (is (some? (rel/release! node "t"))))))

(deftest a-release-has-its-own-sign-offs
  (with-open [node (xt.node/start-node)]
    (draft! node "a" "Wording." "clerk@town.gov")
    (lib/set-status! node "a" 1 :approved)
    (tt/save! node "t" (parse/parse "{{#clause a v=1}}{{/clause}}"))
    (let [r (rel/release! node "t")]
      (rel/sign! node (:sha r) :by "manager@town.gov" :role "Town Manager" :note "Approve for deployment")
      (rel/sign! node (str "sha256:" (:sha r)) :by "attorney@town.gov" :role "Town Attorney")
      (is (= [["manager@town.gov" "Town Manager"] ["attorney@town.gov" "Town Attorney"]]
             (map (juxt :by :role) (rel/signoffs node (:sha r)))))
      (is (every? #(= (:sha r) (:hash %)) (rel/signoffs node (:sha r))))
      (is (= :no-such-release (tag #(rel/sign! node "deadbeef" :by "x@y")))))))
