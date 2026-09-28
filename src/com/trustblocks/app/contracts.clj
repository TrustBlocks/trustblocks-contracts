(ns com.trustblocks.app.contracts
  "The contract warehouse UI: an action-oriented dashboard over stored
  contracts, each contract's data/state/execution/certification record, its
  originating prose (document-page), and its Concerto model (Fields/Concerto
  tabs). Dashboard and record pages also link into Accord's external
  Template Playground and Concerto Viewer when each tool can accept the
  model.

  Page reads stay here; writes still have explicit handlers elsewhere:
  com.trustblocks.app.trigger executes JSON requests and
  com.trustblocks.app.certify handles the officeholder form rendered below.
  Ingestion/provisioning remains a dev-script/REPL operation documented in
  docs/operator-runbook.md."
  (:require [cheshire.core :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [com.trustblocks.app.auth :as auth]
            [com.trustblocks.app.certify :as certify]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.lib.code :as code]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.model.properties :as model.properties]
            [com.trustblocks.model.malli :as model.malli]
            [com.trustblocks.model.playground :as pg]
            [com.trustblocks.model.reads :as reads]
            [com.trustblocks.model.timeline :as timeline]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.runtime :as runtime]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.render :as render]
            [com.trustblocks.template.word :as word]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.note :as note]
            [com.trustblocks.xtdb.store :as store]
            [dev.onionpancakes.chassis.core :as chassis]))

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
  declared property order (com.trustblocks.model.properties/own-properties) --
  the order a human reading the source contract would expect, not JSON key
  order (unspecified) or alphabetical (meaningless). $class/$identifier
  aren't shown; they're the type and the id, not a term of the contract."
  [reg instance]
  (let [fqn (get instance "$class")]
    [:dl {:class ["divide-y divide-gray-100"]}
     (for [prop (model.properties/own-properties reg fqn)
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

(defn query-param
  "biff.ring's default middleware may or may not keywordize :params
  depending on content type, so check both spellings rather than guess --
  the same defensive pattern com.biffweb.ring.impl.middleware/wrap-nippy-params
  itself uses for the same reason."
  [ctx k]
  (or (get-in ctx [:query-params k])
      (get-in ctx [:params k])
      (get-in ctx [:params (keyword k)])))

(defn- type-name [fqn]
  (-> fqn
      (str/split #"\.")
      last
      (str/replace #"([a-z0-9])([A-Z])" "$1 $2")))

(def ^:private action-link-class
  ["inline-flex items-center justify-center rounded-md border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 shadow-sm hover:border-blue-400 hover:text-blue-700"])

(defn- action-link [href label & {:keys [external]}]
  [:a (cond-> {:href href :class action-link-class}
        external (assoc :target "_blank" :rel "noopener noreferrer"))
   label
   (when external [:span {:aria-hidden "true" :class ["ml-1"]} "↗"])])

(defn- disabled-action [label reason]
  [:span {:title reason
          :aria-disabled "true"
          :class ["inline-flex cursor-not-allowed items-center justify-center rounded-md border border-slate-200 bg-slate-100 px-3 py-2 text-sm font-semibold text-slate-400"]}
   label])

(defn- contract-actions
  "The same primary actions on the dashboard and a contract's record page.
  Template Playground's external limitation is represented as a disabled
  action with the precise reason, never as a link known to fail."
  [node reg instance id]
  (let [fqn (get instance "$class")
        {:keys [url unavailable]} (pg/template-status node reg instance)]
    [:div {:class ["flex flex-wrap gap-2"]}
     (action-link (routes/contract-document id) "View Contract")
     (if-let [template (archive/for-class node fqn)]
       (action-link (routes/template-code (:xt/id template)) "View Logic")
       (disabled-action "View Logic" "No template is on file for this contract type"))
     (if url
       (action-link url "View in Template Playground" :external true)
       (disabled-action "View in Template Playground" unavailable))
     (action-link (pg/concerto-url node reg fqn) "View in Concerto Viewer" :external true)]))

(defn- contract-card [node reg {:keys [id class]}]
  (let [instance (store/get-instance node id :reg reg)]
    [:article {:class ["rounded-xl border border-slate-200 bg-white p-5 shadow-sm"]}
     [:div {:class ["flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between"]}
      [:div {:class ["min-w-0"]}
       [:p {:class ["text-xs font-semibold uppercase tracking-widest text-blue-700"]} "Available Contract"]
       [:a {:href (routes/contract id)
            :class ["mt-1 block text-xl font-semibold tracking-tight text-slate-950 hover:text-blue-700"]}
        (type-name class)]
       [:p {:class ["mt-1 font-mono text-xs text-slate-500"]} id]
       [:p {:class ["mt-2 break-all text-xs text-slate-400"]} class]]
      (contract-actions node reg instance id)]]))

(defn list-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [q         (or (query-param ctx "q") "")
        reg       (store/registry-from-xtdb node)
        contracts (->> (store/list-instances node)
                       (filter #(matches? q %))
                       (sort-by :id))]
    (ui/page
     {:ui/title "Municipal Contracts — Trustblocks"}
     (ui/workspace-header :contracts)
     [:main {:class ["mx-auto w-full max-w-6xl px-6 py-8"]}
      [:div {:class ["flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between"]}
       [:div
        [:p {:class ["text-sm font-semibold uppercase tracking-widest text-blue-700"]} "Contract Warehouse"]
        [:h1 {:class ["mt-1 text-3xl font-semibold tracking-tight text-slate-950"]} "Available Contracts"]
        [:p {:class ["mt-2 max-w-2xl text-sm leading-6 text-slate-600"]}
         "Open the signed agreement, inspect its source model, or continue into Accord Project's own viewers."]]
       [:form {:method "get" :action (routes/contracts) :class ["w-full sm:w-80"]}
        [:label {:for "contract-search" :class ["sr-only"]} "Search contracts"]
        [:input {:id "contract-search" :type "search" :name "q" :value q
                 :placeholder "Search contracts"
                 :class ["w-full rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-sm shadow-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"]}]]]
      (if (empty? contracts)
        [:div {:class ["mt-8 rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center text-slate-500"]}
         (if (str/blank? q) "No contracts are stored yet." "No contracts match that search.")]
        [:div {:class ["mt-8 space-y-5"]}
         (map #(contract-card node reg %) contracts)])])))

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

(defn- attestation-row
  "One attestation; `labels` names its signer -- 'Pamela Mills, Town
  Clerk', or the bare did if no identity is on file for it (a signature
  always verifies against a did, so this only falls back if that identity
  has since been removed)."
  [labels attestation]
  (let [{:strs [statement documentHash evidence]} attestation
        issuer     (get attestation "issuer")
        valid-from (get attestation "valid-from")]
    [:div {:class ["border rounded p-3"]}
     [:p {:class ["text-xs text-gray-500 mb-1"]}
      (str valid-from " -- " (get labels issuer issuer))]
     [:p {:class ["text-sm"]} statement]
     [:p {:class ["text-xs text-gray-500 mt-1"]} (str "Document: " documentHash)]
     (when evidence
       [:p {:class ["text-xs text-gray-400 mt-1"]} "Based on an earlier certification."])]))

(defn- certify-form
  "The attestation form: statement, a document reference, the event's own
  date, and -- once something has already been certified about this
  contract -- a choice of which certification this one rests on. The
  authority it requires is the contract template's (see
  com.trustblocks.app.certify/required-authority), shown, not chosen."
  [id attestations authority]
  [:form {:method "post" :action (routes/contract-certify id)
          :class ["space-y-3 border rounded p-4"]}
   [:p {:class ["text-xs text-slate-500"]}
    (str "The contract's template requires " authority " to attest to it.")]
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
  this is only about whether to offer the form at all. `attestations` and
  `labels` (did -> label) are what the page's query read."
  [{:keys [biff.xtdb/node session] :as ctx} id attestations labels]
  (let [officeholder (some->> (:uid session) (auth/user-email ctx) (identity/by-email node))
        authority    (certify/required-authority node id)
        error        (query-param ctx "certify-error")]
    [:section {:class ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"]}
     [:h2 {:class ["mb-2 text-lg font-semibold text-slate-950"]} (str "Certifications (" (count attestations) ")")]
     (when error
       [:p {:class ["mb-3 rounded-md bg-red-50 px-3 py-2 text-sm font-medium text-red-700"]} error])
     (if (empty? attestations)
       [:p {:class ["mb-4 text-slate-500"]} "No certifications recorded."]
       [:div {:class ["mb-5 space-y-3"]} (map #(attestation-row labels %) attestations)])
     (cond
       (not authority)
       [:p {:class ["text-sm text-slate-400"]}
        "This contract's template accepts no attestations."]

       officeholder
       (certify-form id attestations authority)

       :else
       [:p {:class ["text-sm text-slate-400"]}
        "This account is not registered as an officeholder, so it cannot certify anything here."])]))

;; ---------------------------------------------------------------- dashboard

(def ^:private day-formatter
  (java.time.format.DateTimeFormatter/ofPattern "MMM d, yyyy"))

(defn- day [^java.time.Instant t]
  (.format ^java.time.format.DateTimeFormatter day-formatter (.atZone t java.time.ZoneOffset/UTC)))

(defn- epoch-day [^java.time.Instant t]
  (.toEpochDay (.toLocalDate (.atZone t java.time.ZoneOffset/UTC))))

(defn end-of-day
  "The last instant of the UTC day `yyyy-mm-dd`, so a slider set to a day
  shows everything that happened on it."
  [s]
  (-> (java.time.LocalDate/parse s) (.plusDays 1) (.atStartOfDay java.time.ZoneOffset/UTC)
      .toInstant (.minusMillis 1)))

(def ^:private kind-styles
  {:event       ["bg-blue-50 text-blue-700 ring-blue-200" "Event"]
   :designation ["bg-violet-50 text-violet-700 ring-violet-200" "Designation"]
   :receipt     ["bg-emerald-50 text-emerald-700 ring-emerald-200" "Receipt"]
   :date        ["bg-slate-100 text-slate-700 ring-slate-200" "Contract date"]
   :deadline    ["bg-amber-50 text-amber-800 ring-amber-200" "Deadline"]
   :note        ["bg-yellow-50 text-yellow-800 ring-yellow-300" "Note"]
   :advisory    ["bg-fuchsia-50 text-fuchsia-800 ring-fuchsia-200" "AI advisory"]})

(defn- kind-badge [kind]
  (let [[classes label] (kind-styles kind)]
    [:span {:class ["inline-flex w-28 shrink-0 justify-center rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset" classes]}
     label]))

(defn- status-badge [status]
  [:span {:class ["inline-flex rounded-full bg-slate-900 px-3 py-1 text-sm font-semibold text-white"]}
   (or status "Not started")])

(defn as-of-slider
  "A day slider over the timeline's span. Moving it reloads the page at
  `href` as of that day (?as-of=yyyy-mm-dd) -- the state, and which events
  had happened. The matter page shares it."
  [href items as-of today]
  (let [days  (map (comp epoch-day :at) items)
        lo    (apply min (epoch-day today) days)
        hi    (apply max (epoch-day today) days)
        value (epoch-day as-of)]
    [:form {:method "get" :action href :id "as-of-form"
            :class ["rounded-xl border border-slate-200 bg-white p-5 shadow-sm"]}
     [:div {:class ["flex flex-wrap items-baseline justify-between gap-2"]}
      [:label {:for "as-of-slider" :class ["text-sm font-semibold text-slate-950"]}
       "As of " [:span {:id "as-of-label"} (day as-of)]]
      (when (not= (epoch-day as-of) (epoch-day today))
        [:a {:href href :class ["text-sm font-medium text-blue-700 hover:underline"]} "Back to today"])]
     [:input {:type "range" :id "as-of-slider" :min lo :max hi :value value :step 1
              :class ["mt-3 w-full accent-blue-700"]
              :oninput "document.getElementById('as-of-label').textContent = new Date(this.value * 86400000).toLocaleDateString('en-US', {month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC'})"
              :onchange "document.getElementById('as-of').value = new Date(this.value * 86400000).toISOString().slice(0, 10); this.form.submit()"}]
     [:input {:type "hidden" :name "as-of" :id "as-of" :value (subs (str as-of) 0 10)}]
     [:div {:class ["mt-1 flex justify-between text-xs text-slate-500"]}
      [:span (day (java.time.Instant/ofEpochSecond (* 86400 lo)))]
      [:span (day (java.time.Instant/ofEpochSecond (* 86400 hi)))]]]))

(defn- requirement-text [{:keys [requires]}]
  (str "needs " (str/join " or " (map #(str (if (re-find #"^[AEIOU]" (str (get % "credential"))) "an " "a ")
                                            (get % "credential") " under " (get % "authority"))
                                      requires))))

(def ^:private deadline-status
  {:passed   ["bg-rose-50 text-rose-700 ring-rose-200" "Passed"]
   :upcoming ["bg-amber-50 text-amber-800 ring-amber-200" "Upcoming"]
   :met      ["bg-emerald-50 text-emerald-700 ring-emerald-200" "Met"]})

(defn deadlines-section
  "The watch list: each deadline the contract sets, and its documents set,
  with where it stands -- on the dashboard, and on the matter page for the
  matter's terms. Shown, never acted on -- what follows a passed
  deadline is the Town's decision. `deadlines` is the contract's
  :contract/all-deadlines (com.trustblocks.model.reads)."
  [deadlines id]
  (let [ds (->> deadlines
                (filter :due)
                (sort-by (juxt #(case (:status %) :passed 0 :upcoming 1 :met 2) :due)))]
    (when (seq ds)
      [:section {:class ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"]}
       [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Deadlines"]
       [:p {:class ["mb-3 text-sm text-slate-500"]}
        "The dates the contract sets, and where each stands. Nothing happens on its own when one passes: what follows is the Town's decision."]
       [:ul {:class ["divide-y divide-slate-100"]}
        (for [{:keys [name description document due status met-at]} ds
              :let [[classes label] (deadline-status status)]]
          [:li {:class ["flex items-start gap-4 py-3"]}
           [:span {:class ["w-28 shrink-0 font-mono text-xs text-slate-500"]} (day due)]
           [:span {:class ["inline-flex w-24 shrink-0 justify-center rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset" classes]} label]
           [:div {:class ["min-w-0"]}
            [:p {:class ["text-sm text-slate-900"]} name
             (when (not= document id)
               [:a {:href (routes/contract document) :class ["ml-2 font-mono text-xs text-blue-700 hover:underline"]} document])]
            [:p {:class ["text-xs text-slate-500"]}
             (str/join " -- " (remove nil? [description (when met-at (str "Met " (day met-at)))]))]]])]])))

(defn- where-it-stands
  "Its state as of `as-of`, and what can happen next: from what the page's
  query read (`d`)."
  [reg {:contract/keys [state lifecycle terms-of]} as-of today]
  (let [status (get state "status")
        steps  (runtime/next-steps lifecycle status)
        matter (when terms-of {:xt/id (:matter/id terms-of) :matter/name (:matter/name terms-of)})]
    [:section {:class ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"]}
     [:div {:class ["mb-4 flex flex-wrap items-center justify-between gap-3"]}
      [:h2 {:class ["text-lg font-semibold text-slate-950"]}
       (if (= (epoch-day as-of) (epoch-day today)) "Where it stands" (str "Where it stood on " (day as-of)))]
      (when (or status (seq steps)) (status-badge status))]
     (when matter
       [:p {:class ["mb-4 text-sm text-slate-600"]}
        "The terms of matter "
        [:a {:href (routes/matter (:xt/id matter)) :class ["font-medium text-blue-700 hover:underline"]}
         (or (:matter/name matter) (:xt/id matter))]
        " -- its documents' steps are on this timeline."])
     (cond
       (seq steps)
       [:div
        [:p {:class ["mb-2 text-sm font-semibold text-slate-700"]} "What can happen next"]
        [:ul {:class ["space-y-1 text-sm text-slate-700"]}
         (for [{:keys [event to] :as step} steps]
           [:li [:span {:class ["font-mono"]} event] (str " → " to ", " (requirement-text step))])]]

       (and status (timeline/accepting? lifecycle status))
       [:p {:class ["text-sm text-slate-600"]} "Its lifecycle is complete."]

       (not status)
       [:p {:class ["text-sm text-slate-600"]}
        "Its template declares no lifecycle of its own"
        (if matter ", so nothing moves it directly; its matter's documents do." ".")]

       :else nil)
     (when state
       [:details {:class ["mt-4"]}
        [:summary {:class ["cursor-pointer text-sm text-slate-600"]} "The recorded state"]
        [:div {:class ["mt-2"]} (render-instance reg state)]])]))

(defn- timeline-entry [{:keys [at kind title who detail document recorded]} as-of id]
  (let [later?          (.isAfter ^java.time.Instant at ^java.time.Instant as-of)
        recorded-later? (and recorded (.isAfter ^java.time.Instant recorded ^java.time.Instant as-of))]
    [:li {:class (cond-> ["flex items-start gap-4 py-3"] (or later? recorded-later?) (conj "opacity-40"))}
     [:span {:class ["w-28 shrink-0 font-mono text-xs text-slate-500"]} (day at)]
     (kind-badge kind)
     [:div {:class ["min-w-0"]}
      [:p {:class ["text-sm text-slate-900" (when (= kind :note) "italic")]}
       title
       (when (and document (not= document id))
         [:a {:href (routes/contract document) :class ["ml-2 font-mono text-xs text-blue-700 hover:underline"]} document])]
      (when (or who detail)
        [:p {:class ["text-xs text-slate-500"]} (str/join " -- " (remove nil? [who detail]))])
      (when recorded
        [:p {:class ["text-xs text-slate-500"]}
         (str "Recorded " (day recorded)
              (when (> (epoch-day recorded) (epoch-day at)) " -- after the day it is about")
              (when (< (epoch-day recorded) (epoch-day at)) " -- before the day it is about"))])]]))

(defn timeline-section
  "Contract `id`'s timeline -- `items`, finished (com.trustblocks.model.
  timeline/finish) -- divided at `as-of`: on the dashboard, and on the
  matter page for its terms."
  [items id as-of today]
  (let [[past future] (split-with #(not (.isAfter ^java.time.Instant (:at %) ^java.time.Instant as-of)) items)]
    [:section {:class ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"]}
     [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Timeline"]
     [:p {:class ["mb-3 text-sm text-slate-500"]}
      "Every step, the dates the contract names, its deadlines, and notes. Faded: after the slider's day, or a note not yet written by then."]
     (if (empty? items)
       [:p {:class ["text-slate-500"]} "Nothing on record yet."]
       [:ol {:class ["divide-y divide-slate-100"]}
        (map #(timeline-entry % as-of id) past)
        [:li {:class ["flex items-center gap-3 py-2"]}
         [:span {:class ["h-px flex-1 bg-blue-600"]}]
         [:span {:class ["text-xs font-semibold uppercase tracking-wide text-blue-700"]}
          (if (= (epoch-day as-of) (epoch-day today)) "Today" (str "As of " (day as-of)))]
         [:span {:class ["h-px flex-1 bg-blue-600"]}]]
        (map #(timeline-entry % as-of id) future)])]))

(defn- note-form [id today]
  [:section {:class ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"]}
   [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Add a note"]
   [:p {:class ["mb-4 text-sm text-slate-500"]}
    "Context for the record, not a change to the contract. The day it is about places it on the timeline; the day it was recorded is set by the database and cannot be changed -- so a note shows what was known, and when."]
   [:form {:method "post" :action (routes/contract-notes id) :class ["space-y-3"]}
    [:textarea {:name "text" :required true :rows 3
                :placeholder "e.g. The Contractor appears to be in financial trouble -- two suppliers report unpaid invoices."
                :class ["w-full rounded-md border border-slate-300 px-3 py-2 text-sm"]}]
    [:div {:class ["flex flex-wrap items-end gap-4"]}
     [:label {:class ["text-sm text-slate-600"]} "About the day"
      [:input {:type "date" :name "about" :value (subs (str today) 0 10)
               :class ["ml-2 rounded-md border border-slate-300 px-2 py-1 text-sm"]}]]
     [:button {:type "submit" :class ["rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white"]}
      "Add note"]]]])

(defn add-note
  "POST /contracts/:id/notes -- a signed-in user's note on the contract."
  [{:keys [biff.xtdb/node session] :as ctx}]
  (let [id    (routes/contract-id ctx)
        text  (some-> (query-param ctx "text") str/trim)
        about (some-> (query-param ctx "about") not-empty
                      (#(-> (java.time.LocalDate/parse %) (.atStartOfDay java.time.ZoneOffset/UTC) .toInstant)))]
    (when (and (store/get-instance node id) (not (str/blank? text)))
      (note/add! node {:contract id :author (auth/user-email ctx (:uid session)) :text text :about about}))
    {:status 303 :headers {"location" (str (routes/contract id) "#timeline")}}))

(defn- people-labels
  "{did label} for the people a query resolved."
  [people]
  (into {} (map (juxt :identity/did :identity/label)) people))

(defn show-page
  "The contract dashboard: where it stands as of a day (the slider, ?as-of=),
  its timeline, notes, its data, and its certifications -- everything read
  in one graph query (com.trustblocks.model.reads), so each thing it shows
  in several places is read once."
  [{:keys [biff.xtdb/node] :as ctx}]
  (let [id      (routes/contract-id ctx)
        today   (java.time.Instant/now)
        as-of   (or (some-> (query-param ctx "as-of") not-empty
                            (#(try (end-of-day %) (catch Exception _ nil))))
                    today)
        as-of   (if (.isAfter ^java.time.Instant as-of today) today as-of)
        reg     (store/registry-from-xtdb node)
        d       (reads/query ctx {:contract/id id}
                             [{[:? :contract/instance] [:*]}
                              {[:? :contract/state] [:*]}
                              {[:? :contract/lifecycle] [:*]}
                              {[:? :contract/terms-of] [:matter/id [:? :matter/name]]}
                              {[:? :contract/all-deadlines] [:*]}
                              {[:? :contract/timeline] reads/timeline-item}
                              {:contract/history [:*]}
                              {:contract/attestations [:*]}
                              {:contract/attestation-people [:identity/did :identity/label]}]
                             :now today :as-of as-of :registry reg)
        instance (:contract/instance d)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn     (get instance "$class")
            history (:contract/history d)
            items   (mapv timeline/finish (:contract/timeline d))]
        (ui/page
         {:ui/title (str id " — Trustblocks")}
         (ui/workspace-header :contracts)
         [:main {:class ["mx-auto w-full max-w-6xl space-y-8 px-6 py-8"]}
          [:div
           [:a {:href (routes/contracts) :class ["text-sm font-medium text-blue-700 hover:underline"]}
            "← All contracts"]]
          [:div {:class ["flex flex-col gap-5 border-b border-slate-200 pb-7 lg:flex-row lg:items-end lg:justify-between"]}
           [:div
            [:p {:class ["text-sm font-semibold uppercase tracking-widest text-blue-700"]} "Contract Dashboard"]
            [:h1 {:class ["mt-1 text-3xl font-semibold tracking-tight text-slate-950"]} (type-name fqn)]
            [:p {:class ["mt-2 font-mono text-sm text-slate-500"]} id]
            [:a {:href (routes/contract-model id) :class ["mt-2 inline-block text-sm font-medium text-blue-700 hover:underline"]}
             "Inspect stored model"]]
           (contract-actions node reg instance id)]

          (as-of-slider (routes/contract id) items as-of today)
          (where-it-stands reg d as-of today)
          (deadlines-section (:contract/all-deadlines d) id)
          [:div {:id "timeline"} (timeline-section items id as-of today)]
          (note-form id today)

          [:section {:class ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"]}
           [:h2 {:class ["mb-2 text-lg font-semibold text-slate-950"]} "Contract Data"]
           (render-instance reg instance)]

          (certifications-section ctx id (:contract/attestations d)
                                  (people-labels (:contract/attestation-people d)))

          [:details {:class ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"]}
           [:summary {:class ["cursor-pointer text-lg font-semibold text-slate-950"]}
            (str "Execution records (" (count history) ")")]
           (if (empty? history)
             [:p {:class ["mt-3 text-slate-500"]} "No executions recorded."]
             [:div {:class ["mt-4 space-y-4"]} (map history-entry history)])]])))))

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
   [:concerto routes/contract-model-concerto "Concerto"]
   [:malli    routes/contract-model-malli    "Malli"]])

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
   (ui/workspace-header :contracts)
   [:main {:class ["mx-auto w-full max-w-6xl px-6 py-8"]}
    [:div {:class ["mb-4"]}
     [:a {:href (routes/contract id) :class ["text-sm font-medium text-blue-700 hover:underline"]}
      (str "← " id)]]
    [:h1 {:class ["mb-6 break-all font-mono text-2xl font-semibold text-slate-950"]} fqn]
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
           [:tbody (map render-property (model.properties/own-properties reg fqn))]]])))))

(defn- concerto-source-block [node ns]
  [:div {:class ["mb-6"]}
   [:p {:class ["text-xs text-gray-500 font-mono mb-1"]} ns]
   (if-let [source (store/model-source node ns)]
     (code/cto-listing source)
     [:p {:class ["text-sm text-slate-500"]} "(source not available)"])])

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

(def ^:private malli-block
  ["max-h-[32rem] overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-100"])

(defn- malli-listing
  "EDN or JSON as a numbered, highlighted listing (com.trustblocks.lib.code),
  scrolling past 32rem."
  [source]
  [:div {:class ["max-h-[32rem] overflow-auto rounded-lg"]} (code/data-listing source)])

(defn- edn-text [x]
  (binding [pprint/*print-right-margin* 100]
    (with-out-str (pprint/pprint x))))

(defn- malli-section [title blurb & body]
  (into [:section {:class ["space-y-3"]}
         [:h2 {:class ["text-lg font-semibold text-slate-950"]} title]
         [:p {:class ["max-w-3xl text-sm leading-6 text-slate-600"]} blurb]]
        body))

(def ^:private viz-script
  "Graphviz, compiled to WebAssembly, to draw Malli's DOT in the page."
  "https://cdn.jsdelivr.net/npm/@viz-js/viz@3/lib/viz-standalone.js")

(def ^:private draw-diagram
  "Draws #malli-diagram's DOT, or says it could not and points at the source."
  "Viz.instance().then(function (viz) {
     var el = document.getElementById('malli-diagram');
     document.getElementById('malli-diagram-loading').remove();
     el.appendChild(viz.renderSVGElement(el.dataset.dot));
   }).catch(function () {
     document.getElementById('malli-diagram-loading').textContent =
       'The diagram could not be drawn here; its DOT source is below.';
   });")

(defn model-malli-page
  "The contract type's model as Malli -- see com.trustblocks.model.malli:
  the native schema, this contract validated against it, a sample made up
  from it (?seed= picks another), its JSON Schema, and its diagram."
  [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (routes/contract-id ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn    (get instance "$class")
            schema (model.malli/schema reg fqn)
            errors (model.malli/explain schema instance)
            seed   (or (some-> (query-param ctx "seed") parse-long) 1)
            sample (try {:ok (model.malli/sample schema seed)}
                        (catch Exception e {:error (ex-message e)}))
            dot    (model.malli/dot reg fqn)]
        (model-shell
         id fqn :malli
         [:div {:class ["space-y-10"]}
          (malli-section
           "1. The schema"
           "The Concerto model, compiled to a Malli schema by our own Clojure parser and compiler (concerto-clj). It is plain EDN -- data, not code -- so everything below is done with that one value."
           (malli-listing (edn-text (model.malli/form reg fqn))))
          (malli-section
           "2. This contract, validated"
           "The stored contract checked against the schema -- the same check that guards every write to XTDB. Errors come back as data, humanized by Malli."
           (if errors
             [:div {:class ["rounded-lg border border-red-200 bg-red-50 p-4"]}
              [:p {:class ["mb-2 text-sm font-semibold text-red-800"]} "Does not conform"]
              [:pre {:class ["whitespace-pre-wrap font-mono text-xs text-red-900"]} (edn-text errors)]]
             [:p {:class ["rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm font-semibold text-emerald-800"]}
              (str id " conforms to the schema.")]))
          (malli-section
           "3. A contract made up from the schema"
           "Malli generates a valid instance from the schema alone -- the raw material of property-based testing. Nonsense by design: any string the model allows, any number. Every one validates."
           (if-let [made (:ok sample)]
             [:div {:class ["space-y-2"]}
              (malli-listing (json/generate-string made {:pretty true}))
              [:p {:class ["text-sm"]}
               [:span {:class ["mr-3 font-mono text-xs text-slate-500"]} (str "seed " seed)]
               [:a {:href (str (routes/contract-model-malli id) "?seed=" (inc seed))
                    :class ["font-medium text-blue-700 hover:underline"]}
                "Generate another →"]]]
             [:p {:class ["text-sm text-slate-600"]} (str "Malli could not generate one: " (:error sample))]))
          (malli-section
           "4. As JSON Schema"
           "The same model exported by Malli to JSON Schema, for anything outside Clojure that validates JSON. Concerto has its own exporter; this one is ours, from the schema above."
           (malli-listing (json/generate-string (model.malli/json-schema schema) {:pretty true})))
          (malli-section
           "5. As a diagram"
           "Malli's own Graphviz export: each type, its properties, and which types refer to which -- drawn in the page by Graphviz compiled to WebAssembly."
           [:div {:id "malli-diagram" :data-dot dot
                  :class ["overflow-auto rounded-lg border border-slate-200 bg-white p-4"]}
            [:p {:id "malli-diagram-loading" :class ["text-sm text-slate-500"]} "Drawing…"]]
           [:details {:class ["text-sm"]}
            [:summary {:class ["cursor-pointer text-slate-600"]} "The DOT source"]
            [:pre {:class (conj malli-block "mt-2")} dot]]
           [:script {:src viz-script}]
           [:script (chassis/raw-string draw-diagram)])])))))

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
         {:ui/title (str id " — Trustblocks")}
         (ui/workspace-header :contracts)
         [:main {:class ["mx-auto w-full max-w-5xl px-6 py-8"]}
          [:div {:class ["mb-6 flex flex-col gap-4 border-b border-slate-200 pb-6 sm:flex-row sm:items-end sm:justify-between"]}
           [:div
            [:a {:href (routes/contract id) :class ["text-sm font-medium text-blue-700 hover:underline"]}
             (str "← " id)]
            [:h1 {:class ["mt-2 text-3xl font-semibold tracking-tight text-slate-950"]} "Contract Document"]]
           (when grammar
             [:a {:href (routes/contract-document-docx id)
                  :class ["rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700 shadow-sm hover:border-blue-400 hover:text-blue-700"]}
              "Export to Word"])]
          (if grammar
            (into [:article {:class ["rounded-xl border border-slate-200 bg-white p-7 shadow-sm"]}]
                  (render/render grammar instance))
            [:div {:class ["rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center text-slate-500"]}
             "No contract template text is on file for this model."])])))))

(defn docx-response
  "A .docx download, or the reason it could not be made."
  [filename make]
  (try
    {:status  200
     :headers {"content-type" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
               "content-disposition" (str "attachment; filename=\"" filename "\"")}
     :body    (java.io.ByteArrayInputStream. (make))}
    (catch clojure.lang.ExceptionInfo e
      (if (#{:pandoc-unavailable :pandoc-failed} (:trustblocks/error (ex-data e)))
        {:status 501 :headers {"content-type" "text/plain; charset=utf-8"} :body (ex-message e)}
        (throw e)))))

(defn document-docx
  "The contract as drafted from its data, as a Word document."
  [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (routes/contract-id ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)
        grammar  (some->> (get instance "$class") (archive/for-class node) :archive/grammar)]
    (if-not (and instance grammar)
      (ui/on-error {:status 404})
      (docx-response (str id ".docx") #(word/docx grammar instance)))))

(def module
  {:biff.ring/routes
   ;; Every contract page is behind signin -- see lib.middleware/wrap-signed-in.
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/contracts) {:get list-page}]
     [(routes/contract) {:get show-page}]
     [(routes/contract-notes) {:post add-note}]
     [(routes/contract-document) {:get document-page}]
     [(routes/contract-document-docx) {:get document-docx}]
     [(routes/contract-model) {:get model-page}]
     [(routes/contract-model-concerto) {:get model-concerto-page}]
     [(routes/contract-model-malli) {:get model-malli-page}]]]})
