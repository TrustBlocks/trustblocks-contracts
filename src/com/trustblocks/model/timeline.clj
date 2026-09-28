(ns com.trustblocks.model.timeline
  "A contract's timeline: everything that has happened to it, and what is
  due, as one list in time order -- what the contract dashboard draws, and
  what its slider moves along.

  Drawn from what is already on record, nothing new:

    :event        each execution -- a certified step, the contract's own
                  clause moving it -- with who certified it and how
                  (com.trustblocks.xtdb.execute/history)
    :designation  an officeholder designated to act on the matter
    :receipt      a document received on the matter that moves no
                  lifecycle -- an insurance certificate, a W-9 -- with the
                  role it plays in the package (com.trustblocks.xtdb.receipt);
                  a receipt that moved a lifecycle is already its :event
    :date         the dates the contract itself names (Notice to Proceed,
                  the Council's action ...), from its DateTime properties
    :deadline     a date the contract sets -- a payment due 30 days after
                  approval, the Completion Date -- shown, never acted on
                  (com.trustblocks.model.deadlines)
    :note         a note, at the day it is about (com.trustblocks.xtdb.note)
    :advisory     an AI agent's signed advice on a document, under its
                  designation (com.trustblocks.xtdb.advisory) -- advice,
                  never a determination

  When the contract anchors a matter (com.trustblocks.xtdb.matter), its
  documents' events and deadlines are on its timeline too -- STR-27's pay
  requests on STR-27 -- each marked with the document it belongs to."
  (:require [clojure.string :as str]
            [com.trustblocks.model.properties :as model.properties]
            [com.trustblocks.xtdb.execute :as execute]))

;; Pure: everything here is built from what com.trustblocks.model.reads
;; resolves (:contract/timeline). People are refs -- {:identity/did ...} or
;; {:identity/email ...} under :who, :subject and :by -- so a page's one
;; graph query resolves each person once, however many items name them;
;; `finish` then writes the names in.

(defn- instant [x]
  (try (execute/->instant x) (catch Exception _ nil)))

(defn- short-name [fqn] (subs (str fqn) (inc (str/last-index-of (str fqn) "."))))

(defn- words
  "PaymentApproved -> Payment approved; noticeToProceedDate -> Notice to
  proceed date."
  [s]
  (let [spaced (str/lower-case (str/replace s #"([a-z0-9])([A-Z])" "$1 $2"))]
    (str (str/upper-case (subs spaced 0 1)) (subs spaced 1))))

(defn- events
  "Each execution of `document`: `history` as com.trustblocks.model.reads
  gives it, each execution with :who, the person who took the step."
  [document history]
  (for [{:keys [at request certification who]} history
        :let [t (instant at)] :when t]
    {:at       t
     :kind     :event
     :document document
     :title    (words (short-name (:concerto/class request)))
     :who      who
     :detail   (when certification
                 (str "Certified by " (str/lower-case (:credential certification))
                      " under " (:authority certification)))}))

(defn- designations [record]
  (for [d record
        ;; certification/designation-class, not required here: this
        ;; namespace reads nothing.
        :when (= "com.trustblocks.identity@1.0.0.Designation" (get d "$class"))]
    {:at      (get d "valid-from")
     :kind    :designation
     :title   "Designated: "
     :subject {:identity/did (get d "subject")}
     :who     {:identity/did (get d "issuer")}
     :detail  (str (get d "capacity") " -- "
                   (str/join ", " (map #(str/lower-case (words (str/replace % "_" " ")))
                                       (get d "authority"))))}))

(defn- receipt-items [receipts]
  (for [r receipts
        :when (not (get r "contractId"))]
    {:at     (get r "received-at")
     :kind   :receipt
     :title  (str "Received: " (or (get r "description") (get r "documentRole") "a document"))
     :who    {:identity/did (get r "issuer")}
     :detail (str "From " (get r "receivedFrom")
                  (when-let [role (get r "documentRole")] (str " -- " role)))}))

(defn- contract-dates [reg instance]
  (for [prop (model.properties/own-properties reg (get instance "$class"))
        :when (= "DateTimeProperty" (last (str/split (str (:$class prop)) #"\.")))
        :let [v (get instance (:name prop)) t (instant v)]
        :when t]
    {:at t :kind :date :title (words (:name prop)) :detail "A date the contract names"}))

(defn- deadline-items [deadlines]
  (for [{:keys [name description document due status]} deadlines
        :when due]
    {:at       due
     :kind     :deadline
     :document document
     :status   status
     :title    (str name (case status :met " -- met" :passed " -- passed" ""))
     :detail   description}))

(defn- note-items [notes]
  (for [{:keys [author text about recorded]} notes]
    {:at about :kind :note :title text :who {:identity/email author} :recorded recorded}))

(defn- advisory-items [advisories]
  (for [a advisories]
    {:at       (get a "valid-from")
     :kind     :advisory
     :document (get a "contractId")
     :title    (get a "text")
     :who      {:identity/did (get a "issuer")}
     :by       {:identity/did (get-in a ["evidence" "issuer"])}
     :detail   "Advice, not a determination -- designated to advise by "}))

(defn assemble
  "Contract `id`'s timeline, oldest first, people as refs.

    instance    the contract (Concerto JSON), reg its registry
    history     its executions
    documents   when it anchors a matter: [[document-id history] ...]
    record      its matter's verified record; receipts its receipts
    deadlines   its deadlines and its documents' (model.deadlines/compute)
    notes, advisories"
  [{:keys [id reg instance history documents record receipts deadlines notes advisories]}]
  (->> (concat (events id history)
               (mapcat (fn [[doc h]] (events doc h)) documents)
               (designations record)
               (receipt-items receipts)
               (contract-dates reg instance)
               (deadline-items deadlines)
               (note-items notes)
               (advisory-items advisories))
       (sort-by :at)
       vec))

(defn finish
  "An item whose people the graph has resolved (each ref now carrying
  :identity/label, and :identity/name for an agent), with the names written
  into it: :who as 'Teresa Barringer, Zoning Administrator' -- or, for an AI
  agent's advice, its name and '(AI agent)' -- a designation's title, and
  whom an agent's advice is designated by."
  [{:keys [kind who subject by] :as item}]
  (cond-> (dissoc item :who :subject :by)
    who     (assoc :who (if (= :advisory kind)
                          (str (:identity/name who) " (AI agent)")
                          (:identity/label who)))
    subject (update :title str (:identity/label subject))
    by      (update :detail str (:identity/label by))))

(defn accepting?
  "Whether `status` is final in `lifecycle`."
  [lifecycle status]
  (boolean (some #(and (= status (get % "name")) (get % "accepting")) (get lifecycle "states"))))
