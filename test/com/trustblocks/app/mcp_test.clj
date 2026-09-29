(ns com.trustblocks.app.mcp-test
  "The MCP endpoint, as Muny uses it -- called directly as the other
  handler tests do, over the STR-27 demo: the token is the agent, the read
  tools read, and the only writes are advice -- including a vendor form's
  values, read from its received PDF, checked against the model before
  they are signed, and shown on the form's dashboard beside the record."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.contracts :as contracts]
            [com.trustblocks.app.mcp :as mcp]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [street-resurfacing-matter-demo :as demo]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private vendor-form-id "str-27-vendor-form-example-paving")

(defn- setup! [node]
  (let [root (str (Files/createTempDirectory "mcp" (make-array FileAttribute 0)))]
    (demo/load! node root)
    {:root  root
     :token (identity/issue-agent-token! node (:xt/id (demo/ensure-muny! node)))}))

(defn- post [node {:keys [root token]} message]
  (mcp/endpoint {:biff.xtdb/node node :trustblocks/document-root root
                 :headers (if token {"authorization" (str "Bearer " token)} {})
                 :body-params message}))

(defn- rpc [node env method & [params]]
  (let [{:keys [status body]} (post node env {:jsonrpc "2.0" :id 1 :method method :params params})]
    (assert (= 200 status) (str status " " body))
    (json/parse-string body true)))

(defn- call
  "A tool's answer: {:error? :text :data} -- its text parsed as JSON when it is."
  [node env tool args]
  (let [{{:keys [content isError]} :result} (rpc node env "tools/call" {:name tool :arguments args})
        text (:text (first content))]
    {:error? (boolean isError)
     :text   text
     :data   (try (json/parse-string text true) (catch Exception _ nil))}))

(deftest the-token-is-the-agent
  (with-open [node (xt.node/start-node)]
    (let [env (setup! node)]
      (testing "no token, or an unknown one: refused before anything is read"
        (is (= 401 (:status (post node (assoc env :token nil) {:jsonrpc "2.0" :id 1 :method "ping"}))))
        (is (= 401 (:status (post node (assoc env :token "nope") {:jsonrpc "2.0" :id 1 :method "ping"})))))
      (testing "only an agent is issued one -- an officeholder signs in as themselves"
        (is (= :not-an-agent
               (:trustblocks/error
                (try (identity/issue-agent-token! node (:xt/id (identity/by-email node demo/newsome)))
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
      (testing "a new token revokes the last"
        (let [muny (:xt/id (demo/ensure-muny! node))
              _    (identity/issue-agent-token! node muny)]
          (is (= 401 (:status (post node env {:jsonrpc "2.0" :id 1 :method "ping"})))))))))

(deftest the-protocol
  (with-open [node (xt.node/start-node)]
    (let [env (setup! node)]
      (testing "initialize: the protocol version, the tools capability, and who Muny is"
        (let [{:keys [result]} (rpc node env "initialize" {:protocolVersion "2025-06-18"
                                                           :capabilities {} :clientInfo {:name "test"}})]
          (is (= "2025-06-18" (:protocolVersion result)))
          (is (contains? (:capabilities result) :tools))
          (is (str/includes? (:instructions result) "never certify"))))
      (testing "a notification is accepted, and answered with nothing"
        (is (= 202 (:status (post node env {:jsonrpc "2.0" :method "notifications/initialized"})))))
      (testing "the tools: reading, and advice -- nothing that certifies or changes a document"
        (is (= ["list_matters" "matter_overview" "contract" "read_document" "template_schema" "sql"
                "advise" "propose_vendor_form"]
               (map :name (get-in (rpc node env "tools/list") [:result :tools]))))))))

(deftest reading-the-record
  (with-open [node (xt.node/start-node)]
    (let [env (setup! node)]
      (is (= ["str-27"] (map :matter_id (:data (call node env "list_matters" {})))))
      (testing "a matter: its documents, what the package still calls for, its deadlines"
        (let [{:keys [data]} (call node env "matter_overview" {:matter_id "str-27"})]
          (is (some #(= "VENDOR_NUMBER_ASSIGNED" (:status %)) (:documents data)))
          (is (some #(and (= "performance-bond" (:role %)) (= "not-on-record" (:standing %))) (:checklist data)))
          (is (some #(= "Completion Date" (:name %)) (:deadlines data)))))
      (testing "a document as of a day"
        (is (= "RECEIVED" (get-in (call node env "contract" {:contract_id "str-27-pay-2" :as_of "2026-08-26T00:00:00Z"})
                                  [:data :state :status]))))
      (testing "sql: read-only, the query echoed, secrets withheld"
        (let [{:keys [data]} (call node env "sql" {:query "SELECT * FROM identity"})]
          (is (= "SELECT * FROM identity" (:query data)))
          (is (pos? (:row_count data)))
          (is (not-any? #(re-find #"private|token" (name %)) (mapcat keys (:rows data)))))
        (is (:error? (call node env "sql" {:query "DELETE FROM matter"})))))))

(deftest reading-a-vendor-form-and-proposing-its-values
  (with-open [node (xt.node/start-node)]
    (let [env  (setup! node)
          file (:stored-document (first (matter/documents node "str-27" :role "vendor-form")))]
      (testing "the received PDF's text: the Contractor's answers, as they wrote them"
        (let [{:keys [data]} (call node env "read_document" {:document_hash file})]
          (is (= "vendor-form.pdf" (:filename data)))
          (is (str/includes? (:text data) "LEGAL NAME OF COMPANY / CORPORATION (as shown on IRS tax form): Example Paving, LLC"))
          (is (str/includes? (:text data) "FEDERAL TAX ID #: 00-0000000"))))
      (testing "the model it is read into"
        (is (str/includes? (get-in (call node env "template_schema" {:class mcp/vendor-form-class}) [:data :concerto])
                           "o String legalName optional")))
      (testing "values that do not fit the model: the errors come back, and nothing is signed"
        (let [{:keys [error? text]} (call node env "propose_vendor_form"
                                          {:document_id vendor-form-id
                                           :values {:legalName "Example Paving, LLC" :northCarolinaCorporation "Yes"}})]
          (is error?)
          (is (str/includes? text "northCarolinaCorporation"))))
      (testing "values that fit: a signed Advisory carrying the proposal -- the record unchanged"
        (let [{:keys [error? data]} (call node env "propose_vendor_form"
                                          {:document_id vendor-form-id
                                           :values {:legalName "Example Paving, LLC"
                                                    :doingBusinessAs "Example Paving"
                                                    :northCarolinaCorporation "YES"
                                                    :federalTaxId "00-0000000"
                                                    :contractorLicenseExpiration "2026-12-31T00:00:00.000Z"}
                                           :summary "Read from the received PDF; the Town-use block is blank."})]
          (is (not error?))
          (is (= "Example Paving, LLC" (get-in data [:proposed :legalName])))
          (is (= vendor-form-id (get-in data [:proposed :vendorFormId])))
          (let [advisories (get-in (call node env "contract" {:contract_id vendor-form-id}) [:data :advisories])]
            (is (= 1 (count advisories)))
            (is (str/includes? (:proposedData (first advisories)) "Example Paving, LLC")))))
      (testing "on the form's dashboard: field by field, beside the record"
        (let [page (:body (contracts/show-page {:biff.xtdb/node node :uri (str "/contracts/" vendor-form-id)
                                                :params {}}))]
          (is (str/includes? page "Proposed by an AI agent"))
          (is (str/includes? page "legalName"))
          (is (str/includes? page "Read from the received PDF")))))))

(deftest advising
  (with-open [node (xt.node/start-node)]
    (let [env (setup! node)]
      (is (not (:error? (call node env "advise" {:document_id "str-27-pay-3"
                                                 :text "Received on the 25th, as Sec. 6 requires."}))))
      (is (str/includes? (:text (call node env "advise" {:document_id "no-such-document" :text "x"}))
                         "No such document")))))
