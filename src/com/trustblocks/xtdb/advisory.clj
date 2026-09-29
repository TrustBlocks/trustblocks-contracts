(ns com.trustblocks.xtdb.advisory
  "What an AI agent can do: advise, in writing, signed.

  An agent (com.trustblocks.xtdb.identity/provision-agent!) holds no office.
  It acts on a matter only under a Designation carrying ADVISE -- from an
  officeholder who holds ADVISE themselves, for that matter and a period
  (com.trustblocks.xtdb.certification/designate!) -- and what it produces is
  an Advisory: a note on one of the matter's documents, signed with the
  agent's own key, naming its designation as evidence.

  Advice, never a determination. An Advisory moves nothing: no lifecycle
  step accepts one, and every step requires an authority no agent can hold.
  Whether a deadline was missed or the figures agree is the contract's own
  code; what the Town does about it is an officeholder's decision. The
  agent's part is to read, notice and say so -- \"this request arrived
  late, and the last one was returned\" -- on the record, under its own
  name, for the person who decides."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.store :as store]))

(def advisory-class "com.trustblocks.identity@1.0.0.Advisory")

(def ^:private open-ended (java.time.Instant/parse "9999-12-31T00:00:00Z"))

(defn- refuse! [message data]
  (throw (ex-info message data)))

(defn advise!
  "Agent `agent` (its DID) advises on `document`, a document of a matter, at
  `at`: `text`, and `document-hash` when it read a stored file. Returns the
  signed Advisory.

  Refusals, tagged :trustblocks/error: :not-an-agent, :no-such-instance,
  :no-such-matter, :missing-authority (no Designation for this matter
  carrying ADVISE in force at `at`, or its designator no longer holds
  ADVISE), :invalid-advisory (no text), :invalid-proposal (a `proposal`
  that is not a valid instance of the document's own type).

  `proposal`, optionally, is the document's data as the agent read it from
  the stored file -- a Concerto instance of the document's type (JSON
  data), for a person to compare and confirm. It is checked against the
  document's model before anything is signed, and it is advice like the
  rest: nothing applies it."
  [node {:keys [agent document text document-hash proposal at]}]
  (let [identity (identity/by-did node agent)]
    (when-not (and identity (identity/agent? identity))
      (refuse! "Only an AI agent's identity signs an Advisory"
               {:trustblocks/error :not-an-agent :did agent}))
    (when (str/blank? text)
      (refuse! "An Advisory needs some text" {:trustblocks/error :invalid-advisory}))
    (when-not (store/get-instance node document)
      (refuse! "No such document" {:trustblocks/error :no-such-instance :instance document}))
    (when proposal
      (let [fqn (get (store/get-instance node document) "$class")]
        (when (not= fqn (get proposal "$class"))
          (refuse! "A proposal is an instance of the document's own type"
                   {:trustblocks/error :invalid-proposal :expected fqn :given (get proposal "$class")}))
        (when-let [errs (store/validate (store/registry-from-xtdb node) proposal)]
          (refuse! "The proposed values do not fit the document's model"
                   {:trustblocks/error :invalid-proposal :errors errs}))))
    (let [matter-id (or (matter/matter-of node document :at at)
                        (refuse! "An Advisory concerns a document of a matter, and this is in none"
                                 {:trustblocks/error :no-such-matter :document document}))
          {:keys [jwt]} (or (cert/authority-for node agent "ADVISE" at :matter matter-id)
                            (refuse! "Not designated to advise on this matter at that time"
                                     {:trustblocks/error :missing-authority :did agent
                                      :required "ADVISE" :matter matter-id}))]
      (credential/issue!
       node (identity/keypair identity)
       {:issuer agent :subject agent
        :credential-subject (cond-> {"$class"      advisory-class
                                     "id"          agent
                                     "matterId"    matter-id
                                     "contractId"  document
                                     "text"        text
                                     "certifiedAt" (str (java.time.Instant/now))}
                              document-hash (assoc "documentHash" document-hash)
                              proposal      (assoc "proposedData" (json/generate-string proposal)))
        :valid-from at :valid-until open-ended
        :evidence jwt}))))

(defn for-document
  "The verified Advisories on `document` -- or, when `document` is a
  matter's terms, on every document of its matter -- oldest first: the
  credentialSubject plus \"issuer\", \"valid-from\" and \"evidence\" (the
  designation, decoded), as certification/matter-record gives them."
  [node document]
  (let [terms-of  (first (filter #(= document (:matter/terms %)) (matter/all node)))
        matter-id (or (:xt/id terms-of) (matter/matter-of node document))]
    (when matter-id
      (->> (cert/matter-record node matter-id)
           (filter #(= advisory-class (get % "$class")))
           (filter #(or terms-of (= document (get % "contractId"))))
           (sort-by #(get % "valid-from"))
           vec))))
