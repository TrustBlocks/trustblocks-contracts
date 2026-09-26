(ns com.trustblocks.xtdb.advance
  "Moving a document through its lifecycle by certifying an event -- the
  one path from an officeholder's signed act to a document's next state.

  The template says what an event requires (com.trustblocks.lifecycle): a
  Receipt or an Attestation, under a named authority. This namespace is the
  environment's part of that bargain, and nothing more:

  1. who is acting -- the officeholder signed in as `email`;
  2. whether they hold one of the authorities the event accepts, at the
     event's own time: through their own OfficeGrant, or a Designation for
     the document's matter (com.trustblocks.xtdb.certification/
     authority-for);
  3. trigger the document with that certification, so its lifecycle and
     clause decide (com.trustblocks.xtdb.execute/trigger!);
  4. sign the Receipt or Attestation that records the act, naming the
     credential that authorized it as evidence.

  Nothing here knows what a pay request is. A new document type is a new
  template with its own lifecycle; this is unchanged.

  Refusals carry :trustblocks/error, as execute's do."
  (:require [com.trustblocks.lifecycle :as lifecycle]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.receipt :as receipt]
            [com.trustblocks.xtdb.store :as store]))

(defn- refuse! [message data]
  (throw (ex-info message data)))

(defn- request-id
  "Deterministic, so a retried certification is answered as a duplicate
  rather than run twice."
  [document event at]
  (str "advance:" document ":" (lifecycle/event-name event) ":" at))

(defn- authorization
  "The first requirement of `requires` for `credential` that `did` meets at
  `at`, as {:authority :jwt} -- :jwt being the authorizing credential."
  [node did credential requires at matter-id]
  (some (fn [{:strs [authority] :as r}]
          (when (= credential (get r "credential"))
            (when-let [{:keys [jwt]} (cert/authority-for node did authority at :matter matter-id)]
              {:authority authority :jwt jwt})))
        requires))

(defn advance!
  "The officeholder signed in as `email` certifies `event` (a Request
  instance) on stored document instance `document`, at `at` (an Instant),
  with a `credential` of \"Receipt\" or \"Attestation\". Returns
  {:state :response :credential <signed JWT>}.

  For a Receipt: `document-hash` is the stored file received, from
  `received-from`, optionally with a `description`. For an Attestation:
  `statement`, and `document-hash` when it is not the document's own stored
  file, which it names by default.

  Refusals: :no-such-officeholder, :no-such-instance, :no-lifecycle,
  :no-transition, :missing-authority (holds none of the authorities the
  event accepts, as this credential), and a Receipt's :no-such-matter,
  :no-such-stored-document and :already-received -- then any of
  execute/trigger!'s, including a clause's refusal."
  [node {:keys [email document event credential at document-hash statement
                received-from description]}]
  (let [officeholder (or (identity/by-email node email)
                         (refuse! "No officeholder identity for this email"
                                  {:trustblocks/error :no-such-officeholder :email email}))
        did       (:xt/id officeholder)
        instance  (or (store/get-instance node document)
                      (refuse! "No such document"
                               {:trustblocks/error :no-such-instance :instance document}))
        lc        (or (execute/get-lifecycle node (get instance "$class"))
                      (refuse! "This document's template declares no lifecycle"
                               {:trustblocks/error :no-lifecycle :$class (get instance "$class")}))
        matter-id (matter/matter-of node document :at at)
        status    (get (execute/current-state node document :at at) "status")
        requires  (lifecycle/requirements lc status event)
        ;; An attestation names the file of the document it certifies,
        ;; unless the caller names another.
        document-hash (or document-hash
                          (some #(when (= document (:xt/id %)) (:stored-document %))
                                (when matter-id (matter/documents node matter-id :at at))))
        {:keys [authority jwt]}
        (or (authorization node did credential requires at matter-id)
            (refuse! (str "Not authorized to certify " (lifecycle/event-name event)
                          " with a " credential " on this document at that time")
                     {:trustblocks/error :missing-authority :did did
                      :requires requires :matter matter-id}))]
    (when (and (= "Attestation" credential) (not document-hash))
      (refuse! "An attestation names the stored file it certifies, and this document has none"
               {:trustblocks/error :no-such-stored-document :document document}))
    (when (= "Receipt" credential)
      (when-not matter-id
        (refuse! "A receipt is made on a matter, and this document belongs to none"
                 {:trustblocks/error :no-such-matter :document document}))
      (receipt/check-receivable! node matter-id document-hash))
    (let [{:keys [state response]}
          (execute/trigger! node document event (str at)
                            :request-id (request-id document event at)
                            :by email
                            :certification {:credential credential :authority authority :did did})
          signed
          (if (= "Receipt" credential)
            (receipt/issue! node officeholder
                            {:matter matter-id :document-hash document-hash
                             :received-from received-from :description description
                             :received-at at :evidence jwt})
            (credential/issue!
             node (identity/keypair officeholder)
             {:issuer did :subject did
              :credential-subject (cond-> {"$class"       cert/attestation-class
                                           "id"           did
                                           "documentHash" document-hash
                                           "statement"    statement
                                           "contractId"   document
                                           "certifiedAt"  (str (java.time.Instant/now))}
                                    matter-id (assoc "matterId" matter-id))
              :valid-from at :valid-until (java.time.Instant/parse "9999-12-31T00:00:00Z")
              :evidence jwt}))]
      {:state state :response response :credential signed})))
