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
            [com.trustblocks.templatemark.model :as model]
            [com.trustblocks.xtdb.defaults :as defaults]
            [com.trustblocks.xtdb.lifecycle-check :as lifecycle-check]
            [com.trustblocks.xtdb.model-repository :as repo]
            [com.trustblocks.xtdb.signoff :as signoff]
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

(defn- states-hash [states]
  (when (seq states) (clauses/sha-256 (pr-str (clauses/canonical (vec states))))))

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
        (:signoffs r)   (assoc :signoffs (edn/read-string (:signoffs r)))
        (:model-hash r) (assoc :model-hash (:model-hash r))
        (:defaults r)   (assoc :defaults (edn/read-string (:defaults r))
                               :defaults-hash (:defaults-hash r))
        (:pinned-model r) (assoc :pinned {:model (:pinned-model r) :root (:pinned-root r)
                                          :pins (edn/read-string (:pins r))}
                                 :pins-hash (:pins-hash r))
        (:states r)     (assoc :states (edn/read-string (:states r))
                               :states-hash (:states-hash r))
        (:model r)      (assoc :model (edn/read-string (:model r)))))))

(defn verify
  "Does release `sha` still hold? {:ok? :problems}. Re-reads every clause the
  release pinned from the library and recomputes every hash; also checks the
  stored tree and model against the hashes the release recorded. Problems:
  :sha-mismatch, :clause-changed, :clause-missing, :clause-corrupt (a stored
  clause no longer matches its own hash), :template-changed, :model-changed,
  :defaults-altered, :defaults-changed, :defaults-missing, :defaults-corrupt
  (see defaults/verify-snapshot), :pins-altered, :model-missing,
  :model-changed (a pinned model; see model-repository/verify-pins),
  :model-corrupt, :no-such-release."
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
                           [{:rule :model-changed :message "The stored model no longer matches the release's model hash"}])
                         (when (and (:states r) (not= (:states-hash r) (states-hash (:states r))))
                           [{:rule :states-altered :message "The stored statements no longer match the release's states hash"}])
                         (when (:defaults r)
                           (concat
                            (when (not= (:defaults-hash r) (defaults/snapshot-hash (:defaults r)))
                              [{:rule :defaults-altered :message "The stored defaults no longer match the release's defaults hash"}])
                            (defaults/verify-snapshot node (:defaults r))))
                         (when-let [{:keys [pins]} (:pinned r)]
                           (concat
                            (when (not= (:pins-hash r) (repo/pins-hash pins))
                              [{:rule :pins-altered :message "The stored model pins no longer match the release's pins hash"}])
                            (for [{:keys [problem model]} (repo/verify-pins node pins)]
                              {:rule problem :model model
                               :message (str "Model " model ": " (name problem))})))))]
      {:ok? (empty? problems) :problems problems})
    {:ok? false :problems [{:rule :no-such-release :message (str "No release " sha)}]}))

;; ------------------------------------------------------------------ release

(defn release!
  "Release revision `rev` (default: the head) of `template-id`. Returns the
  stored release (see get-release) with :new? saying whether it was written or
  already existed (an identical pinned template is the same release).

  Refuses, with :trustblocks/error :release-refused and the :problems, if
  a clause reference cannot be resolved, a clause is not :approved (unless
  `:allow-draft?`), a clause lacks the sign-offs `:require-signoffs` asks of it
  (a number of agreements from people other than the clause's author, or
  {:agree n :roles #{...}}; see signoff/satisfied?), a library clause floats a
  dependency, or -- when `:compose`
  is given -- the composed model has errors.

  `:compose` is {:base :root :deps}, as com.trustblocks.templatemark.compose
  takes them; its model is stored with the release and its hash is part of
  the SHA.

  `:defaults` is a vector of [id pin] (see xtdb.defaults/prefill) naming the
  default data the contract was drafted from. Each is resolved to an exact
  version once; the release stores the versions and their values, and their
  hash is part of the SHA, so a later change to the Town's defaults cannot
  alter this contract and verify says if the stored values were touched.
  A template saved against a model (template-tree/save! `:model`) is held
  to it: a placeholder that does not resolve refuses the release
  (:template-does-not-fit-model), as does a clause that requires a model the
  template is not written against (:clause-model-not-pinned). The model and
  everything it imports are pinned -- exact versions and hashes -- stored with
  the release, and their hash is part of the SHA; `verify` says if a pinned
  model has since changed.

  `:lifecycle` (a lifecycle.json map) asks whether the text states every
  certification the lifecycle requires (see xtdb.lifecycle-check). What the
  text states is each clause's `:clause/states` plus `:states`, the template's
  own, a list of {:event :authority}; `:states` is part of the SHA, so the same
  template released with a different statement is a different release. The
  gaps come back as `:findings` on the release; with `:require-stated?` they
  refuse it.

  Options: `:by`, `:note`, `:valid-from`."
  [node template-id & {:keys [rev compose allow-draft? require-signoffs by note valid-from lifecycle states require-stated?]
                       refs :defaults}]
  (let [t (or (templates/revision node template-id rev)
              (refuse :no-such-template (str "No template " template-id (when rev (str " at revision " rev))) {:template template-id}))
        lookup   (library/lookup node)
        composed (when compose
                   (compose/compose (assoc compose :doc (:tree t) :library lookup)))
        snap     (when (seq refs) (defaults/snapshot node refs :at (->instant valid-from)))
        model-ref (:model t)
        pins-result (when model-ref
                      (try {:pins (repo/pins node model-ref)}
                           (catch clojure.lang.ExceptionInfo e
                             {:problem {:rule (or (:trustblocks/error (ex-data e)) :model-missing)
                                        :message (str "Template " template-id " is written against " model-ref ": " (ex-message e))}})))
        pins     (:pins pins-result)
        fit      (when pins
                   (let [reg (repo/registry node model-ref)
                         root-fqn (str model-ref "." (:root t))]
                     (for [{:keys [node-id message]} (model/errors (model/typecheck (:tree t) reg root-fqn :library lookup))]
                       {:rule :template-does-not-fit-model :node-id node-id
                        :message (str "Template " template-id " does not fit " model-ref ": " message)})))
        pinned   (release/release (:tree t) lookup :model-hash (model-hash (:model composed))
                                  :defaults-hash (defaults/snapshot-hash snap)
                                  :pins-hash (:hash pins)
                                  :states-hash (states-hash states))
        manifest-clauses (for [{:keys [clause version]} (:manifest pinned)] (library/get-version node clause version))
        model-mismatch (when pins
                         (for [c manifest-clauses
                               :let [req (:model (:clause/requires c))]
                               :when (and req (not (contains? (:pins pins) req)))]
                           {:rule :clause-model-not-pinned :clause (:clause/id c) :version (:clause/version c)
                            :message (str "Clause " (:clause/id c) " version " (:clause/version c) " requires " req
                                          ", which " model-ref " does not import")}))
        stated   (concat (mapcat :clause/states manifest-clauses) states)
        findings (when lifecycle (lifecycle-check/unstated lifecycle stated))
        drafts   (when-not allow-draft?
                   (for [{:keys [clause version]} (:manifest pinned)
                         :let [s (library/status node clause version)]
                         :when (not= :approved s)]
                     {:rule :clause-not-approved :clause clause :version version :status s
                      :message (str "Clause " clause " version " version " is " (if s (name s) "unknown")
                                    ", not approved")}))
        clause-signoffs (into {} (for [{:keys [clause version]} (:manifest pinned)]
                                   [(str clause "@" version)
                                    (mapv #(select-keys % [:by :role :verdict :own?])
                                          (library/signoffs node clause version))]))
        unsigned (when require-signoffs
                   (for [{:keys [clause version]} (:manifest pinned)
                         :let [{:keys [ok? missing objections]}
                               (signoff/satisfied? node {:kind :clause :id (str clause "@" version)} require-signoffs)]
                         :when (not ok?)]
                     {:rule (if (seq objections) :clause-objected :clause-needs-signoff)
                      :clause clause :version version :objections objections
                      :message (str "Clause " clause " version " version
                                    (if (seq objections)
                                      (str " has an objection from " (clojure.string/join ", " objections))
                                      (str " " (clojure.string/join "; " missing))))}))
        problems (vec (concat (:problems pinned)
                              (some-> pins-result :problem vector)
                              fit
                              model-mismatch
                              (when require-stated? findings)
                              drafts
                              unsigned
                              (when composed
                                (->> (:problems composed) (filter #(= :error (:severity %)))))))]
    (when (seq problems)
      (refuse :release-refused
              (str "Cannot release " template-id ": " (clojure.string/join "; " (map :message problems)))
              {:template template-id :problems problems}))
    (let [sha (:sha pinned), id (release-id sha)]
      (if-let [existing (get-release node sha)]
        (assoc existing :new? false :findings findings)
        (let [vf (or (->instant valid-from) (java.time.Instant/now))]
          (xt/execute-tx
           node
           (into [[:put-docs {:into release-table :valid-from vf}
                   (cond-> {:xt/id id :template-id template-id :template-rev (:rev t)
                            :tree (pr-str (:doc pinned)) :manifest (pr-str (:manifest pinned))
                            :template-hash (:template-hash pinned) :sha sha}
                     (:model composed)   (assoc :model (pr-str (:model composed))
                                                 :model-hash (:model-hash pinned))
                     (seq snap)  (assoc :defaults (pr-str snap) :defaults-hash (:defaults-hash pinned))
                     pins        (assoc :pinned-model model-ref :pinned-root (:root t)
                                        :pins (pr-str (:pins pins)) :pins-hash (:hash pins))
                     (seq states) (assoc :states (pr-str (vec states)) :states-hash (:states-hash pinned))
                     (seq clause-signoffs) (assoc :signoffs (pr-str clause-signoffs))
                     by   (assoc :author by)
                     note (assoc :note note))]]
                 (for [{:keys [clause version hash]} (:manifest pinned)]
                   [:put-docs {:into templates/use-table :valid-from vf}
                    {:xt/id (str "release:" sha "#" clause "@" version)
                     :owner-kind "release" :owner id :clause clause :pin version :hash hash :inline false}])))
          (assoc (get-release node sha) :new? true :findings findings))))))

;; ------------------------------------------------------------------ findings

(defn findings
  "The certifications `lifecycle` requires that release `sha`'s text does not
  state: see lifecycle-check/unstated. What the release states is each pinned
  clause's `:clause/states` and the `:states` it was released with."
  [node sha lifecycle]
  (when-let [r (get-release node sha)]
    (lifecycle-check/unstated
     lifecycle
     (concat (mapcat #(:clause/states (library/get-version node (:clause %) (:version %))) (:manifest r))
             (:states r)))))

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

(defn sign!
  "The signed-in `:by` says `:verdict` about release `sha`: the Manager
  approves deploying it, the Attorney agrees. Recorded against the release's
  own hash (see com.trustblocks.xtdb.signoff)."
  [node sha & {:as opts}]
  (let [sha (if (.startsWith ^String sha "sha256:") (subs sha 7) sha)]
    (when-not (get-release node sha)
      (refuse :no-such-release (str "No release " sha) {:release sha}))
    (apply signoff/sign! node {:kind :release :id sha :hash sha} (mapcat identity opts))))

(defn signoffs
  "The standing sign-offs on release `sha`."
  [node sha & {:as opts}]
  (signoff/signoffs node {:kind :release :id (if (.startsWith ^String sha "sha256:") (subs sha 7) sha)} opts))

;; ------------------------------------------------------------- attestations

(defn attestation-subject
  "What an officeholder should attest to for `contract-id`: the release it is
  deployed against, as `sha256:<hex>` -- the value for an Attestation's
  documentHash -- or nil if it is not deployed. Attesting to the release
  rather than to a typed reference makes the attestation about exactly the
  words, clauses, model and defaults that were released."
  [node contract-id & {:as opts}]
  (some-> (apply release-of node contract-id (mapcat identity opts)) release-id))

(defn verify-attested
  "Does what an Attestation attested to still hold? `document-hash` is its
  documentHash. If it names a release (`sha256:<hex>`), the release is
  verified: {:release sha :ok? :problems}. Otherwise it is a reference to
  something else (a stored file, say) and this says so: {:ok? nil}."
  [node document-hash]
  (if (and (string? document-hash) (re-matches #"sha256:[0-9a-f]{64}" document-hash))
    (assoc (verify node document-hash) :release (subs document-hash 7))
    {:ok? nil :note "not a release; nothing to verify here"}))
