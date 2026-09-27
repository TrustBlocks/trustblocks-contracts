(ns com.trustblocks.model.timeline
  "A contract's timeline: everything that has happened to it, and what is
  due, as one list in time order -- what the contract dashboard draws, and
  what its slider moves along.

  Drawn from what is already on record, nothing new:

    :event        each execution -- a certified step, the contract's own
                  clause moving it -- with who certified it and how
                  (com.trustblocks.xtdb.execute/history)
    :designation  an officeholder designated to act on the matter
    :date         the dates the contract itself names (Notice to Proceed,
                  the Council's action ...), from its DateTime properties
    :due          a timed step the lifecycle will take if nothing else
                  happens first -- a payment overdue 30 days after
                  approval -- counted from when the document entered its
                  current status
    :note         a note, at the day it is about (com.trustblocks.xtdb.note)

  When the contract anchors a matter (com.trustblocks.xtdb.matter), its
  documents' events and due dates are on its timeline too -- STR-27's pay
  requests on STR-27 -- each marked with the document it belongs to."
  (:require [clojure.string :as str]
            [com.trustblocks.model.graph :as model.graph]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.note :as note]
            [com.trustblocks.xtdb.store :as store]))

(defn- instant [x]
  (try (execute/->instant x) (catch Exception _ nil)))

(defn- short-name [fqn] (subs (str fqn) (inc (str/last-index-of (str fqn) "."))))

(defn- words
  "PaymentApproved -> Payment approved; noticeToProceedDate -> Notice to
  proceed date."
  [s]
  (let [spaced (str/lower-case (str/replace s #"([a-z0-9])([A-Z])" "$1 $2"))]
    (str (str/upper-case (subs spaced 0 1)) (subs spaced 1))))

(defn- who
  "'Teresa Barringer, Zoning Administrator' for a DID or an email on file."
  [node {:keys [did email]}]
  (if-let [o (or (some->> did (identity/by-did node)) (some->> email (identity/by-email node)))]
    (str (:identity/name o) ", " (:identity/office o))
    (or email did)))

(defn- events [node document]
  (for [{:keys [at by request certification]} (execute/history node document)
        :let [t (instant at)] :when t]
    {:at       t
     :kind     :event
     :document document
     :title    (words (short-name (:concerto/class request)))
     :who      (who node (if certification {:did (:did certification)} {:email by}))
     :detail   (when certification
                 (str "Certified by " (str/lower-case (:credential certification))
                      " under " (:authority certification)))}))

(defn- designations [node matter-id]
  (for [d (cert/matter-record node matter-id)
        :when (= cert/designation-class (get d "$class"))]
    {:at     (get d "valid-from")
     :kind   :designation
     :title  (str "Designated: " (who node {:did (get d "subject")}))
     :who    (who node {:did (get d "issuer")})
     :detail (str (get d "capacity") " -- "
                  (str/join ", " (map #(str/lower-case (words (str/replace % "_" " ")))
                                      (get d "authority"))))}))

(defn- contract-dates [reg instance]
  (for [prop (model.graph/own-properties reg (get instance "$class"))
        :when (= "DateTimeProperty" (last (str/split (str (:$class prop)) #"\.")))
        :let [v (get instance (:name prop)) t (instant v)]
        :when t]
    {:at t :kind :date :title (words (:name prop)) :detail "A date the contract names"}))

(defn- due [node document now]
  (let [instance (store/get-instance node document)
        lc       (some->> (get instance "$class") (execute/get-lifecycle node))
        status   (get (execute/current-state node document) "status")
        entered  (when status (execute/state-entered-at node document now))]
    (for [t (get lc "transitions")
          :let [days (get t "afterDays")]
          :when (and days status entered (some #{status} (get t "from")))]
      {:at       (.plus ^java.time.Instant entered (java.time.Duration/ofDays days))
       :kind     :due
       :document document
       :title    (str (words (get t "event")) " -- if still " status)
       :detail   (str days " days after it became " status
                      "; the lifecycle's timed step, raised by the scheduler")})))

(defn- notes [node contract]
  (for [{:keys [author text about recorded]} (note/for-contract node contract)]
    {:at about :kind :note :title text :who (who node {:email author}) :recorded recorded}))

(defn matter-of-terms
  "The matter `id` anchors, or nil."
  [node id]
  (first (filter #(= id (:matter/terms %)) (matter/all node))))

(defn items
  "Contract `id`'s timeline, oldest first. `now` places the due dates."
  [node reg id now]
  (let [instance  (store/get-instance node id :reg reg)
        matter-id (:xt/id (matter-of-terms node id))
        documents (when matter-id (map :xt/id (matter/documents node matter-id)))]
    (->> (concat (events node id)
                 (mapcat #(events node %) documents)
                 (when matter-id (designations node matter-id))
                 (contract-dates reg instance)
                 (mapcat #(due node % now) (cons id documents))
                 (notes node id))
         (sort-by :at)
         vec)))

(defn next-steps
  "What can happen to `id` next, from its lifecycle and its status as of
  `at`: each transition with the certification it needs, or its days."
  [node id at]
  (let [instance (store/get-instance node id)
        lc       (some->> (get instance "$class") (execute/get-lifecycle node))
        status   (get (execute/current-state node id :at at) "status")]
    (for [t (get lc "transitions")
          :when (if status (some #{status} (get t "from")) (empty? (get t "from")))]
      {:event    (get t "event")
       :to       (get t "to")
       :requires (get t "requires")
       :days     (get t "afterDays")})))

(defn accepting?
  "Whether `status` is final in `id`'s lifecycle."
  [node id status]
  (let [lc (some->> (store/get-instance node id) (#(get % "$class")) (execute/get-lifecycle node))]
    (boolean (some #(and (= status (get % "name")) (get % "accepting")) (get lc "states")))))
