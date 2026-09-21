(ns com.trustblocks.app.contracts
  "Read-only pages over what's actually stored, reached from a contract's
  own page (show-page) as two choices: View Contract (document-page --
  the originating template's prose, rendered against this instance's data,
  see com.trustblocks.template.render) and View Model (model-page and its
  Fields/Concerto tabs -- our own derived property table, then the native
  .cto source).

  Nothing here writes. Ingestion still happens through the REPL/dev scripts
  (dev/late_delivery_demo.clj, dev/manager_employment_demo.clj,
  dev/seed.clj), and execution has its own endpoint in
  com.trustblocks.app.trigger; this is the warehouse's front window, not its
  loading dock."
  (:require [clojure.string :as str]
            [com.trustblocks.app.auth :as auth]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.model.graph :as model.graph]
            [com.trustblocks.model.playground :as pg]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.render :as render]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]))

;; ---------------------------------------------------------------- rendering

(declare render-instance)

(defn- render-value
  "An instance value as hiccup. Nested concepts (a map carrying its own
  $class) and arrays of them recurse; everything else -- strings, numbers,
  booleans, enum tags, DateTime strings, relationship ids -- is text. Dates
  are shown in their wire ISO-8601 form rather than reformatted, same
  principle as everywhere else in this app: a DateTime is a string until
  something has a specific reason to parse it, and a read-only viewer
  doesn't."
  [reg v]
  (cond
    (map? v)
    (render-instance reg v)

    (and (vector? v) (seq v) (every? map? v))
    [:div {:class ["space-y-3 border-l-2 border-gray-200 pl-4"]}
     (for [item v] (render-instance reg item))]

    (vector? v)
    (str/join ", " v)

    :else (str v)))

(defn render-instance
  "A Concerto instance as a read-only field list, in the $class's own
  declared property order (com.trustblocks.model.graph/own-properties) --
  the order a human reading the source contract would expect, not JSON key
  order (unspecified) or alphabetical (meaningless). $class/$identifier
  aren't shown; they're the type and the id, not a term of the contract."
  [reg instance]
  (let [fqn (get instance "$class")]
    [:dl {:class ["divide-y divide-gray-100"]}
     (for [prop (model.graph/own-properties reg fqn)
           :let [pname (:name prop)
                 v     (get instance pname)]
           :when (some? v)]
       [:div {:class ["py-2 grid grid-cols-3 gap-4"]}
        [:dt {:class ["text-sm text-gray-500"]} pname]
        [:dd {:class ["col-span-2 text-sm"]} (render-value reg v)]])]))

;; ---------------------------------------------------------------- list

(defn- matches? [q {:keys [id class]}]
  (or (str/blank? q)
      (str/includes? (str/lower-case (str id)) (str/lower-case q))
      (str/includes? (str/lower-case (str class)) (str/lower-case q))))

(defn- query-param
  "biff.ring's default middleware may or may not keywordize :params
  depending on content type, so check both spellings rather than guess --
  the same defensive pattern com.biffweb.ring.impl.middleware/wrap-nippy-params
  itself uses for the same reason."
  [ctx k]
  (or (get-in ctx [:query-params k])
      (get-in ctx [:params k])
      (get-in ctx [:params (keyword k)])))

(defn- contract-row [{:keys [id class]}]
  [:tr {:class ["border-t"]}
   [:td {:class ["py-2"]}
    [:a {:href (routes/contract id) :class ["text-blue-600 hover:underline"]} id]]
   [:td {:class ["py-2 text-gray-600"]} class]])

(defn- contracts-table [contracts]
  [:table {:class ["w-full text-sm"]}
   [:thead
    [:tr {:class ["text-left text-gray-500"]}
     [:th {:class ["pb-2"]} "Id"]
     [:th {:class ["pb-2"]} "Type"]]]
   [:tbody (map contract-row contracts)]])

(defn list-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [q         (or (query-param ctx "q") "")
        contracts (->> (store/list-instances node)
                       (filter #(matches? q %))
                       (sort-by :id))]
    (ui/page
     {:ui/title "Contracts"}
     [:main {:class ["mx-auto max-w-3xl p-8 space-y-6"]}
      [:h1 {:class ["text-2xl font-semibold"]} "Contracts"]
      [:form {:method "get" :action (routes/contracts)}
       [:input {:type "search" :name "q" :value q
                :placeholder "Search by id or type"
                :class ["border rounded px-3 py-1.5 w-full max-w-sm"]}]]
      (if (empty? contracts)
        [:p {:class ["text-gray-500"]}
         (if (str/blank? q) "No contracts stored yet." "No contracts match that search.")]
        (contracts-table contracts))])))

;; ---------------------------------------------------------------- viewer

(defn- history-entry [{:keys [at by request-id request response]}]
  [:div {:class ["border rounded p-3"]}
   [:p {:class ["text-xs text-gray-500 mb-2 space-x-3"]}
    [:span (str at)]
    (when by [:span (str "by " by)])
    (when request-id [:span {:class ["font-mono"]} request-id])]
   [:div {:class ["grid grid-cols-2 gap-4 text-sm"]}
    [:div {:class ["min-w-0"]}
     [:p {:class ["text-gray-500"]} "request"]
     [:pre {:class ["whitespace-pre-wrap break-words"]} (pr-str request)]]
    [:div {:class ["min-w-0"]}
     [:p {:class ["text-gray-500"]} "response"]
     [:pre {:class ["whitespace-pre-wrap break-words"]} (pr-str response)]]]])

(defn- external-link [href label]
  [:a {:href href :target "_blank" :rel "noopener noreferrer"
       :class ["text-blue-600 hover:underline"]}
   (str label " \u2197")])

(defn- playground-links
  "Open in Concerto Playground / Open in Template Playground, the two
  links Accord Project's own playgrounds give us for free -- see
  com.trustblocks.model.playground. Concerto Playground always works;
  Template Playground depends on the model fitting its share format, and
  this says so in place of a link when it doesn't, rather than offering
  one that is certain to fail."
  [node reg instance]
  (let [fqn (get instance "$class")
        {:keys [url unavailable]} (pg/template-status node reg instance)]
    [:span {:class ["space-x-4"]}
     (external-link (pg/concerto-url node reg fqn) "Open in Concerto Playground")
     (if url
       (external-link url "Open in Template Playground")
       [:span {:class ["text-sm text-gray-400"]}
        (str "Template Playground: " unavailable)])]))

(defn- officeholder-label
  "'Jane Clerk, Town Clerk', or the bare did if no identity is on file
  for it -- a signature always verifies against a did (see
  com.trustblocks.xtdb.certification/attestations-for-contract), so
  this only falls back if that identity has since been removed."
  [node did]
  (if-let [officeholder (identity/by-did node did)]
    (str (:identity/name officeholder) ", " (:identity/office officeholder))
    did))

(defn- attestation-row [node attestation]
  (let [{:strs [statement documentHash evidence]} attestation
        issuer     (get attestation "issuer")
        valid-from (get attestation "valid-from")]
    [:div {:class ["border rounded p-3"]}
     [:p {:class ["text-xs text-gray-500 mb-1"]}
      (str valid-from " -- " (officeholder-label node issuer))]
     [:p {:class ["text-sm"]} statement]
     [:p {:class ["text-xs text-gray-500 mt-1"]} (str "Document: " documentHash)]
     (when evidence
       [:p {:class ["text-xs text-gray-400 mt-1"]} "Based on an earlier certification."])]))

(defn- certify-form
  "The vote-then-minutes case's one form: statement, a document
  reference, the meeting's own date, and -- once something has already
  been certified about this contract -- a choice of which certification
  this one rests on. required-authority is fixed to CERTIFY_MINUTES on
  the server (com.trustblocks.app.certify); there is no picker for it
  here because there is only the one act this project was built to
  support."
  [id attestations]
  [:form {:method "post" :action (routes/contract-certify id)
          :class ["space-y-3 border rounded p-4"]}
   [:div
    [:label {:class ["block text-sm text-gray-600"]} "Statement"]
    [:textarea {:name "statement" :required true :rows 2
                :class ["w-full border rounded px-2 py-1 text-sm"]}]]
   [:div
    [:label {:class ["block text-sm text-gray-600"]} "Document reference"]
    [:input {:type "text" :name "documentHash" :required true
             :class ["w-full border rounded px-2 py-1 text-sm"]}]]
   [:div
    [:label {:class ["block text-sm text-gray-600"]} "Meeting date"]
    [:input {:type "datetime-local" :name "validFrom" :required true
             :class ["border rounded px-2 py-1 text-sm"]}]]
   (when (seq attestations)
     [:div
      [:label {:class ["block text-sm text-gray-600"]} "Evidence (optional)"]
      [:select {:name "evidence" :class ["w-full border rounded px-2 py-1 text-sm"]}
       [:option {:value ""} "(none)"]
       (for [{:strs [statement jwt]} attestations]
         [:option {:value jwt} (subs statement 0 (min 60 (count statement)))])]])
   [:button {:type "submit" :class ["px-3 py-1.5 bg-blue-600 text-white rounded text-sm"]}
    "Certify"]])

(defn- certifications-section
  "A contract's certification history (attestations naming it, see
  com.trustblocks.xtdb.certification) plus, for a signed-in officeholder,
  the form to add one. Anyone signed in can read this section -- the
  page itself is already behind wrap-signed-in -- but only an email
  registered as an officeholder (com.trustblocks.xtdb.identity/by-email)
  sees the form; certify! re-checks their actual authority regardless,
  this is only about whether to offer the form at all."
  [{:keys [biff.xtdb/node session] :as ctx} id]
  (let [attestations (cert/attestations-for-contract node id)
        officeholder (some->> (:uid session) (auth/user-email ctx) (identity/by-email node))
        error        (query-param ctx "certify-error")]
    [:section
     [:h2 {:class ["text-lg font-medium mb-2"]} (str "Certifications (" (count attestations) ")")]
     (when error
       [:p {:class ["text-sm text-red-600 mb-3"]} error])
     (if (empty? attestations)
       [:p {:class ["text-gray-500 mb-4"]} "No certifications recorded."]
       [:div {:class ["space-y-3 mb-4"]} (map #(attestation-row node %) attestations)])
     (if officeholder
       (certify-form id attestations)
       [:p {:class ["text-sm text-gray-400"]}
        "This account is not registered as an officeholder, so it cannot certify anything here."])]))

(defn show-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (routes/contract-id ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn     (get instance "$class")
            state   (execute/current-state node id :reg reg)
            history (execute/history node id)]
        (ui/page
         {:ui/title (str id " - Trustblocks")}
         [:main {:class ["mx-auto max-w-3xl p-8 space-y-8"]}
          [:div
           [:a {:href (routes/contracts) :class ["text-sm text-blue-600 hover:underline"]}
            "<- All contracts"]]
          [:div
           [:h1 {:class ["text-2xl font-semibold"]} id]
           [:p {:class ["text-gray-500"]} fqn]
           [:p {:class ["mt-1 space-x-4"]}
            [:a {:href (routes/contract-document id) :class ["text-blue-600 hover:underline"]}
             "View Contract"]
            [:a {:href (routes/contract-model id) :class ["text-blue-600 hover:underline"]}
             "View Model"]]
           [:p {:class ["mt-1"]} (playground-links node reg instance)]]

          [:section
           [:h2 {:class ["text-lg font-medium mb-2"]} "Data"]
           (render-instance reg instance)]

          [:section
           [:h2 {:class ["text-lg font-medium mb-2"]} "Current state"]
           (if state
             (render-instance reg state)
             [:p {:class ["text-gray-500"]} "Not yet executed."])]

          [:section
           [:h2 {:class ["text-lg font-medium mb-2"]} (str "History (" (count history) ")")]
           (if (empty? history)
             [:p {:class ["text-gray-500"]} "No executions recorded."]
             [:div {:class ["space-y-4"]} (map history-entry history)])]

          (certifications-section ctx id)])))))

;; ---------------------------------------------------------------- model

(defn- render-property [prop]
  (let [kind   (mm/metamodel-type (:$class prop))
        target (when (= kind "ObjectProperty") (mm/type-fqn (:type prop)))]
    [:tr {:class ["border-t"]}
     [:td {:class ["py-1.5 pr-4 font-mono text-sm"]} (:name prop)]
     [:td {:class ["py-1.5 pr-4 text-sm text-gray-600"]}
      (cond-> (or target (str/replace kind #"Property$" ""))
        (:isArray prop) (str "[]"))]
     [:td {:class ["py-1.5 text-sm text-gray-500"]} (when (:isOptional prop) "optional")]]))

(def ^:private model-tab-routes
  [[:fields   routes/contract-model          "Fields"]
   [:concerto routes/contract-model-concerto "Concerto"]])

(defn- model-tabs
  "The Fields/Concerto tab strip shared by both model pages. Plain links to
  separate routes, not a JS tab widget -- every tab is a real page,
  curl-able and bookmarkable on its own, matching how the rest of this
  read-only app works."
  [id active]
  [:nav {:class ["flex gap-4 border-b mb-6"]}
   (for [[tab route label] model-tab-routes]
     [:a {:href  (route id)
          :class (if (= tab active)
                   ["px-1 pb-2 border-b-2 border-blue-600 text-blue-700 font-medium"]
                   ["px-1 pb-2 border-b-2 border-transparent text-gray-500 hover:text-gray-700"])}
      label])])

(defn- model-shell
  "The header + tab strip every model page shares; `body` is that tab's own
  content."
  [id fqn active body]
  (ui/page
   {:ui/title (str fqn " model")}
   [:main {:class ["mx-auto max-w-3xl p-8"]}
    [:div {:class ["mb-4"]}
     [:a {:href (routes/contract id) :class ["text-sm text-blue-600 hover:underline"]}
      (str "<- " id)]]
    [:h1 {:class ["text-2xl font-semibold font-mono mb-6"]} fqn]
    (model-tabs id active)
    body]))

(defn model-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (routes/contract-id ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn   (get instance "$class")
            chain (mm/super-chain reg fqn)]
        (model-shell
         id fqn :fields
         [:div
          [:p {:class ["text-gray-500 mb-4"]}
           (str "Supertype chain: " (str/join " -> " (reverse chain)))]
          [:table {:class ["w-full"]}
           [:thead
            [:tr {:class ["text-left text-gray-500"]}
             [:th {:class ["pb-2"]} "Property"] [:th {:class ["pb-2"]} "Type"] [:th {:class ["pb-2"]} ""]]]
           [:tbody (map render-property (model.graph/own-properties reg fqn))]]])))))

(defn- concerto-source-block [node ns]
  [:div {:class ["mb-6"]}
   [:p {:class ["text-xs text-gray-500 font-mono mb-1"]} ns]
   [:pre {:class ["text-sm bg-gray-50 border rounded p-4 overflow-x-auto"]}
    (or (store/model-source node ns) "(source not available)")]])

(defn model-concerto-page
  "The $class's own supertype chain, in native Concerto (.cto) syntax --
  the same source put-model! read, not our own derived rendering of it."
  [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (routes/contract-id ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn        (get instance "$class")
            chain      (mm/super-chain reg fqn)
            namespaces (distinct (map #(:namespace (get reg %)) chain))]
        (model-shell
         id fqn :concerto
         [:div (map #(concerto-source-block node %) namespaces)])))))

(defn document-page
  "The contract as prose: its originating template's grammar.tem.md (see
  com.trustblocks.template.archive/by-namespace), rendered against this
  instance's own data via com.trustblocks.template.render -- the terms a
  reader signed, with what Concerto filled in highlighted rather than left
  as bare {{fieldName}} tokens. Not every $class has a template on file
  (docs/walkthrough.md's synthetic snow-removal example never had real
  prose to begin with), in which case this says so rather than fabricating
  one. A sibling of the model pages, not a tab under them -- View Contract
  and View Model are the two choices show-page offers, side by side."
  [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (routes/contract-id ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn     (get instance "$class")
            ns      (:namespace (get reg fqn))
            grammar (:archive/grammar (archive/by-namespace node ns))]
        (ui/page
         {:ui/title (str id " - Trustblocks")}
         [:main {:class ["mx-auto max-w-3xl p-8"]}
          [:div {:class ["mb-4"]}
           [:a {:href (routes/contract id) :class ["text-sm text-blue-600 hover:underline"]}
            (str "<- " id)]]
          [:h1 {:class ["text-2xl font-semibold mb-6"]} id]
          (if grammar
            (into [:div] (render/render grammar instance))
            [:p {:class ["text-gray-500"]}
             "No contract template text on file for this model."])])))))

(def module
  {:biff.ring/routes
   ;; Every contract page is behind signin -- see lib.middleware/wrap-signed-in.
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/contracts) {:get list-page}]
     [(routes/contract) {:get show-page}]
     [(routes/contract-document) {:get document-page}]
     [(routes/contract-model) {:get model-page}]
     [(routes/contract-model-concerto) {:get model-concerto-page}]]]})
