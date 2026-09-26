(ns com.trustblocks.app.templates
  "The template library: every contract template this application runs, in
  Accord's three parts, each its own page:

    Text   the TemplateMark, drafted against the template's sample, and
           the source beneath it
    Model  its Concerto, as ingested
    Code   its logic -- the Clojure clause, the lifecycle it declares, the
           authority it requires to attest to it, and any TypeScript it
           carries -- or a plain statement that it has none.

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
            [com.trustblocks.lib.middleware :as mid]
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
   [:code  routes/template-code  "Code"]])

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
  [node {:archive/keys [root logic attestation-authority]}]
  (let [clause    (when root (execute/get-clause node root))
        lifecycle (when root (execute/get-lifecycle node root))
        pieces    (remove nil? [(when clause "clause")
                                (when lifecycle "lifecycle")
                                (when attestation-authority "attestation")
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
                   [:pre {:class code-block} (or (store/model-source node ns) "(source not available)")]])])))))

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
       (for [{:strs [event from to requires afterDays]} transitions]
         [:tr
          [:td {:class ["py-2"]} (if (seq from) (str/join ", " from) "—")]
          [:td {:class ["font-mono"]} event]
          [:td to]
          [:td (if afterDays
                 (str afterDays " days pass")
                 (str/join " or " (map #(str (get % "credential") ", " (get % "authority")) requires)))]])]]]))

(defn code-page [ctx]
  (with-template
    ctx
    (fn [node {:archive/keys [root logic attestation-authority kind] :as template}]
      (let [clause    (when root (execute/get-clause node root))
            lifecycle (when root (execute/get-lifecycle node root))]
        (shell template :code
               (if-not (or clause lifecycle attestation-authority (seq logic))
                 [:section {:class section}
                  [:h2 {:class ["mb-2 text-lg font-semibold text-slate-950"]} "No code"]
                  [:p {:class ["text-sm text-slate-600"]}
                   (str "This " kind " template is text and model only: it drafts, and its data is validated against its model, but nothing executes.")]]
                 [:div {:class ["space-y-6"]}
                  (when lifecycle
                    [:section {:class section}
                     [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Lifecycle"]
                     [:p {:class ["mb-4 text-sm text-slate-500"]}
                      "Declared by the template (lifecycle.json). The application enforces it: each event only in the states listed, certified as shown."]
                     (lifecycle-table lifecycle)])
                  (when clause
                    [:section {:class section}
                     [:div {:class ["mb-3 flex flex-wrap items-baseline gap-3"]}
                      [:h2 {:class ["text-lg font-semibold text-slate-950"]} "Clause"]
                      [:span {:class ["rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold uppercase tracking-wide text-blue-700"]}
                       (name (:runtime clause))]]
                     [:p {:class ["mb-4 text-sm text-slate-500"]}
                      "What follows from each event, run in the restricted sandbox with the document's data, the request, its state and the time."]
                     [:pre {:class code-block} (:source clause)]])
                  (when attestation-authority
                    [:section {:class section}
                     [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Attestation"]
                     [:p {:class ["text-sm text-slate-600"]}
                      (str "An officeholder attesting to this contract must hold " attestation-authority
                           ". The clause decides whether what they attest can be said yet.")]])
                  (for [{:keys [file source]} logic]
                    [:section {:class section}
                     [:h2 {:class ["mb-1 font-mono text-sm font-semibold text-slate-950"]} file]
                     [:p {:class ["mb-3 text-sm text-slate-500"]}
                      "Accord TypeScript logic, stored as ingested. Nothing runs it yet."]
                     [:pre {:class code-block} source]])]))))))

(def module
  {:biff.ring/routes
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/templates) {:get list-page}]
     [(routes/template-text) {:get text-page}]
     [(routes/template-model) {:get model-page}]
     [(routes/template-code) {:get code-page}]]]})
