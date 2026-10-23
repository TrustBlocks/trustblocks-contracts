(ns com.trustblocks.xtdb.release
  "Deploying a template: pinning it, fingerprinting it, and holding contracts
  to the fingerprint.

  While a template is being built it floats: a clause reference may say
  `v=latest`, and the library moves under it. A contract in force may not
  float. `release!` takes a template revision and resolves every clause
  reference, in the template and in the library clauses those pull in, to an
  exact version; it refuses any clause that is not :approved. What it stores
  is a RELEASE -- a self-contained, immutable bundle:

    the pinned template tree        (so it can be shown without the library)
    the manifest                    (every clause, version and content hash)
    the composed Concerto model     (when composed: a clause may bring data)
    the SHA                         (of all of the above together)

  A release is keyed by its SHA (`sha256:<hex>`), so the same pinned content is
  the same release, and a release cannot be changed: changing anything changes
  its key. A contract is deployed AGAINST a release (`deploy!`), and
  `verify` re-reads every clause from the library and recomputes every hash --
  the check that the clauses a contract in force depends on are still the ones
  it was signed with. `check-active` runs it over every deployed contract.

  The :clause-use table gets one row per clause per release, so \"which
  deployed contracts contain clause X version 3\" is a query
  (`deployed-with`), and retiring or replacing a clause starts from knowing
  its blast radius."
  (:require [clojure.edn :as edn]
            [com.trustblocks.templatemark.clauses :as clauses]
            [com.trustblocks.templatemark.compose :as compose]
            [com.trustblocks.templatemark.release :as release]
            [com.trustblocks.xtdb.clause-library :as library]
            [com.trustblocks.xtdb.template-tree :as templates]
            [xtdb.api :as xt]))

(def release-table  :release)
(def deployed-table :contract-release)

(def ^:private star (symbol "*"))

(defn- refuse [error msg data]
  (throw (ex-info msg (assoc data :trustblocks/error error))))

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

(defn release-id [sha] (str "sha256:" sha))

(defn- model-hash [model] (when model (clauses/sha-256 (pr-str (clauses/canonical model)))))

(defn- exact-lookup
  "A lookup that only ever returns the exact version asked for, whatever its
  state: verification is about content, not about whether the clause is still
  approved."
  [node]
  (fn [id pin] (when (integer? pin) (library/get-version node id pin))))

;; ------------------------------------------------------------------- reads

(defn get-release
  "The stored release `sha` (hex, or `sha256:<hex>`) as {:sha :template-id
  :template-rev :tree :manifest :template-hash :model-hash :model}, or nil."
  [node sha]
  (let [sha (if (.startsWith ^String sha "sha256:") (subs sha 7) sha)]
    (when-let [r (first (with-reads #(xt/q node (list 'from release-table [{:xt/id (release-id sha)} star]))))]
      (cond-> {:sha           sha
               :template-id   (:template-id r)
               :template-rev  (:template-rev r)
               :tree          (edn/read-string (:tree r))
               :manifest      (edn/read-string (:manifest r))
               :template-hash (:template-hash r)}
        (:model-hash r) (assoc :model-hash (:model-hash r))
        (:model r)      (assoc :model (edn/read-string (:model r)))))))

(defn verify
  "Does release `sha` still hold? {:ok? :problems}. Re-reads every clause the
  release pinned from the library and recomputes every hash; also checks the
  stored tree and model against the hashes the release recorded. Problems:
  :sha-mismatch, :clause-changed, :clause-missing, :clause-corrupt (a stored
  clause no longer matches its own hash), :template-changed, :model-changed,
  :no-such-release."
  [node sha]
  (if-let [r (get-release node sha)]
    (let [lookup   (exact-lookup node)
          safe     (fn [id pin]
                     (try {:clause (lookup id pin)}
                          (catch clojure.lang.ExceptionInfo e
                            (if (= :clause-corrupt (:trustblocks/error (ex-data e)))
                              {:corrupt (ex-message e)}
                              (throw e)))))
          results  (into {} (for [{:keys [clause version]} (:manifest r)] [[clause version] (safe clause version)]))
          corrupt  (for [[[id v] {:keys [corrupt]}] results :when corrupt]
                     {:rule :clause-corrupt :clause id :version v :message corrupt})
          lookup'  (fn [id pin] (:clause (get results [id pin])))
          problems (vec (concat
                         corrupt
                         (remove #(and (#{:clause-missing} (:rule %))
                                       (some (fn [c] (and (= (:clause c) (:clause %)) (= (:version c) (:version %)))) corrupt))
                                 (release/verify r lookup' :doc {:node :document :body (:body (:tree r))}))
                         (when (and (:model r) (not= (:model-hash r) (model-hash (:model r))))
                           [{:rule :model-changed :message "The stored model no longer matches the release's model hash"}])))]
      {:ok? (empty? problems) :problems problems})
    {:ok? false :problems [{:rule :no-such-release :message (str "No release " sha)}]}))

;; ------------------------------------------------------------------ release

(defn release!
  "Release revision `rev` (default: the head) of `template-id`. Returns the
  stored release (see get-release) with :new? saying whether it was written or
  already existed (an identical pinned template is the same release).

  Refuses, with :trustblocks/error :release-refused and the :problems, if
  a clause reference cannot be resolved, a clause is not :approved (unless
  `:allow-draft?`), a library clause floats a dependency, or -- when `:compose`
  is given -- the composed model has errors.

  `:compose` is {:base :root :deps}, as com.trustblocks.templatemark.compose
  takes them; its model is stored with the release and its hash is part of
  the SHA. Options: `:by`, `:note`, `:valid-from`."
  [node template-id & {:keys [rev compose allow-draft? by note valid-from]}]
  (let [t (or (templates/revision node template-id rev)
              (refuse :no-such-template (str "No template " template-id (when rev (str " at revision " rev))) {:template template-id}))
        lookup   (library/lookup node)
        composed (when compose
                   (compose/compose (assoc compose :doc (:tree t) :library lookup)))
        pinned   (release/release (:tree t) lookup :model-hash (model-hash (:model composed)))
        drafts   (when-not allow-draft?
                   (for [{:keys [clause version]} (:manifest pinned)
                         :let [s (library/status node clause version)]
                         :when (not= :approved s)]
                     {:rule :clause-not-approved :clause clause :version version :status s
                      :message (str "Clause " clause " version " version " is " (if s (name s) "unknown")
                                    ", not approved")}))
        problems (vec (concat (:problems pinned)
                              drafts
                              (when composed
                                (->> (:problems composed) (filter #(= :error (:severity %)))))))]
    (when (seq problems)
      (refuse :release-refused
              (str "Cannot release " template-id ": " (clojure.string/join "; " (map :message problems)))
              {:template template-id :problems problems}))
    (let [sha (:sha pinned), id (release-id sha)]
      (if-let [existing (get-release node sha)]
        (assoc existing :new? false)
        (let [vf (or (->instant valid-from) (java.time.Instant/now))]
          (xt/execute-tx
           node
           (into [[:put-docs {:into release-table :valid-from vf}
                   (cond-> {:xt/id id :template-id template-id :template-rev (:rev t)
                            :tree (pr-str (:doc pinned)) :manifest (pr-str (:manifest pinned))
                            :template-hash (:template-hash pinned) :sha sha}
                     (:model composed)   (assoc :model (pr-str (:model composed))
                                                 :model-hash (:model-hash pinned))
                     by   (assoc :author by)
                     note (assoc :note note))]]
                 (for [{:keys [clause version hash]} (:manifest pinned)]
                   [:put-docs {:into templates/use-table :valid-from vf}
                    {:xt/id (str "release:" sha "#" clause "@" version)
                     :owner-kind "release" :owner id :clause clause :pin version :hash hash :inline false}])))
          (assoc (get-release node sha) :new? true))))))

;; ------------------------------------------------------------------ deploy

(defn deploy!
  "Deploy `contract-id` against release `sha`, taking effect at `:valid-from`
  (default now). The release is verified first and must hold. Deploying a
  contract again, against a newer release, is an amendment: both stay in
  XTDB's history, and `release-of` answers for any date.

  The write asserts, inside the transaction, that the release exists."
  [node contract-id sha & {:keys [valid-from by note]}]
  (let [sha (if (.startsWith ^String sha "sha256:") (subs sha 7) sha)
        {:keys [ok? problems]} (verify node sha)]
    (when-not ok?
      (refuse :deploy-refused
              (str "Release " sha " does not hold: " (clojure.string/join "; " (map :message problems)))
              {:contract contract-id :release sha :problems problems}))
    (let [vf (or (->instant valid-from) (java.time.Instant/now))]
      (xt/execute-tx node
                     [[:sql "ASSERT EXISTS (SELECT 1 FROM release WHERE _id = ?)" [(release-id sha)]]
                      [:put-docs {:into deployed-table :valid-from vf}
                       (cond-> {:xt/id contract-id :release sha}
                         by (assoc :by by) note (assoc :note note))]])
      {:contract contract-id :release sha :valid-from vf})))

(defn release-of
  "The SHA of the release `contract-id` is deployed against at `:at` (default
  now) as understood at `:as-of`, or nil."
  [node contract-id & {:keys [at] :as opts}]
  (let [opts (assoc opts :at (or at (java.time.Instant/now)))]
    (:release (first (with-reads #(xt/q node (from-q deployed-table [{:xt/id contract-id} star] opts)))))))

(defn deployed-contracts
  "Every contract deployed against some release at `:at` / `:as-of`:
  [{:contract :release}]."
  [node & {:keys [at] :as opts}]
  (let [opts (assoc opts :at (or at (java.time.Instant/now)))]
    (->> (with-reads #(xt/q node (from-q deployed-table [star] opts)))
         (mapv (fn [r] {:contract (:xt/id r) :release (:release r)}))
         (sort-by :contract) vec)))

(defn deployed-with
  "Which deployed contracts contain clause `clause-id` (at `:version`, if
  given), at `:at` / `:as-of`: [{:contract :release :clause :version}]. The
  question to ask before replacing or retiring a clause."
  [node clause-id & {:keys [version at] :as opts}]
  (let [opts (assoc opts :at (or at (java.time.Instant/now)))
        uses (->> (with-reads #(xt/q node (from-q templates/use-table
                                                  [{:owner-kind "release" :clause clause-id} star] opts)))
                  (filter #(or (nil? version) (= version (:pin %)))))
        by-release (group-by #(subs (:owner %) 7) uses)]
    (->> (for [{:keys [contract release]} (deployed-contracts node opts)
               u (get by-release release)]
           {:contract contract :release release :clause clause-id :version (:pin u)})
         (sort-by (juxt :contract :version))
         vec)))

(defn check-active
  "Verify every release that a contract is currently deployed against
  (at `:at`, default now). Returns {:ok? :checked :failures}, a failure being
  {:release :contracts :problems}. Run it on a schedule, or before any change
  to the library, to know the clauses behind every contract in force are
  unchanged."
  [node & {:as opts}]
  (let [by-release (group-by :release (deployed-contracts node opts))
        failures   (vec (for [[sha cs] (sort-by key by-release)
                              :let [{:keys [ok? problems]} (verify node sha)]
                              :when (not ok?)]
                          {:release sha :contracts (mapv :contract cs) :problems problems}))]
    {:ok? (empty? failures) :checked (count by-release) :failures failures}))
