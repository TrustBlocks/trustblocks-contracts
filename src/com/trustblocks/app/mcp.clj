(ns com.trustblocks.app.mcp
  "Trustblocks as an MCP server (Model Context Protocol, Streamable HTTP):
  POST /mcp, JSON-RPC 2.0. How an AI agent -- Muny -- reads the record and
  advises on it, through whatever MCP client runs the model: Claude Code or
  Claude Desktop today, a self-hosted model later. The app holds no model
  and no API key.

  The bearer token is the agent (com.trustblocks.xtdb.identity/
  issue-agent-token!): every call acts as that agent's DID, and nothing
  else. So what the tools can do is what an agent can do:

    read     list_matters, matter_overview, contract, read_document,
             template_schema -- through the same graph the pages read
             (com.trustblocks.model.reads) -- and sql: a read-only query,
             echoed back with its rows, so what was asked is visible
    advise   advise, and propose_vendor_form: sign an Advisory
             (com.trustblocks.xtdb.advisory) under the agent's ADVISE
             designation for the matter. A proposal is a form's values as
             read from its received file, checked against the template's
             model first -- its errors come back so the agent can correct
             them -- and applied by no one but the person who confirms it.

  Nothing here certifies, triggers or changes a document: those need an
  authority no agent can hold.

  On Biff's API route stack, which has no session and no CSRF check: the
  token is the credential, and a request without a known one is refused
  with 401 before anything is read."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.trustblocks.model.malli :as model.malli]
            [com.trustblocks.model.reads :as reads]
            [com.trustblocks.model.timeline :as timeline]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.runtime :as runtime]
            [com.trustblocks.xtdb.advisory :as advisory]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]))

(def protocol-versions ["2025-06-18" "2025-03-26" "2024-11-05"])

(def instructions
  "You are Muny, an AI agent working for the Town of China Grove, North
Carolina, on its municipal contracts in Trustblocks. You hold no office. You
may read every matter, contract, deadline and stored document, and you may
advise -- sign a note on a document, or propose a form's values as read from
its received file -- under your ADVISE designation for that matter. You
never certify, approve or change anything: that is always a Town officer's
decision, recorded under their own authority. Say what you read, what you
noticed and how sure you are; propose, never decide.")

;; ---------------------------------------------------------------- helpers

(defn- jsonable
  "Instants, dates and keywords as JSON-friendly strings."
  [x]
  (walk/postwalk
   (fn [v]
     (cond
       (instance? java.time.Instant v)       (str v)
       (instance? java.time.ZonedDateTime v) (str (.toInstant ^java.time.ZonedDateTime v))
       (instance? java.util.Date v)          (str (.toInstant ^java.util.Date v))
       (instance? java.util.UUID v)          (str v)
       (keyword? v)                          (subs (str v) 1)
       :else                                 v))
   x))

(defn- text-result [data]
  {:content [{:type "text" :text (json/generate-string (jsonable data) {:pretty true})}]})

(defn- error-result [message & [data]]
  {:content [{:type "text"
              :text (str message (when data
                                   (str "\n" (json/generate-string (jsonable data) {:pretty true}))))}]
   :isError true})

(defn- instant [s]
  (when (seq s) (java.time.Instant/parse s)))

(defn- stringify
  "Tool arguments as Concerto JSON: muuntaja keywordized the keys on the
  way in; a $class is a string key again."
  [x]
  (walk/postwalk #(if (keyword? %) (subs (str %) 1) %) x))

;; ---------------------------------------------------------------- tools

(defn- list-matters [{:keys [biff.xtdb/node]} _ _]
  (text-result
   (for [{:keys [xt/id] :matter/keys [name package terms]} (matter/all node)]
     {:matter_id id :name name :package package :terms_contract terms})))

(defn- matter-overview [ctx _ {:strs [matter_id as_of]}]
  (let [d (reads/query ctx {:matter/id matter_id}
                       [[:? :matter/name] [:? :matter/package]
                        {[:? :matter/terms] [:contract/id {[:? :contract/all-deadlines] [:*]}]}
                        {:matter/documents-as-of [:contract/id :membership/role :membership/joined
                                                  [:? :membership/file] {[:? :contract/state] [:*]}]}
                        {[:? :matter/checklist] [:*]}]
                       :as-of (instant as_of))]
    (if-not (:matter/terms d)
      (error-result (str "No matter " matter_id))
      (text-result
       {:matter_id      matter_id
        :name           (:matter/name d)
        :package        (:matter/package d)
        :terms_contract (get-in d [:matter/terms :contract/id])
        :documents      (for [x (:matter/documents-as-of d)]
                          {:document_id (:contract/id x)
                           :role        (:membership/role x)
                           :joined      (:membership/joined x)
                           :status      (get-in x [:contract/state "status"])
                           :stored_file (:membership/file x)})
        :checklist      (for [row (get-in d [:matter/checklist :documents])]
                          {:role      (:role row)
                           :title     (:title row)
                           :phase     (:phase row)
                           :required  (:required row)
                           :standing  (:standing row)
                           :on_record (for [e (:on-record row)]
                                        (select-keys e [:kind :id :at :status :from :file]))})
        :deadlines      (for [x (get-in d [:matter/terms :contract/all-deadlines])]
                          (select-keys x [:name :document :due :status :met-at]))}))))

(defn- contract-tool [ctx _ {:strs [contract_id as_of]}]
  (let [d (reads/query ctx {:contract/id contract_id}
                       [{[:? :contract/instance] [:*]}
                        {[:? :contract/state] [:*]}
                        {[:? :contract/lifecycle] [:*]}
                        {[:? :contract/all-deadlines] [:*]}
                        {[:? :contract/timeline] reads/timeline-item}
                        {:contract/advisories [:*]}]
                       :as-of (instant as_of))]
    (if-not (:contract/instance d)
      (error-result (str "No contract or document " contract_id))
      (text-result
       {:contract_id contract_id
        :data        (:contract/instance d)
        :state       (:contract/state d)
        :next_steps  (runtime/next-steps (:contract/lifecycle d) (get-in d [:contract/state "status"]))
        :deadlines   (:contract/all-deadlines d)
        :timeline    (map timeline/finish (:contract/timeline d))
        :advisories  (for [a (:contract/advisories d)]
                       (select-keys a ["text" "issuer" "valid-from" "proposedData"]))}))))

(def ^:private max-text 40000)

(defn- read-document [{:keys [biff.xtdb/node trustblocks/document-root]} _ {:strs [document_hash]}]
  (if-let [{:document/keys [filename media-type size]} (document/get-document node document_hash)]
    (let [text (document/text node (or document-root document/default-root) document_hash)]
      (text-result
       {:document_hash document_hash
        :filename      filename
        :media_type    media-type
        :size          size
        :text          (if (and text (> (count text) max-text)) (subs text 0 max-text) text)
        :note          (cond (nil? text) "No text to read: not a PDF or text file, or a scan with no text layer."
                             (> (count text) max-text) (str "Truncated to the first " max-text " characters."))}))
    (error-result (str "No stored document " document_hash))))

(defn- template-schema [{:keys [biff.xtdb/node]} _ {:strs [class]}]
  (let [reg (store/registry-from-xtdb node)]
    (if-not (get reg class)
      (error-result (str "No model declares " class))
      (text-result
       {:class       class
        :json_schema (model.malli/json-schema (model.malli/schema reg class))
        :concerto    (store/model-source node (subs class 0 (str/last-index-of class ".")))}))))

(def ^:private max-rows 200)

(defn- redact
  "Rows without secrets: an identity's private key and an agent's token
  hash never leave the server, whatever the query asked for."
  [rows]
  (walk/postwalk
   (fn [v]
     (if (map? v)
       (into (empty v) (remove (fn [[k _]] (re-find #"(?i)private.?key|token.?hash" (str k)))) v)
       v))
   rows))

(defn- sql [{:keys [biff.xtdb/node]} _ {:strs [query]}]
  ;; Read-only: xt/q runs queries, never transactions -- and only a query
  ;; form is let through to it. Secrets are taken out of what comes back.
  (if-not (re-find #"(?is)^\s*(select|with|from|values)\b" (str query))
    (error-result "Only a read-only query: SELECT, WITH, FROM or VALUES.")
    (try
      (let [rows (redact (xt/q node query))]
        (text-result {:query     query
                      :row_count (count rows)
                      :rows      (take max-rows rows)
                      :note      (when (> (count rows) max-rows) (str "First " max-rows " rows shown."))}))
      (catch Exception e (error-result (str "The query failed: " (ex-message e)) {:query query})))))

(defn- refusal [e]
  (error-result (ex-message e) (select-keys (ex-data e) [:trustblocks/error :errors :required :matter])))

(defn- advise [{:keys [biff.xtdb/node]} agent {:strs [document_id text document_hash]}]
  (try
    (advisory/advise! node {:agent agent :document document_id :text text
                            :document-hash (not-empty document_hash)
                            :at (java.time.Instant/now)})
    (text-result {:signed true :by agent :document_id document_id :text text})
    (catch clojure.lang.ExceptionInfo e (refusal e))))

(def vendor-form-class
  "com.trustblocks.municipal.construction.vendorform@1.0.0.VendorInformationForm")

(defn- propose-vendor-form [{:keys [biff.xtdb/node]} agent {:strs [document_id values summary document_hash]}]
  (let [instance (store/get-instance node document_id)]
    (cond
      (not instance)
      (error-result (str "No document " document_id))

      (not= vendor-form-class (get instance "$class"))
      (error-result (str document_id " is not a vendor form; propose_vendor_form reads only vendor forms."))

      :else
      (let [;; Its identity and type are the record's, not the agent's to read.
            proposal (merge (stringify values)
                            {"$class" vendor-form-class "vendorFormId" (get instance "vendorFormId")})
            file     (or (not-empty document_hash)
                         (some #(when (= document_id (:xt/id %)) (:stored-document %))
                               (some->> (matter/matter-of node document_id) (matter/documents node))))]
        ;; Explained in the model's own property names, so the agent can
        ;; correct them; advise! checks again before it signs.
        (if-let [errs (model.malli/explain (model.malli/schema (store/registry-from-xtdb node) vendor-form-class)
                                           proposal)]
          (error-result "The proposed values do not fit the vendor form's model; correct them and propose again."
                        {:errors errs})
          (try
            (advisory/advise! node {:agent agent :document document_id :document-hash file
                                    :proposal proposal :at (java.time.Instant/now)
                                    :text (or (not-empty summary)
                                              (str "Proposed " (dec (dec (count proposal)))
                                                   " fields read from the received vendor form, for the Finance Director to confirm."))})
            (text-result {:signed true :by agent :document_id document_id :proposed proposal})
            (catch clojure.lang.ExceptionInfo e (refusal e))))))))

(defn- schema [props required]
  {:type "object" :properties props :required required :additionalProperties false})

(def ^:private as-of-prop
  {:type "string" :description "ISO-8601 instant: read the record as it stood then (default: now)"})

(def tools
  [{:name "list_matters"
    :description "Every open matter: its id, name, package and terms contract."
    :inputSchema (schema {} [])
    :run list-matters}
   {:name "matter_overview"
    :description "A matter as of a day: its documents with their status, the package manifest's checklist (what is on record for each role, and what is required but not on record), and every deadline with its status."
    :inputSchema (schema {:matter_id {:type "string"} :as_of as-of-prop} ["matter_id"])
    :run matter-overview}
   {:name "contract"
    :description "One contract or document of a matter, as of a day: its data, its state, what can happen next and who must certify it, its deadlines, its timeline, and any advice already given."
    :inputSchema (schema {:contract_id {:type "string"} :as_of as-of-prop} ["contract_id"])
    :run contract-tool}
   {:name "read_document"
    :description "The text of a stored file (a received PDF), by its hash (sha256:...), as matter_overview lists them."
    :inputSchema (schema {:document_hash {:type "string"}} ["document_hash"])
    :run read-document}
   {:name "template_schema"
    :description "A document type's model: its JSON Schema and its Concerto source, with each field's description -- what the form's fields are called and what they hold."
    :inputSchema (schema {:class {:type "string" :description "The $class, e.g. com.trustblocks.municipal.construction.vendorform@1.0.0.VendorInformationForm"}} ["class"])
    :run template-schema}
   {:name "sql"
    :description "A read-only SQL query against the record (XTDB SQL: FOR VALID_TIME AS OF, FOR SYSTEM_TIME AS OF and FOR ALL VALID_TIME work). Tables include matter, matter_document, identity (public fields), credential, note, state, execution. The query comes back with its rows."
    :inputSchema (schema {:query {:type "string"}} ["query"])
    :run sql}
   {:name "advise"
    :description "Sign a note of advice on a document of a matter -- what you read, noticed or recommend -- under your ADVISE designation. It appears on the document's timeline as AI advice. It changes nothing."
    :inputSchema (schema {:document_id {:type "string"}
                          :text {:type "string"}
                          :document_hash {:type "string" :description "The stored file you read, if any"}}
                         ["document_id" "text"])
    :run advise}
   {:name "propose_vendor_form"
    :description "Propose a received vendor form's values, as you read them from its stored file (read_document), for the Finance Director to confirm. `values` uses the vendor form model's property names (template_schema); YES/NO questions take \"YES\" or \"NO\", dates are ISO-8601. The values are checked against the model first; if they do not fit, the errors come back and nothing is signed. Nothing is applied until a person confirms."
    :inputSchema (schema {:document_id {:type "string" :description "The vendor form's document id, e.g. str-27-vendor-form-example-paving"}
                          :values {:type "object" :description "Field name -> value, for the fields the file fills in"}
                          :summary {:type "string" :description "One line on what you read and anything uncertain"}
                          :document_hash {:type "string" :description "The stored file read (default: the form's own)"}}
                         ["document_id" "values"])
    :run propose-vendor-form}])

(def ^:private tools-by-name (into {} (map (juxt :name identity)) tools))

;; ---------------------------------------------------------------- JSON-RPC

(defn- rpc-result [id result] {:jsonrpc "2.0" :id id :result result})
(defn- rpc-error [id code message] {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- handle [ctx agent {:keys [id method params]}]
  (case method
    "initialize"
    (rpc-result id {:protocolVersion (or (some #{(:protocolVersion params)} protocol-versions)
                                         (first protocol-versions))
                    :capabilities    {:tools {}}
                    :serverInfo      {:name "trustblocks" :version "0.1.0"}
                    :instructions    instructions})

    "ping"
    (rpc-result id {})

    "tools/list"
    (rpc-result id {:tools (mapv #(dissoc % :run) tools)})

    "tools/call"
    (if-let [{:keys [run]} (tools-by-name (:name params))]
      (rpc-result id (try (run ctx (:xt/id agent) (stringify (or (:arguments params) {})))
                          (catch Exception e
                            (error-result (str "The tool failed: " (ex-message e))))))
      (rpc-error id -32602 (str "Unknown tool: " (:name params))))

    (rpc-error id -32601 (str "Method not found: " method))))

(defn- json-response [status body]
  {:status status :headers {"content-type" "application/json"} :body (json/generate-string body)})

(defn endpoint
  "POST /mcp: one JSON-RPC message, as the agent the bearer token names."
  [{:keys [biff.xtdb/node headers body-params] :as ctx}]
  (let [token (some->> (get headers "authorization") (re-find #"(?i)^Bearer\s+(\S+)$") second)
        agent (identity/by-agent-token node token)]
    (cond
      (not agent)
      {:status 401 :headers {"content-type" "application/json"
                             "www-authenticate" "Bearer realm=\"trustblocks\""}
       :body (json/generate-string {:error "An agent's bearer token is required"})}

      (not (map? body-params))
      (json-response 400 (rpc-error nil -32700 "Expected one JSON-RPC message"))

      ;; A notification has no id and wants no answer.
      (not (contains? body-params :id))
      {:status 202 :body ""}

      :else
      (json-response 200 (handle ctx agent body-params)))))

(def module
  {:biff.ring/api-routes
   [[(routes/mcp) {:post endpoint
                   :get  (fn [_] {:status 405 :headers {"allow" "POST"} :body ""})}]]})
