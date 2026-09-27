(ns com.trustblocks.app.templates
  "The template library: every contract template this application runs, in
  Accord's three parts, each its own page:

    Text   the TemplateMark, drafted against the template's sample, and
           the source beneath it
    Model  its Concerto, as ingested
    Logic  its code, set apart from the application's: each request the
           contract answers, what it means (the model's own comment), who
           calls it and what the application checks first, and the clause
           lines that handle it; then the lifecycle and the full clause
           listing -- or a plain statement that it has none.

  There are no clauses here as such. Clauses -- a signature block, an
  indemnity -- live in TrustBlocks/trustblocks-templates as templates of
  their own, and are built into the contracts that use them there (their
  model and text copied in; see that repository's docs/templatemark-
  conformance.md, Clauses). What arrives here is the built contract, and
  the running contracts are its instances.

  Read-only, behind signin, like every page here. Templates are ingested
  from TrustBlocks/trustblocks-templates (com.trustblocks.template.
  library), not edited here."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.lib.code :as code]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.lifecycle :as lifecycle]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.render :as render]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]))

(def ^:private section
  ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"])

(def ^:private code-block
  ["max-h-[36rem] overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-100"])

(def ^:private parts
  [[:text  routes/template-text  "Text"]
   [:model routes/template-model "Model"]
   [:code  routes/template-code  "Logic"]])

(defn- kind-badge [kind]
  [:span {:class ["w-fit rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wide"
                  (if (= "clause" kind) "bg-amber-50 text-amber-800" "bg-blue-50 text-blue-700")]}
   kind])

(defn- own-namespace
  "The template's own namespace: its root type's."
  [{:archive/keys [root]}]
  (when root (subs root 0 (str/last-index-of root "."))))

(defn- logic-summary
  "What the template's Code page will show, in a phrase."
  [node {:archive/keys [root logic]}]
  (let [clause    (when root (execute/get-clause node root))
        lifecycle (when root (execute/get-lifecycle node root))
        pieces    (remove nil? [(when clause "clause")
                                (when lifecycle "lifecycle")
                                (when (execute/get-deadlines node root) "deadlines")
                                (when (lifecycle/attestation-authority lifecycle) "attestation")
                                (when (seq logic) "TypeScript")])]
    (if (seq pieces) (str/join " · " pieces) "text and model only")))

(defn- part-buttons [template-name active]
  [:nav {:class ["flex flex-wrap gap-2"]}
   (for [[k route label] parts]
     [:a {:href (route template-name)
          :class (if (= k active)
                   ["rounded-md bg-slate-900 px-3 py-2 text-sm font-semibold text-white"]
                   ["rounded-md border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 hover:border-blue-400 hover:text-blue-700"])}
      label])])

;; ------------------------------------------------------------------ list

(defn list-page [{:keys [biff.xtdb/node]}]
  (let [templates (archive/all node)]
    (ui/page
     {:ui/title "Templates — Trustblocks"}
     (ui/workspace-header :templates)
     [:main {:class ["mx-auto w-full max-w-6xl px-6 py-8"]}
      [:div {:class ["flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between"]}
       [:div
        [:p {:class ["text-sm font-semibold uppercase tracking-widest text-blue-700"]} "Template Library"]
        [:h1 {:class ["mt-1 text-3xl font-semibold tracking-tight text-slate-950"]} "Templates"]
        [:p {:class ["mt-2 max-w-3xl text-sm leading-6 text-slate-600"]}
         "The contract templates this application runs, in Accord's three parts: text, Concerto model and code. Clauses such as a signature block are built into these in the template repository, not listed separately."]]
       [:a {:href (routes/clauses) :class ["text-sm font-medium text-blue-700 hover:underline"]}
        "Installed logic, by contract type →"]]
      (if (empty? templates)
        [:div {:class ["mt-8 rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center text-slate-500"]}
         "No templates are ingested yet."]
        [:div {:class ["mt-8 space-y-4"]}
         (for [{:keys [xt/id] :archive/keys [version description kind] :as t} templates]
           [:article {:class section}
            [:div {:class ["flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between"]}
             [:div {:class ["min-w-0"]}
              [:div {:class ["flex flex-wrap items-center gap-3"]}
               [:h2 {:class ["text-lg font-semibold text-slate-950"]} id]
               (kind-badge kind)
               (when version [:span {:class ["font-mono text-xs text-slate-500"]} version])]
              (when description
                [:p {:class ["mt-2 max-w-3xl text-sm leading-6 text-slate-600"]} description])
              [:p {:class ["mt-2 font-mono text-xs text-slate-500"]}
               (str (own-namespace t) " · " (logic-summary node t))]]
             (part-buttons id nil)]])])])))

;; ------------------------------------------------------------------ one template

(defn- shell [template active body]
  (let [{:keys [xt/id] :archive/keys [kind version root]} template]
    (ui/page
     {:ui/title (str id " — Trustblocks")}
     (ui/workspace-header :templates)
     [:main {:class ["mx-auto w-full max-w-6xl space-y-6 px-6 py-8"]}
      [:a {:href (routes/templates) :class ["text-sm font-medium text-blue-700 hover:underline"]}
       "← All templates"]
      [:div {:class ["flex flex-col gap-4 border-b border-slate-200 pb-6 lg:flex-row lg:items-end lg:justify-between"]}
       [:div
        [:div {:class ["flex flex-wrap items-center gap-3"]}
         [:h1 {:class ["text-3xl font-semibold tracking-tight text-slate-950"]} id]
         (kind-badge kind)]
        [:p {:class ["mt-2 font-mono text-sm text-slate-500"]} (str root (when version (str " · " version)))]]
       (part-buttons id active)]
      body])))

(defn- with-template [{:keys [biff.xtdb/node] :as ctx} f]
  (if-let [template (archive/by-name node (routes/template-name ctx))]
    (f node template)
    (ui/on-error {:status 404})))

(defn text-page [ctx]
  (with-template
    ctx
    (fn [_ {:archive/keys [grammar sample] :as template}]
      (shell template :text
             [:div {:class ["space-y-6"]}
              [:section {:class section}
               [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Drafted from its sample"]
               [:p {:class ["mb-5 text-sm text-slate-500"]}
                "The template's own sample.json. Blanks the sample leaves empty draft as the original blank lines."]
               (cond
                 (not grammar) [:p {:class ["text-slate-500"]} "No text is on file for this template."]
                 (not sample) [:p {:class ["text-slate-500"]} "No sample is on file to draft from."]
                 :else (into [:article] (render/render grammar (json/parse-string sample))))]
              (when grammar
                [:section {:class section}
                 [:h2 {:class ["mb-3 text-lg font-semibold text-slate-950"]} "TemplateMark"]
                 [:pre {:class code-block} grammar]])]))))

(defn model-page [ctx]
  (with-template
    ctx
    (fn [node {:archive/keys [namespaces] :as template}]
      (let [own (own-namespace template)
            ;; Its own namespace first, then what it imports.
            ordered (cons own (remove #{own} namespaces))]
        (shell template :model
               [:div {:class ["space-y-6"]}
                (for [ns ordered]
                  [:section {:class section}
                   [:div {:class ["mb-3 flex flex-wrap items-baseline gap-3"]}
                    [:h2 {:class ["font-mono text-sm font-semibold text-slate-950"]} ns]
                    [:span {:class ["text-xs text-slate-500"]}
                     (if (= ns own) "the template's own namespace" "imported")]]
                   (if-let [source (store/model-source node ns)]
                     (code/cto-listing source)
                     [:p {:class ["text-sm text-slate-500"]} "(source not available)"])])])))))

(def ^:private request-type "org.accordproject.runtime@0.2.0.Request")
(def ^:private attestation-request lifecycle/attestation-request)

(defn- short-name [fqn] (subs fqn (inc (str/last-index-of fqn "."))))

(defn- source-lines [source] (str/split-lines (or source "")))

(defn- lines-naming
  "The 1-based lines of `source` that name request type `fqn` in a string
  -- where the clause matches it, as \"...PaymentApproved\" or by its full
  name."
  [source fqn]
  (let [needle (str (short-name fqn) "\"")]
    (keep-indexed (fn [i line] (when (str/includes? line needle) (inc i)))
                  (source-lines source))))

(defn- events
  "The requests the contract answers: every Request type its own namespace
  declares, and any other its clause names (the shared AttestationRequest),
  in the order the lifecycle lists them, then by name."
  [reg own source lifecycle]
  (let [request? #(some #{request-type} (rest (mm/super-chain reg %)))
        declared (filter #(and (= own (:namespace (get reg %))) (request? %)) (keys reg))
        named    (filter #(and (not= own (:namespace (get reg %))) (request? %)
                               (seq (lines-naming source %)))
                         (keys reg))
        order    (into {} (map-indexed (fn [i t] [(get t "event") i]) (get lifecycle "transitions")))]
    (sort-by (juxt #(get order (short-name %) 1000) short-name) (distinct (concat declared named)))))

(defn- doc-comment
  "The /** ... */ comment the model puts on `type-name`, as plain text."
  [cto type-name]
  (when cto
    (when-let [[_ body] (re-find (re-pattern (str "(?s)/\\*\\*((?:(?!\\*/).)*)\\*/\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*"
                                                  "(?:abstract\\s+)?transaction\\s+" type-name "\\b"))
                                 cto)]
      (->> (str/split-lines body)
           (map #(str/replace % #"^\s*\*\s?" ""))
           (str/join " ")
           str/trim
           not-empty))))

(defn- caller
  "Who calls the contract with this event, and what the application does
  first -- the application's side of the line."
  [fqn transition attestation-authority]
  (cond
    (= fqn attestation-request)
    (str "The application's certify form, after checking the officeholder holds "
         (or attestation-authority "the authority the template declares")
         " as of the event's date; what the contract answers is what gets signed "
         "(com.trustblocks.xtdb.certification/certify!).")

    transition
    (str "The application, when an officeholder signs "
         (str/join " or " (map #(str (if (re-find #"^[AEIOU]" (str (get % "credential"))) "an " "a ")
                                     (get % "credential") " holding " (get % "authority"))
                               (get transition "requires")))
         " -- through their own office grant, or a designation for the document's matter. "
         "It checks the authority as of the event's date, runs the contract, then signs the credential "
         "(com.trustblocks.xtdb.advance).")

    :else
    (str "The trigger endpoint, POST /contracts/:id/trigger. The template requires no certification "
         "for it, so the application checks only that the caller is signed in.")))

(defn- transition-for [lifecycle fqn]
  (first (filter #(= (short-name fqn) (get % "event")) (get lifecycle "transitions"))))

(defn- event-card [node reg source lifecycle attestation-authority fqn]
  (let [t     (transition-for lifecycle fqn)
        doc   (doc-comment (store/model-source node (:namespace (get reg fqn))) (short-name fqn))
        lines (lines-naming source fqn)]
    [:article {:class ["rounded-lg border border-slate-200 p-4"]}
     [:div {:class ["flex flex-wrap items-baseline justify-between gap-2"]}
      [:h3 {:class ["font-mono text-sm font-semibold text-slate-950"]} (short-name fqn)]
      (when t
        [:span {:class ["text-xs text-slate-500"]}
         (str (if (seq (get t "from")) (str/join ", " (get t "from")) "start") " → " (get t "to"))])]
     (when doc [:p {:class ["mt-1 text-sm text-slate-700"]} doc])
     [:dl {:class ["mt-3 grid gap-2 text-sm sm:grid-cols-[9rem_1fr]"]}
      [:dt {:class ["text-xs font-semibold uppercase tracking-wide text-slate-500"]} "Called by"]
      [:dd {:class ["text-slate-700"]} (caller fqn t attestation-authority)]
      [:dt {:class ["text-xs font-semibold uppercase tracking-wide text-slate-500"]} "Contract code"]
      [:dd {:class ["text-slate-700"]}
       (cond
         (seq lines)
         [:span "Handled by the clause at "
          (interpose ", " (for [l lines] [:a {:href (str "#L" l) :class ["font-mono text-blue-700 hover:underline"]}
                                          (str "line " l)]))]

         source "The clause records nothing beyond the lifecycle's move; it passes this event through."
         :else "No clause: the lifecycle's move is the whole answer.")]]]))

(defn- listing [source highlighted]
  [:div {:class ["overflow-auto rounded-lg bg-slate-950 py-4 font-mono text-xs leading-6 text-slate-100"]}
   (for [[i line] (map-indexed vector (source-lines source))
         :let [n (inc i)]]
     [:div {:id (str "L" n)
            :class (cond-> ["flex whitespace-pre pr-5"]
                     (highlighted n) (conj "bg-blue-900/60"))}
      [:span {:class ["w-12 shrink-0 select-none pr-4 text-right text-slate-500"]} n]
      [:span {:class (if (str/starts-with? (str/triml line) ";") ["text-emerald-300"] ["text-slate-100"])}
       (if (str/blank? line) " " line)]])])

(defn- lifecycle-table [{:strs [stateType states transitions]}]
  (let [accepting (set (map #(get % "name") (filter #(get % "accepting") states)))]
    [:div
     [:p {:class ["mb-3 text-sm text-slate-600"]}
      (str "State type " stateType ". States: "
           (str/join ", " (map #(str (get % "name") (when (accepting (get % "name")) " (final)")) states)) ".")]
     [:table {:class ["w-full text-left text-sm"]}
      [:thead {:class ["text-xs uppercase tracking-wide text-slate-500"]}
       [:tr [:th {:class ["py-2"]} "From"] [:th "Event"] [:th "To"] [:th "Certified by"]]]
      [:tbody {:class ["divide-y divide-slate-100"]}
       (for [{:strs [event from to requires]} transitions]
         [:tr
          [:td {:class ["py-2"]} (if (seq from) (str/join ", " from) "—")]
          [:td {:class ["font-mono"]} event]
          [:td to]
          [:td (str/join " or " (map #(str (get % "credential") ", " (get % "authority")) requires))]])]]]))

(defn- deadlines-table [{:strs [deadlines]}]
  [:table {:class ["w-full text-left text-sm"]}
   [:thead {:class ["text-xs uppercase tracking-wide text-slate-500"]}
    [:tr [:th {:class ["py-2"]} "Deadline"] [:th "Counted from"] [:th "Days"] [:th "Met by"]]]
   [:tbody {:class ["divide-y divide-slate-100"]}
    (for [{:strs [name description fromField fromState days daysField source metBy]} deadlines
          :let [of (if (= "TERMS" source) " (the terms)" "")]]
      [:tr {:class ["align-top"]}
       [:td {:class ["py-2 pr-4"]} [:p {:class ["font-medium text-slate-900"]} name]
        (when description [:p {:class ["text-xs text-slate-500"]} description])]
       [:td {:class ["pr-4"]} (if fromField (str fromField of) (str "entering " fromState))]
       [:td {:class ["pr-4"]} (or (some-> days str) (str daysField of))]
       [:td (if (seq metBy) (str/join ", " metBy) "--")]])]])

(def ^:private division
  "Where the line falls between the contract's code and the application's."
  [:section {:class ["grid gap-4 md:grid-cols-2"]}
   [:div {:class ["rounded-xl border border-blue-200 bg-blue-50 p-5"]}
    [:h2 {:class ["text-sm font-semibold uppercase tracking-wide text-blue-800"]} "The contract's code"]
    [:p {:class ["mt-2 text-sm leading-6 text-blue-950"]}
     "From the template, part of the contract. Its lifecycle says which events can happen, in which states, certified by whom; its clause says what follows from each. The clause runs in a sandbox with the contract's data, the request, its state and the time -- no clock, no files, no network -- so the same event always has the same answer, wherever it runs."]]
   [:div {:class ["rounded-xl border border-slate-200 bg-white p-5"]}
    [:h2 {:class ["text-sm font-semibold uppercase tracking-wide text-slate-700"]} "The application"]
    [:p {:class ["mt-2 text-sm leading-6 text-slate-700"]}
     "The environment the contract runs in, the same for every contract. It verifies who is acting and that they held the authority on the event's date, supplies the time, stores the files a credential names, records every execution bitemporally, and signs the credential that records the act. It decides nothing the contract decides."]]])

(defn code-page [ctx]
  (with-template
    ctx
    (fn [node {:archive/keys [root logic kind] :as template}]
      (let [reg       (store/registry-from-xtdb node)
            clause    (when root (execute/get-clause node root))
            source    (:source clause)
            lifecycle (when root (execute/get-lifecycle node root))
            attestation-authority (lifecycle/attestation-authority lifecycle)
            deadlines (when root (execute/get-deadlines node root))
            evs       (events reg (own-namespace template) source lifecycle)
            lines     (set (mapcat #(lines-naming source %) evs))]
        (shell template :code
               [:div {:class ["space-y-6"]}
                division
                (if-not (or clause lifecycle deadlines (seq logic))
                  [:section {:class section}
                   [:h2 {:class ["mb-2 text-lg font-semibold text-slate-950"]} "No logic"]
                   [:p {:class ["text-sm text-slate-600"]}
                    (str "This " kind " template is text and model only: it drafts, and its data is validated against its model, but nothing executes.")]]
                  [:div {:class ["space-y-6"]}
                   (when (seq evs)
                     [:section {:class section}
                      [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "What calls the contract"]
                      [:p {:class ["mb-4 text-sm text-slate-500"]}
                       "Each request the contract answers: what it means, who calls it and what the application checks first, and where the contract's code handles it."]
                      [:div {:class ["space-y-3"]}
                       (map #(event-card node reg source lifecycle attestation-authority %) evs)]])
                   (when deadlines
                     [:section {:class section}
                      [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Deadlines"]
                      [:p {:class ["mb-4 text-sm text-slate-500"]}
                       "Declared by the template (deadlines.json): the dates the contract sets. Shown on the timeline and the dashboard; never acted on -- no step happens, and no notice goes out, because one passes."]
                      (deadlines-table deadlines)])
                   (when lifecycle
                     [:section {:class section}
                      [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Lifecycle"]
                      [:p {:class ["mb-4 text-sm text-slate-500"]}
                       "Declared by the template (lifecycle.json). Each event only in the states listed, certified as shown."]
                      (lifecycle-table lifecycle)])
                   (when clause
                     [:section {:class section}
                      [:div {:class ["mb-3 flex flex-wrap items-baseline gap-3"]}
                       [:h2 {:class ["text-lg font-semibold text-slate-950"]} "Clause"]
                       [:span {:class ["rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold uppercase tracking-wide text-blue-700"]}
                        (name (:runtime clause))]
                       [:span {:class ["text-xs text-slate-500"]} "highlighted: where each request is handled"]]
                      (listing source lines)])
                   (for [{:keys [file source]} logic]
                     [:section {:class section}
                      [:h2 {:class ["mb-1 font-mono text-sm font-semibold text-slate-950"]} file]
                      [:p {:class ["mb-3 text-sm text-slate-500"]}
                       "Accord TypeScript logic, stored as ingested. Nothing runs it yet."]
                      (listing source #{})])])])))))

(def module
  {:biff.ring/routes
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/templates) {:get list-page}]
     [(routes/template-text) {:get text-page}]
     [(routes/template-model) {:get model-page}]
     [(routes/template-code) {:get code-page}]]]})
