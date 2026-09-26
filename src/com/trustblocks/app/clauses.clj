(ns com.trustblocks.app.clauses
  "Operator-facing inventory of the clause logic currently stored in XTDB.

  Clauses are data and therefore part of the record, not source files the UI
  guesses about. This page reads through execute/list-clauses so its runtime
  defaults stay identical to trigger!'s interpretation of older records."
  (:require [clojure.string :as str]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.xtdb.execute :as execute]))

(defn- display-name [fqn]
  (-> fqn
      (str/split #"\.")
      last
      (str/replace #"([a-z0-9])([A-Z])" "$1 $2")))

(defn- clause-card [{:keys [class runtime source]}]
  [:article {:class ["overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm"]}
   [:div {:class ["flex flex-col gap-3 border-b border-slate-200 px-5 py-4 sm:flex-row sm:items-start sm:justify-between"]}
    [:div
     [:h2 {:class ["text-lg font-semibold text-slate-950"]} (display-name class)]
     [:p {:class ["mt-1 break-all font-mono text-xs text-slate-500"]} class]]
    [:span {:class ["w-fit rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold uppercase tracking-wide text-blue-700"]}
     (name runtime)]]
   [:pre {:class ["max-h-96 overflow-auto whitespace-pre-wrap bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-100"]}
    source]])

(defn list-page [{:keys [biff.xtdb/node]}]
  (let [clauses (execute/list-clauses node)]
    (ui/page
     {:ui/title "Installed Logic — Trustblocks"}
     (ui/workspace-header :templates)
     [:main {:class ["mx-auto w-full max-w-6xl px-6 py-8"]}
      [:div {:class ["mb-7"]}
       [:a {:href (routes/templates) :class ["text-sm font-medium text-blue-700 hover:underline"]} "← Templates"]
       [:p {:class ["mt-4 text-sm font-semibold uppercase tracking-widest text-blue-700"]} "Execution Registry"]
       [:h1 {:class ["mt-1 text-3xl font-semibold tracking-tight text-slate-950"]} "Installed Logic"]
       [:p {:class ["mt-2 max-w-3xl text-sm leading-6 text-slate-600"]}
        "The logic installed to execute each stored contract type -- from its template's code, or installed directly, as the walkthrough's examples are. Each runs inside the restricted SCI vocabulary and is recorded with every execution it produces."]]
      (if (empty? clauses)
        [:div {:class ["rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center text-slate-500"]}
         "No clauses are stored yet."]
        [:div {:class ["space-y-5"]} (map clause-card clauses)])])))

(def module
  {:biff.ring/routes
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/clauses) {:get list-page}]]]})
