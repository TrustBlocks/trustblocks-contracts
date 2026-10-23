(ns com.trustblocks.xtdb.signoff
  "Sign-offs: the thumbs-up on a clause version, a template revision or a
  release. Not a credential and not an authority check -- the signed-in login
  says \"agreed\" (or \"object\"), with an optional role label and a note: the
  Manager approves, the Attorney agrees.

    (sign! node {:kind :clause :id \"indemnity@3\" :hash \"<sha>\"}
           :by \"attorney@town.gov\" :role \"Town Attorney\" :verdict :agree)

  A sign-off is about one exact thing. It records the subject's hash, so it
  is evidence of what was agreed to, not of a name that could later mean
  something else. Each person has one standing sign-off per subject; signing
  again replaces it (agree, then object, then agree) and XTDB keeps every one,
  so `history` shows the change of mind and when it was recorded.

  Who counts. A sign-off from the item's own author is recorded and shown but
  does not count toward `satisfied?` -- the point of a second look is that it
  is a second person. Objections always count: one outstanding objection
  stops `satisfied?` until that person agrees or the objection is withdrawn.

  The subject ids are the same ones the other namespaces use:
  :clause \"<id>@<version>\", :template \"<id>@<revision>\", :release the SHA."
  (:require [xtdb.api :as xt]))

(def table :signoff)

(def verdicts #{:agree :object :withdrawn})

(def ^:private star (symbol "*"))

(defn- refuse [error msg data]
  (throw (ex-info msg (assoc data :trustblocks/error error))))

(defn- unindexed? [e] (= :xtdb/unindexed-tx (:xtdb.error/code (ex-data e))))

(defn- with-reads [f]
  (loop [n 0]
    (let [r (try {:ok (f)} (catch Exception e (if (and (unindexed? e) (< n 20)) {:retry e} (throw e))))]
      (if (contains? r :ok) (:ok r) (do (Thread/sleep (inc n)) (recur (inc n)))))))

(defn- from-q [binding {:keys [at as-of]}]
  (list 'from table (cond-> {:bind binding}
                      at    (assoc :for-valid-time (list 'at at))
                      as-of (assoc :for-system-time (list 'at as-of)))))

(defn- subject-key [{:keys [kind id]}] (str (name kind) ":" id))

(defn- row-id [subject by] (str (subject-key subject) "/" by))

(defn- zdt->instant [^java.time.ZonedDateTime z] (some-> z .toInstant))

(defn sign!
  "Record that `:by` (a login) says `:verdict` (:agree, :object, or :withdrawn
  to take a sign-off back) about `subject` -- {:kind :id :hash}. Options:
  `:role` a label (\"Town Attorney\"), `:note` free text, `:author` the
  subject's own author, so the sign-off can be marked as not counting, and
  `:valid-from` (default now). Returns the stored sign-off."
  [node {:keys [kind id hash] :as subject} & {:keys [by role note author verdict valid-from]
                                              :or {verdict :agree}}]
  (when-not (#{:clause :template :release} kind)
    (refuse :invalid-signoff (str "Cannot sign off a " (pr-str kind)) {:kind kind}))
  (when-not (and (string? id) (string? hash))
    (refuse :invalid-signoff "A sign-off names a subject by :id and :hash" {:subject subject}))
  (when-not (and (string? by) (seq by))
    (refuse :invalid-signoff "A sign-off needs the login that gives it (:by)" {:subject subject}))
  (when-not (verdicts verdict)
    (refuse :invalid-signoff (str "Not a verdict: " (pr-str verdict)) {:verdict verdict}))
  (let [vf  (or valid-from (java.time.Instant/now))
        doc (cond-> {:xt/id (row-id subject by) :subject-kind (name kind) :subject id
                     :hash hash :by by :verdict (name verdict)
                     :own (boolean (and author (= author by)))}
              role (assoc :role role)
              note (assoc :note note))]
    (xt/execute-tx node [[:put-docs {:into table :valid-from vf} doc]])
    {:kind kind :id id :hash hash :by by :role role :note note :verdict verdict
     :own? (:own doc) :valid-from vf}))

(defn- ->signoff [r]
  (cond-> {:by (:by r) :verdict (keyword (:verdict r)) :hash (:hash r) :own? (boolean (:own r))
           :recorded-at (zdt->instant (:xt/system-from r))}
    (:role r) (assoc :role (:role r))
    (:note r) (assoc :note (:note r))))

(defn signoffs
  "The standing sign-offs on `subject` ({:kind :id}), as of `:at` / `:as-of`
  (default now), oldest first: {:by :role :verdict :note :own? :hash :recorded-at}.
  A withdrawn sign-off is not listed."
  [node subject & {:as opts}]
  (->> (with-reads #(xt/q node (from-q [{:subject-kind (name (:kind subject)) :subject (:id subject)}
                                        star 'xt/system-from] opts)))
       (sort-by (juxt :xt/system-from :by))
       (map ->signoff)
       (remove #(= :withdrawn (:verdict %)))
       vec))

(defn history
  "Everything anyone has said about `subject`, in the order it was recorded --
  agreements, objections, changes of mind, withdrawals -- with when each took
  effect and when it was recorded."
  [node subject]
  (->> (with-reads #(xt/q node (list 'from table
                                     {:for-valid-time :all-time :for-system-time :all-time
                                      :bind [{:subject-kind (name (:kind subject)) :subject (:id subject)}
                                             star 'xt/valid-from 'xt/system-from]})))
       ;; Replacing a record splits the old one's valid-time range, so a single
       ;; write comes back as two rows that share the time it was recorded;
       ;; keep the one that starts earliest.
       (group-by (juxt :by :verdict :note :xt/system-from))
       vals
       (map #(first (sort-by :xt/valid-from %)))
       (sort-by (juxt :xt/system-from :by))
       (mapv #(assoc (->signoff %) :valid-from (zdt->instant (:xt/valid-from %))))))

(defn summary
  "{:agree [logins] :object [logins] :others-agree n} for `subject`: who has
  agreed (the author's own included, marked by :others-agree not counting
  them), who objects."
  [node subject & {:as opts}]
  (let [s (signoffs node subject opts)]
    {:agree        (mapv :by (filter #(= :agree (:verdict %)) s))
     :object       (mapv :by (filter #(= :object (:verdict %)) s))
     :others-agree (count (filter #(and (= :agree (:verdict %)) (not (:own? %))) s))}))

(defn satisfied?
  "Has `subject` the sign-offs `requirement` asks? The requirement is a
  number (that many agreements from people other than its author), or a map
  {:agree n :roles #{\"Town Attorney\" ...}} -- n agreements, and at least one
  from each of those roles. An outstanding objection fails either way.
  Returns {:ok? :missing [...] :objections [...]}."
  [node subject requirement & {:as opts}]
  (let [{:keys [agree roles]} (if (map? requirement) requirement {:agree requirement})
        s        (signoffs node subject opts)
        counting (filter #(and (= :agree (:verdict %)) (not (:own? %))) s)
        objects  (mapv :by (filter #(= :object (:verdict %)) s))
        missing  (cond-> []
                   (< (count counting) (or agree 0))
                   (conj (str "needs " (or agree 0) " agreement(s) from others, has " (count counting)))
                   true (into (for [role roles
                                    :when (not-any? #(= role (:role %)) counting)]
                                (str "needs an agreement from " role))))]
    {:ok? (and (empty? missing) (empty? objects))
     :missing missing
     :objections objects}))
