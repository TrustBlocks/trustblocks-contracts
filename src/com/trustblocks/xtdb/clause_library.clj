(ns com.trustblocks.xtdb.clause-library
  "The clause library: reusable TEXT clauses (com.trustblocks.templatemark), kept
  so that every change is a new, immutable version and nothing already used is
  ever altered.

  Two tables, because two different things are true of a clause:

    :clause-version  WHAT the clause says -- one row per version, keyed
                     \"<id>@<n>\", written once and never updated. The row
                     carries the SHA-256 of its body and model fragment, and
                     the hash of the version before it, so a version's place
                     in the clause's history is part of what it is.
    :clause-state    WHETHER it may be used -- :draft, :approved or :retired,
                     keyed by the same id, and bitemporal: it changes over
                     time, and XTDB keeps every state it was in and when we
                     learned of it.

  Editing a clause is saving a new version. There is no function here that
  alters a version, and `put-version!` refuses to write over one even when it
  is asked to (the write is guarded inside the transaction). Every read
  re-hashes what it returns: a version whose stored content no longer matches
  its hash is refused, never served -- see `get-version`.

  A version's valid time is when its wording takes effect (an ordinance's
  effective date), its system time when we recorded it; `in-force` answers
  \"which version applied on this date\".

  These are text clauses, distinct from the SCI clause *logic* of
  com.trustblocks.clause and com.trustblocks.xtdb.execute."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [com.trustblocks.concerto.parser :as parser]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.templatemark.clauses :as clauses]
            [com.trustblocks.templatemark.model :as model]
            [com.trustblocks.templatemark.tree :as tree]
            [com.trustblocks.xtdb.model-repository :as repo]
            [com.trustblocks.xtdb.model-use :as model-use]
            [com.trustblocks.xtdb.signoff :as signoff]
            [xtdb.api :as xt]))

(def version-table :clause-version)
(def state-table   :clause-state)

(def statuses #{:draft :approved :retired})

(def ^:private transitions
  {:draft #{:approved :retired}, :approved #{:retired}, :retired #{}})

;; ----------------------------------------------------------------- helpers

(defn- refuse [error msg data]
  (throw (ex-info msg (assoc data :trustblocks/error error))))

(defn version-id [clause-id version] (str clause-id "@" version))

(defn- ->instant [x] (cond (nil? x) nil (instance? java.time.Instant x) x :else (java.time.Instant/parse x)))

(defn- zdt->instant [^java.time.ZonedDateTime z] (some-> z .toInstant))

(defn- from-q
  "A `from` query over `table` binding `binding`, read as of valid time `at`
  and/or system time `as-of` when given."
  [table binding {:keys [at as-of]}]
  (list 'from table (cond-> {:bind binding}
                      at    (assoc :for-valid-time (list 'at at))
                      as-of (assoc :for-system-time (list 'at as-of)))))

(def ^:private star (symbol "*"))

(defn- assert-failed? [e] (= :xtdb/assert-failed (:xtdb.error/code (ex-data e))))

(defn- unindexed? [e] (= :xtdb/unindexed-tx (:xtdb.error/code (ex-data e))))

(defn- with-reads
  "Runs `f`, running it again a moment later when XTDB 2.1 says a read came
  before it had finished indexing (:xtdb/unindexed-tx -- see
  com.trustblocks.xtdb.execute/unindexed?). Nothing has been written when that
  happens, so a read is safe to repeat."
  [f]
  (loop [n 0]
    (let [r (try {:ok (f)} (catch Exception e (if (and (unindexed? e) (< n 20)) {:retry e} (throw e))))]
      (if (contains? r :ok)
        (:ok r)
        (do (Thread/sleep (inc n)) (recur (inc n)))))))

;; -------------------------------------------------------------------- rows

(defn- clause->row [{:clause/keys [id version title body model root requires states hash parent-hash note author]}]
  (cond-> {:xt/id     (version-id id version)
           :clause-id id
           :version   version
           :body      (pr-str body)
           :hash      hash}
    title       (assoc :title title)
    model       (assoc :model model)
    root        (assoc :root root)
    requires    (assoc :requires (pr-str requires))
    states      (assoc :states (pr-str states))
    parent-hash (assoc :parent-hash parent-hash)
    note        (assoc :note note)
    author      (assoc :author author)))

(defn- row->clause [row]
  (cond-> #:clause{:id      (:clause-id row)
                   :version (:version row)
                   :body    (edn/read-string (:body row))
                   :hash    (:hash row)}
    (:title row)       (assoc :clause/title (:title row))
    (:model row)       (assoc :clause/model (:model row))
    (:root row)        (assoc :clause/root (:root row))
    (:requires row)    (assoc :clause/requires (edn/read-string (:requires row)))
    (:states row)      (assoc :clause/states (edn/read-string (:states row)))
    (:parent-hash row) (assoc :clause/parent-hash (:parent-hash row))
    (:note row)        (assoc :clause/note (:note row))
    (:author row)      (assoc :clause/author (:author row))))

(defn- verified
  "The clause a stored row holds, after checking the row still hashes as it
  did when written."
  [row]
  (let [c (row->clause row)]
    (when-not (= (:hash row) (clauses/clause-hash c))
      (refuse :clause-corrupt
              (str "Clause " (:xt/id row) " no longer matches its hash; it was altered after it was stored")
              {:clause (:clause-id row) :version (:version row)}))
    c))

;; ------------------------------------------------------------------- reads

(defn version-rows
  "Every stored row for clause `id`, lowest version first."
  [node id & {:as opts}]
  (->> (with-reads #(xt/q node (from-q version-table [{:clause-id id} star] opts)))
       (sort-by :version)))

(defn get-version
  "Version `version` of clause `id` as a clause map, or nil. Throws
  :clause-corrupt if what is stored no longer matches its hash."
  [node id version & {:as opts}]
  (some-> (first (with-reads #(xt/q node (from-q version-table [{:xt/id (version-id id version)} star] opts))))
          verified))

(defn latest
  "The highest version of clause `id`, or nil."
  [node id & {:as opts}]
  (some-> (last (version-rows node id opts)) verified))

(defn versions
  "A summary of every version of `id`, lowest first: {:version :hash :parent-hash
  :title :note :author}. Does not decode bodies."
  [node id]
  (mapv #(select-keys % [:version :hash :parent-hash :title :note :author])
        (version-rows node id)))

(defn clause-ids
  "The ids of every clause in the library."
  [node]
  (->> (with-reads #(xt/q node '(from :clause-version [clause-id])))
       (map :clause-id) distinct sort vec))

;; ------------------------------------------------------------------ status

(defn state-history
  "The states version `version` of `id` has been in, oldest first, as the
  timeline stands now: {:status :by :note :valid-from :valid-to :recorded-at}.
  :valid-from is when a state took effect (nil :valid-to means still in
  effect), :recorded-at when we learned of it. A back-dated change rewrites
  the timeline from its date; what we believed before is not shown here but
  is not lost -- ask with `:as-of` on `status`, or see `state-audit`."
  [node id version]
  (->> (with-reads #(xt/q node (list 'from state-table
                                     {:for-valid-time :all-time
                                      :bind [{:xt/id (version-id id version)} star
                                             'xt/valid-from 'xt/valid-to 'xt/system-from]})))
       (sort-by :xt/valid-from)
       (mapv (fn [r] {:status      (keyword (:status r))
                      :by          (:by r)
                      :note        (:note r)
                      :valid-from  (zdt->instant (:xt/valid-from r))
                      :valid-to    (zdt->instant (:xt/valid-to r))
                      :recorded-at (zdt->instant (:xt/system-from r))}))))

(defn state-audit
  "Every row ever recorded for version `version` of `id`, superseded ones
  included, oldest recorded first: the full record of what we believed and
  when. {:status :valid-from :valid-to :recorded-from :recorded-to}."
  [node id version]
  (->> (with-reads #(xt/q node (list 'from state-table
                                     {:for-valid-time :all-time
                                      :for-system-time :all-time
                                      :bind [{:xt/id (version-id id version)} star
                                             'xt/valid-from 'xt/valid-to 'xt/system-from 'xt/system-to]})))
       (sort-by (juxt :xt/system-from :xt/valid-from))
       (mapv (fn [r] {:status        (keyword (:status r))
                      :valid-from    (zdt->instant (:xt/valid-from r))
                      :valid-to      (zdt->instant (:xt/valid-to r))
                      :recorded-from (zdt->instant (:xt/system-from r))
                      :recorded-to   (zdt->instant (:xt/system-to r))}))))

(defn status
  "The state of version `version` of `id` -- :draft, :approved or :retired --
  as of valid time `:at` (default now) and system time `:as-of` (default now);
  nil if there is no such version."
  [node id version & {:keys [at as-of]}]
  (let [at (or at (java.time.Instant/now))]
    (some-> (first (with-reads #(xt/q node (from-q state-table [{:xt/id (version-id id version)} star]
                                                   {:at at :as-of as-of}))))
            :status keyword)))

(defn in-force
  "The version of clause `id` that applied at `at` (default now): the highest
  version that had taken effect by then and was :approved at that moment. Nil
  if none. With `:as-of`, as we understood it at that system time."
  [node id & {:keys [at as-of]}]
  (let [at (or at (java.time.Instant/now))]
    (->> (version-rows node id {:at at :as-of as-of})
         reverse
         (filter #(= :approved (status node id (:version %) :at at :as-of as-of)))
         first
         (#(some-> % verified)))))

(defn set-status!
  "Move version `version` of `id` to `new-status`, taking effect at
  `:valid-from` (default now). Allowed: draft -> approved, draft -> retired,
  approved -> retired. A retired version cannot be revived, and an approved one
  cannot go back to draft: the words someone approved are not un-approved, they
  are replaced by a new version. Returns the new state."
  [node id version new-status & {:keys [by note valid-from]}]
  (when-not (statuses new-status)
    (refuse :invalid-status (str "Not a status: " (pr-str new-status)) {:status new-status}))
  (let [current (:status (last (state-history node id version)))]
    (when-not current
      (refuse :no-such-clause (str "No clause " (version-id id version)) {:clause id :version version}))
    (when-not (contains? (transitions current) new-status)
      (refuse :invalid-transition
              (str "Clause " (version-id id version) " is " (name current) "; it cannot become " (name new-status))
              {:clause id :version version :from current :to new-status}))
    (let [vf (or (->instant valid-from) (java.time.Instant/now))]
      (xt/execute-tx node [[:put-docs {:into state-table :valid-from vf}
                            (cond-> {:xt/id (version-id id version) :status (name new-status)}
                              by (assoc :by by) note (assoc :note note))]])
      new-status)))

;; ------------------------------------------------------------------ writes

(defn- validate! [{:clause/keys [id body model] :as clause}]
  (when-not (and (string? id) (re-matches #"[A-Za-z][A-Za-z0-9_.-]*" id))
    (refuse :invalid-clause (str "A clause id starts with a letter and holds letters, digits, . _ -; not " (pr-str id))
            {:clause id}))
  (when-not (vector? body)
    (refuse :invalid-clause "A clause body is a vector of template nodes" {:clause id}))
  (when-let [states (:clause/states clause)]
    (when-not (and (sequential? states) (every? #(and (map? %) (string? (:event %))) states))
      (refuse :invalid-clause
              (str "Clause " id ": :clause/states is a list of {:event \"EventName\" :authority \"AUTHORITY\"}, the lifecycle events its text states")
              {:clause id})))
  (let [doc {:node :document :id "check" :body body}]
    (when-let [problems (tree/explain doc)]
      (refuse :invalid-clause-body (str "Clause " id "'s body is not a valid template: " (pr-str problems))
              {:clause id :problems problems})))
  (when-let [floating (seq (clauses/unpinned-references body))]
    (refuse :unpinned-nested-clause
            (str "Clause " id " refers to " (str/join ", " (map :clause floating))
                 " without pinning a version. A library clause is immutable, so what it depends on must be too: write v=N.")
            {:clause id :references (vec floating)}))
  (when model
    (try (parser/parse-string (str "namespace check@1.0.0\n" model))
         (catch Exception e
           (refuse :invalid-clause-model (str "Clause " id "'s model fragment does not parse: " (ex-message e))
                   {:clause id}))))
  clause)

(declare lookup)

(defn- check-requires
  "For a clause that says which model it reads -- :clause/requires
  {:model \"ns@1.0.0\" :root \"Type\" :fields [\"name\" ...]} -- holds its body to
  that model and returns the properties it reads (templatemark model/reads).

  Every placeholder must resolve against `:root` in the pinned model, or the
  clause is refused (:clause-does-not-fit-model). If `:fields` is given it is
  the clause's declaration of what it reads from the root and its supertypes;
  reading anything else is refused (:undeclared-read), so a clause cannot
  quietly depend on more of the contract than it says."
  [node {:clause/keys [id body requires]}]
  (let [{:keys [model root fields]} requires
        _        (when-not (and (string? model) (string? root))
                   (refuse :invalid-clause (str "Clause " id ": :requires needs a :model (ns@version) and a :root type")
                           {:clause id}))
        reg      (try (repo/registry node model)
                      (catch clojure.lang.ExceptionInfo e
                        (refuse (or (:trustblocks/error (ex-data e)) :model-missing)
                                (str "Clause " id " requires model " model ": " (ex-message e))
                                {:clause id :model model})))
        root-fqn (str model "." root)
        _        (when-not (get reg root-fqn)
                   (refuse :clause-does-not-fit-model (str "Model " model " has no type " root) {:clause id :model model :root root}))
        doc      {:node :document :id "check" :body (:body (tree/with-ids {:node :document :id "x" :body body}))}
        library  (lookup node)
        errors   (model/errors (model/typecheck doc reg root-fqn :library library))]
    (when (seq errors)
      (refuse :clause-does-not-fit-model
              (str "Clause " id " does not fit " model "." root ": " (str/join "; " (map :message errors)))
              {:clause id :model model :root root :problems errors}))
    (let [reads (model/reads doc reg root-fqn :library library)]
      (when (seq fields)
        (let [scope (set (map #(str % ".") (mm/super-chain reg root-fqn)))
              ours  (fn [fqn] (some #(and (str/starts-with? fqn %) (not (str/includes? (subs fqn (count %)) "."))) scope))
              extra (->> reads (map :fqn) distinct (filter ours)
                         (remove (fn [fqn] (some #(str/ends-with? fqn (str "." %)) fields))))]
          (when (seq extra)
            (refuse :undeclared-read
                    (str "Clause " id " reads " (str/join ", " extra) " but does not declare it in :fields")
                    {:clause id :undeclared (vec extra)}))))
      reads)))

(defn put-version!
  "Save `clause` -- {:clause/id :clause/title :clause/body :clause/model
  :clause/root :clause/requires :clause/states} -- as the next version of its id, and return it with its
  :clause/version, :clause/hash and :clause/parent-hash. The new version starts
  as :draft.

  If the content is identical to the latest version's, nothing is written and
  that version is returned: saving an unchanged clause is not a change.
  Otherwise the version number is the latest plus one, written in a
  transaction that aborts if a concurrent writer took that number first (it
  then tries again with the new latest).

  Options: `:valid-from` when the wording takes effect (default now), `:by`,
  `:note` (why it changed).

  A clause with :clause/requires is held to that model first (see
  `check-requires`), and the properties it reads are recorded with the
  version (com.trustblocks.xtdb.model-use)."
  [node clause & {:keys [valid-from by note]}]
  (validate! clause)
  (let [id   (:clause/id clause)
        body (:body (tree/with-ids {:node :document :id "x" :body (:clause/body clause)}))
        base (-> clause (assoc :clause/body body) (select-keys [:clause/id :clause/title :clause/body :clause/model :clause/root :clause/requires :clause/states]))
        reads (when (:clause/requires clause) (check-requires node base))
        h    (clauses/clause-hash base)
        vf   (or (->instant valid-from) (java.time.Instant/now))]
    (loop [attempt 0]
      (let [prior (latest node id)]
        (if (and prior (= h (:clause/hash prior)))
          prior
          (let [c   (cond-> (assoc base
                                   :clause/version (inc (or (:clause/version prior) 0))
                                   :clause/hash h)
                      prior (assoc :clause/parent-hash (:clause/hash prior))
                      note  (assoc :clause/note note)
                      by    (assoc :clause/author by))
                vid (version-id id (:clause/version c))
                r   (try
                      (xt/execute-tx node
                                     (into
                                      [[:sql "ASSERT NOT EXISTS (SELECT 1 FROM clause_version WHERE _id = ?)" [vid]]
                                      [:put-docs {:into version-table :valid-from vf} (clause->row c)]
                                      [:put-docs {:into state-table :valid-from vf}
                                       (cond-> {:xt/id vid :status "draft"} by (assoc :by by))]]
                                     (map (fn [row] [:put-docs {:into model-use/table :valid-from vf} row])
                                          (model-use/rows :clause vid (:clause/version c) reads))))
                      c
                      (catch Exception e
                        (if (and (assert-failed? e) (< attempt 5)) ::taken (throw e))))]
            (if (= ::taken r) (recur (inc attempt)) r)))))))

(defn lookup
  "A `(fn [id pin])` over the stored library, in the shape
  com.trustblocks.templatemark.clauses/library has: the exact version for an
  integer pin, and for :latest or nil the highest version whose state is one of
  `:statuses` (default #{:approved}) -- a draft is not picked up by a template
  that floats until someone has approved it. Verifies every clause it returns."
  [node & {:keys [statuses] :or {statuses #{:approved}}}]
  (fn [id pin]
    (if (integer? pin)
      (get-version node id pin)
      (->> (version-rows node id)
           reverse
           (filter #(contains? statuses (status node id (:version %))))
           first
           (#(some-> % verified))))))

(defn diff-versions
  "What changed in the text between versions `v1` and `v2` of `id`: the
  template-tree diff of their bodies (see com.trustblocks.templatemark.tree/diff)
  and whether the model fragment changed."
  [node id v1 v2]
  (let [a (get-version node id v1), b (get-version node id v2)]
    (when-not (and a b)
      (refuse :no-such-clause (str "No clause " id " at both versions " v1 " and " v2) {:clause id}))
    {:body  (tree/diff {:node :document :id "a" :body (:clause/body a)}
                       {:node :document :id "b" :body (:clause/body b)})
     :model {:changed? (not= (:clause/model a) (:clause/model b))
             :before   (:clause/model a)
             :after    (:clause/model b)}}))

;; ----------------------------------------------------------------- sign-offs

(defn sign!
  "The signed-in `:by` says `:verdict` (:agree by default, :object, or
  :withdrawn) about version `version` of clause `id`. See
  com.trustblocks.xtdb.signoff: a thumbs-up with an optional `:role` and
  `:note`, recorded against the version's hash. The version's own author is
  recorded as such, and their sign-off does not count toward a requirement."
  [node id version & {:as opts}]
  (let [c (or (get-version node id version)
              (refuse :no-such-clause (str "No clause " (version-id id version)) {:clause id :version version}))]
    (apply signoff/sign! node {:kind :clause :id (version-id id version) :hash (:clause/hash c)}
           (mapcat identity (assoc opts :author (:clause/author c))))))

(defn signoffs
  "The standing sign-offs on version `version` of clause `id` (see signoff/signoffs)."
  [node id version & {:as opts}]
  (signoff/signoffs node {:kind :clause :id (version-id id version)} opts))

(defn sign-off-summary
  "Who has agreed and objected on a clause version: {:agree :object :others-agree}."
  [node id version & {:as opts}]
  (signoff/summary node {:kind :clause :id (version-id id version)} opts))
