(ns com.trustblocks.xtdb.template-tree
  "Templates as trees (com.trustblocks.templatemark), kept as a chain of
  immutable revisions with a head that moves.

    :template-revision  one row per save, keyed \"<id>@<n>\", never updated:
                        the whole tree, its content hash, and the hash of the
                        revision before it. Saving is the only way to change a
                        template, so its history is complete by construction.
    :template           the head: which revision is current. Bitemporal -- the
                        question \"which revision was the template on
                        2026-03-01, as we then knew it\" is one query.
    :clause-use         one row per use of a library clause by the head
                        revision, rewritten in the SAME transaction that moves
                        the head, so it cannot disagree with the tree. This is
                        what answers \"which templates use clause X\", now or as
                        of any date. (com.trustblocks.xtdb.release writes the
                        same rows for deployed releases.)

  A save names the revision it was based on (`:based-on`). If the head has
  moved since -- someone else saved -- the save is refused with
  :stale-template, and the caller diffs and merges rather than overwriting;
  the transaction itself asserts it, so two saves racing cannot both win.

  Trees are stored as EDN text (pr-str), as models are in store.clj: XTDB
  would mangle the keys of a nested map."
  (:require [clojure.edn :as edn]
            [com.trustblocks.templatemark.clauses :as clauses]
            [com.trustblocks.templatemark.tree :as tree]
            [xtdb.api :as xt]))

(def revision-table :template-revision)
(def head-table     :template)
(def use-table      :clause-use)

(defn revision-id [template-id rev] (str template-id "@" rev))

(def ^:private star (symbol "*"))

(defn- refuse [error msg data]
  (throw (ex-info msg (assoc data :trustblocks/error error))))

(defn- assert-failed? [e] (= :xtdb/assert-failed (:xtdb.error/code (ex-data e))))
(defn- unindexed? [e] (= :xtdb/unindexed-tx (:xtdb.error/code (ex-data e))))

(defn- with-reads [f]
  (loop [n 0]
    (let [r (try {:ok (f)} (catch Exception e (if (and (unindexed? e) (< n 20)) {:retry e} (throw e))))]
      (if (contains? r :ok) (:ok r) (do (Thread/sleep (inc n)) (recur (inc n)))))))

(defn- from-q [table binding {:keys [at as-of]}]
  (list 'from table (cond-> {:bind binding}
                      at    (assoc :for-valid-time (list 'at at))
                      as-of (assoc :for-system-time (list 'at as-of)))))

(defn- ->instant [x] (cond (nil? x) nil (instance? java.time.Instant x) x :else (java.time.Instant/parse x)))

;; -------------------------------------------------------------- clause uses

(defn use-id
  "The id of a template's use row for tree node `node-id`."
  [template-id node-id] (str "template:" template-id "#" node-id))

(defn- use-row [template-id rev {:keys [node-id clause pin inline? hash]}]
  (cond-> {:xt/id      (use-id template-id node-id)
           :owner-kind "template"
           :owner      template-id
           :rev        rev
           :node       node-id
           :clause     clause
           :inline     (boolean inline?)}
    (some? pin) (assoc :pin (if (keyword? pin) (name pin) pin))
    hash        (assoc :hash hash)))

(defn- decode-pin [p] (if (string? p) (keyword p) p))

(defn uses
  "The clause-use rows for `template-id` as they stand at valid time `:at`
  (default now) and system time `:as-of`, as {:node-id :clause :pin :inline? :hash :rev}."
  [node template-id & {:as opts}]
  (->> (with-reads #(xt/q node (from-q use-table [{:owner-kind "template" :owner template-id} star] opts)))
       (mapv (fn [r] (cond-> {:node-id (:node r) :clause (:clause r) :inline? (:inline r) :rev (:rev r)}
                       (some? (:pin r)) (assoc :pin (decode-pin (:pin r)))
                       (:hash r)        (assoc :hash (:hash r)))))
       (sort-by :node-id)
       vec))

(defn clause-users
  "Every use of clause `clause-id` by a template head, as it stands at `:at` /
  `:as-of`: {:template :node-id :pin :inline? :rev}. With `:version`, only the
  uses that pin exactly that version."
  [node clause-id & {:keys [version] :as opts}]
  (->> (with-reads #(xt/q node (from-q use-table [{:owner-kind "template" :clause clause-id} star] opts)))
       (map (fn [r] (cond-> {:template (:owner r) :node-id (:node r) :inline? (:inline r) :rev (:rev r)}
                      (some? (:pin r)) (assoc :pin (decode-pin (:pin r))))))
       (filter #(or (nil? version) (= version (:pin %))))
       (sort-by (juxt :template :node-id))
       vec))

;; --------------------------------------------------------------------- reads

(defn head
  "The head of `template-id` -- {:rev :hash} -- or nil, as of `:at` / `:as-of`."
  [node template-id & {:as opts}]
  (when-let [r (first (with-reads #(xt/q node (from-q head-table [{:xt/id template-id} star] opts))))]
    {:rev (:rev r) :hash (:hash r)}))

(defn- verified-tree [row]
  (let [doc (edn/read-string (:tree row))]
    (when-not (= (:hash row) (clauses/body-hash (:body doc)))
      (refuse :template-corrupt
              (str "Template revision " (:xt/id row) " no longer matches its hash; it was altered after it was stored")
              {:template (:template-id row) :rev (:rev row)}))
    doc))

(defn revision
  "Revision `rev` of `template-id` (default: the head) as {:rev :hash
  :parent-hash :tree :note :author}, or nil. Throws :template-corrupt if the
  stored tree no longer matches its hash."
  [node template-id & [rev]]
  (when-let [rev (or rev (:rev (head node template-id)))]
    (when-let [r (first (with-reads #(xt/q node (list 'from revision-table
                                                      [{:xt/id (revision-id template-id rev)} star]))))]
      (cond-> {:rev rev :hash (:hash r) :tree (verified-tree r)}
        (:parent-hash r) (assoc :parent-hash (:parent-hash r))
        (:note r)        (assoc :note (:note r))
        (:author r)      (assoc :author (:author r))))))

(defn revisions
  "A summary of every revision of `template-id`, oldest first (no trees)."
  [node template-id]
  (->> (with-reads #(xt/q node (list 'from revision-table [{:template-id template-id} star])))
       (sort-by :rev)
       (mapv #(select-keys % [:rev :hash :parent-hash :note :author]))))

(defn diff-revisions
  "The tree diff (com.trustblocks.templatemark.tree/diff) between two revisions
  of `template-id`."
  [node template-id r1 r2]
  (let [a (revision node template-id r1), b (revision node template-id r2)]
    (when-not (and a b)
      (refuse :no-such-template (str "No template " template-id " at both revisions " r1 " and " r2)
              {:template template-id}))
    (tree/diff (:tree a) (:tree b))))

;; --------------------------------------------------------------------- save

(defn save!
  "Save `doc` (a :document tree) as the next revision of `template-id`, and
  move the head to it. Returns {:rev :hash :changed?}.

  `:based-on` is the revision the editor started from (nil for a new
  template). If the head is anywhere else the save is refused with
  :stale-template. Saving content identical to the head's writes nothing and
  returns {:changed? false}.

  One transaction writes the revision, the head, and the clause-use rows (new
  and changed ones put, vanished ones deleted), and asserts that the head is
  still where `:based-on` said, so racing saves cannot both land.

  Options: `:based-on`, `:valid-from` (when this revision takes effect;
  default now), `:by`, `:note`."
  [node template-id doc & {:keys [based-on valid-from by note]}]
  (when-let [problems (tree/explain doc)]
    (refuse :invalid-template (str "Not a valid template: " (pr-str problems)) {:template template-id :problems problems}))
  (let [doc  (tree/with-ids doc)
        h    (clauses/body-hash (:body doc))
        vf   (or (->instant valid-from) (java.time.Instant/now))
        cur  (head node template-id)]
    (cond
      (not= based-on (:rev cur))
      (refuse :stale-template
              (str "Template " template-id " is at revision " (:rev cur) ", not " based-on
                   "; load the current revision and merge")
              {:template template-id :head (:rev cur) :based-on based-on})

      (and cur (= h (:hash cur)))
      {:rev (:rev cur) :hash h :changed? false}

      :else
      (let [rev      (inc (or (:rev cur) 0))
            old-uses (when cur (uses node template-id))
            new-uses (clauses/uses doc)
            gone     (remove (set (map :node-id new-uses)) (map :node-id old-uses))
            guard    (if cur
                       [:sql "ASSERT EXISTS (SELECT 1 FROM template WHERE _id = ? AND rev = ?)" [template-id (:rev cur)]]
                       [:sql "ASSERT NOT EXISTS (SELECT 1 FROM template WHERE _id = ?)" [template-id]])]
        (try
          (xt/execute-tx
           node
           (-> [guard
                [:put-docs {:into revision-table :valid-from vf}
                 (cond-> {:xt/id (revision-id template-id rev) :template-id template-id :rev rev
                          :tree (pr-str doc) :hash h}
                   cur  (assoc :parent-hash (:hash cur))
                   note (assoc :note note)
                   by   (assoc :author by))]
                [:put-docs {:into head-table :valid-from vf}
                 {:xt/id template-id :rev rev :hash h}]]
               (into (map (fn [u] [:put-docs {:into use-table :valid-from vf} (use-row template-id rev u)]) new-uses))
               (into (map (fn [id] [:delete-docs {:from use-table :valid-from vf} (use-id template-id id)]) gone))))
          {:rev rev :hash h :changed? true}
          (catch Exception e
            (if (assert-failed? e)
              (refuse :stale-template
                      (str "Template " template-id " changed while it was being saved; load the current revision and merge")
                      {:template template-id :based-on based-on})
              (throw e))))))))
